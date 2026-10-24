package com.sengine.engine.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.sengine.engine.Engine
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Camera3D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Collider3D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Light
import com.sengine.engine.core.Light2D
import com.sengine.engine.core.MeshRenderer
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.math.Affine
import com.sengine.engine.math.Mat4
import com.sengine.engine.platform.android.GlInfo
import com.sengine.engine.platform.android.GlSurface
import com.sengine.engine.platform.android.GraphicsStatus
import com.sengine.engine.render.api.GraphicsBackend
import com.sengine.engine.render.api.GraphicsBackendSelector
import com.sengine.engine.render.api.GraphicsInitException
import com.sengine.engine.render.api.ShaderSourceSet
import com.sengine.engine.render.gles2.Gles2Shaders
import com.sengine.engine.render.gles3.Gles3Shaders
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the scene (2D or 3D), the screen-space UI layer, post effects and — when [editor]
 * is non-null and the engine is in edit mode — editor grids and gizmos.
 */
class SceneRenderer(private val engine: Engine, private val editor: EditorState?) : GLSurfaceView.Renderer {

    private val r = Renderer2D()
    private val r3 = Renderer3D()
    private val shaders = ShaderLibrary(engine.project) { engine.log(2, it) }
    private val post = PostProcessor()
    private val lighting = Lighting2D()
    private val textures = TextureCache(engine.project).also { cache -> cache.onLog = { lvl, msg -> engine.log(lvl, msg) } }
    private val gameView3D = View3D()
    private val shakeView = View2D()
    private val uiView = View2D()
    private var lastNs = 0L
    private var width = 1
    private var height = 1
    private val tmp = Affine()
    private val tmp2 = Affine()
    private val m4 = FloatArray(16)
    private val m4b = FloatArray(16)
    private val rnd = java.util.Random()
    /** True when the last context could not start the renderer; frames then show an error colour only. */
    private var initFailed = false
    /** Number of GL contexts this renderer has been given (1 on first start, +1 per recreation). */
    private var contextCount = 0
    private var surfaceW = -1
    private var surfaceH = -1

    /**
     * Runs on the GL thread every time a context is created (first start, resume, or after the
     * driver dropped the context). All GPU objects from a previous context are abandoned here and
     * rebuilt, so no handle from an old context is ever reused.
     */
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        lastNs = System.nanoTime()
        initFailed = false
        contextCount++
        surfaceW = -1; surfaceH = -1
        var description = ""
        try {
            val caps = GlInfo.query()
            val selected = GraphicsBackendSelector.select(caps)
            description = caps.describe(selected)
            if (selected == null) {
                throw GraphicsInitException("Unsupported OpenGL context. S Engine needs OpenGL ES 2.0 or 3.0. $description")
            }
            // Report the context that was really created, from the driver's own strings.
            report("GL context #$contextCount: $description", 0)
            // ES 3 context: prefer the ES 3 shader set. If it fails to build on this driver, fall back to the
            // ES 2 (GLSL ES 1.00) set on the same context; the draw calls are identical, only the shaders differ.
            val candidates = if (selected == GraphicsBackend.GLES30) {
                listOf(GraphicsBackend.GLES30 to Gles3Shaders, GraphicsBackend.GLES20 to Gles2Shaders)
            } else {
                listOf(GraphicsBackend.GLES20 to Gles2Shaders)
            }
            var active: Pair<GraphicsBackend, ShaderSourceSet>? = null
            var lastError: GraphicsInitException? = null
            for ((backend, sources) in candidates) {
                try {
                    shaders.init(sources)
                    r.init(shaders, sources)
                    r3.init(shaders, sources)
                    active = backend to sources
                    break
                } catch (e: GraphicsInitException) {
                    lastError = e
                    report("${backend.label} shaders could not be built: ${e.message}", 2)
                }
            }
            val (activeBackend, activeSources) = active ?: throw (lastError ?: GraphicsInitException("No shader set could be built"))
            post.init()
            lighting.init(activeSources)
            textures.clear()
            val shaderNote = if (activeBackend == selected) "" else " | fell back to ${activeBackend.label} shaders"
            GraphicsStatus.succeeded(activeBackend, description + " | shader dialect: ${activeSources.dialect.name}" + shaderNote)
            report("Renderer ready (context #$contextCount): ${activeBackend.label}, shader dialect ${activeSources.dialect.name}$shaderNote", 0)
            val errors = GL.drainErrors()
            if (errors.isNotEmpty()) {
                report("GL errors after renderer init: " + errors.joinToString { "0x" + Integer.toHexString(it) }, 2)
            }
        } catch (e: Throwable) {
            initFailed = true
            GraphicsStatus.failed(e.message ?: e.javaClass.simpleName, description)
            report("Graphics init failed (context #$contextCount): ${e.message ?: e.javaClass.simpleName}", 2)
        }
    }

    private fun report(message: String, level: Int) {
        if (level >= 2) Log.e(GlSurface.TAG, message) else Log.i(GlSurface.TAG, message)
        engine.log(level, message)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        if (width != surfaceW || height != surfaceH) {
            surfaceW = width; surfaceH = height
            report("Surface ${width}x${height} (context #$contextCount)", 0)
        }
        this.width = width; this.height = height
        GLES20.glViewport(0, 0, width, height)
        engine.gameView.widthPx = width; engine.gameView.heightPx = height
        editor?.view?.let { it.widthPx = width; it.heightPx = height }
        editor?.view3D?.let { it.widthPx = width; it.heightPx = height }
        gameView3D.widthPx = width; gameView3D.heightPx = height
    }

    override fun onDrawFrame(gl: GL10?) {
        if (initFailed) {
            GLES20.glClearColor(0.35f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        synchronized(engine.lock) {
            engine.tick(dt)
            val t0 = System.nanoTime()
            textures.beginFrame()
            r.drawCalls = 0; r3.drawCalls = 0
            val time = engine.time.toFloat()
            r.time = time; r3.time = time
            r.resW = width.toFloat(); r.resH = height.toFloat(); r3.resW = r.resW; r3.resH = r.resH
            val editing = editor != null && engine.mode == Engine.Mode.EDIT
            val cam3 = engine.mainCamera3D()
            val use3D = if (editing) editor!!.mode3D else cam3 != null

            // post effect selection (play mode only)
            var postProg: PostProgram? = null
            var intensity = 1f
            if (!editing) {
                var fx = 0; var shader = ""
                if (use3D) cam3?.get<Camera3D>()?.let { fx = it.postFx; shader = it.postShader; intensity = it.postIntensity }
                else engine.mainCamera()?.get<Camera2D>()?.let { fx = it.postFx; shader = it.postShader; intensity = it.postIntensity }
                if (fx > 0) postProg = shaders.post(if (fx == POST_CUSTOM) 0 else fx, if (fx == POST_CUSTOM) shader else "")
            }
            if (postProg != null) post.begin(width, height)

            if (use3D) render3D(editing, cam3) else render2D(editing)
            if (!use3D) drawLighting2D(editing)
            drawScreenUI(editing, use3D)

            if (post.active && postProg != null) r.drawCalls += post.end(postProg, time, intensity)
            engine.drawCalls = r.drawCalls + r3.drawCalls
            engine.textureBytes = textures.usedBytes
            engine.renderMs = (System.nanoTime() - t0) / 1e6f
        }
    }

    // ------------------------------------------------------------------ 2D

    private fun render2D(editing: Boolean) {
        val view: View2D
        if (editing) view = editor!!.view
        else {
            shakeView.copyFrom(engine.gameView)
            val cam2 = engine.mainCamera()?.get<Camera2D>()
            if (cam2 != null && (cam2.shakeOffX != 0f || cam2.shakeOffY != 0f)) {
                shakeView.cx += cam2.shakeOffX
                shakeView.cy += cam2.shakeOffY
            }
            view = shakeView
        }
        val bg = if (editing) 0xFF262B33.toInt() else engine.backgroundColor()
        GLES20.glClearColor(GL.r(bg), GL.g(bg), GL.b(bg), 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        r.begin(view)
        if (editing && editor!!.showGrid) drawGrid(view)
        val ppu = view.pixelsPerUnit
        for (go in sortedObjects()) {
            val ui = isScreenSpace(go)
            if (ui && !editing) continue
            drawObject2D(go, ppu, null)
        }
        if (editing) drawEditorOverlay(view, editor!!)
        // Light map composite happens in onDrawFrame so screen-space UI stays unlit.
        lightView = view
    }

    private var lightView: View2D? = null
    private var lightsThisFrame = false

    private fun drawLighting2D(editing: Boolean) {
        lightsThisFrame = false
        if (editing) return
        val scene = engine.scene
        var hasLight = false
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            if (go.get<Light2D>() != null) { hasLight = true; break }
        }
        if (!hasLight) return
        val view = lightView ?: return
        val ambient = lighting.ambientColor(scene, LIGHT_AMBIENT)
        if (!lighting.begin(width, height, ambient)) return
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val light = go.get<Light2D>() ?: continue
            if (light.kind == 0) continue
            val cookie = if (light.cookie.isNotBlank()) textures.image(light.cookie) else null
            lighting.drawLight(go, view, cookie)
        }
        r.drawCalls += lighting.end(r, width, height)
        lightsThisFrame = true
    }

    private fun sortedObjects(): List<GameObject> = Draw2DRules.drawOrder(engine.scene.objects)

    private fun isScreenSpace(go: GameObject): Boolean =
        go.get<SpriteRenderer>()?.screenSpace == true || go.get<TextRenderer>()?.screenSpace == true

    /** Draws sprite / text / particles of [go]. When [m3] is given the object is placed in 3D with that matrix. */
    private fun drawObject2D(go: GameObject, ppu: Float, m3: FloatArray?) {
        val w = go.world
        go.get<SpriteRenderer>()?.let { sr ->
            val texName = sr.animTexture ?: sr.texture
            val tex = if (texName.isNotBlank()) textures.image(texName) else null
            val prog = if (sr.shader.isNotBlank()) shaders.sprite(sr.shader) else null
            val shape = if (tex != null) 0 else sr.shape
            val pivotX = Draw2DRules.pivotOffset(sr.pivotX)
            val pivotY = Draw2DRules.pivotOffset(sr.pivotY)
            if (m3 != null) {
                val spriteMatrix = if (pivotX == 0f && pivotY == 0f) m3 else {
                    Mat4.trs(m4b, pivotX, pivotY, 0f, 0f, 0f, 0f, 1f, 1f, 1f)
                    Mat4.mul(m4, m3, m4b)
                    m4
                }
                r.quadModel(spriteMatrix, sr.color, shape, tex, 200f, sr.flipX, sr.flipY, sr.uv, prog, sr.shaderParam)
            } else {
                val spriteWorld = if (pivotX == 0f && pivotY == 0f) w else {
                    tmp2.identity(); tmp2.tx = pivotX; tmp2.ty = pivotY
                    tmp.setMul(w, tmp2)
                }
                r.quad(spriteWorld, sr.color, shape, tex, min(w.scaleX, w.scaleY) * ppu, sr.flipX, sr.flipY, sr.uv, prog, sr.shaderParam)
            }
        }
        go.get<TextRenderer>()?.let { tr ->
            if (tr.text.isNotEmpty()) {
                val tex = textures.text(tr.text, tr.bold, tr.align)
                val hh = Draw2DRules.textHeight(tr.size, Draw2DRules.textLineCount(tr.text))
                val ww = Draw2DRules.textWidth(hh, tex.w.toFloat(), tex.h.toFloat())
                val ox = Draw2DRules.textOriginX(tr.align, ww)
                if (m3 != null) {
                    Mat4.trs(m4b, ox, 0f, 0f, 0f, 0f, 0f, ww, hh, 1f)
                    Mat4.mul(m4, m3, m4b)
                    r.quadModel(m4, tr.color, 0, tex, 100f)
                } else {
                    tmp2.a = ww; tmp2.b = 0f; tmp2.c = 0f; tmp2.d = hh; tmp2.tx = ox; tmp2.ty = 0f
                    tmp.setMul(w, tmp2)
                    r.quad(tmp, tr.color, 0, tex, 100f)
                }
            }
        }
        go.getAny<ParticleEmitter>()?.let { pe -> drawParticles(pe, ppu, if (m3 != null) m3[14] else null) }
    }

    private fun drawParticles(pe: ParticleEmitter, ppu: Float, z: Float?) {
        if (pe.particles.isEmpty()) return
        val tex = if (pe.texture.isNotBlank()) textures.image(pe.texture) else null
        if (pe.additive) GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        for (p in pe.particles) {
            val t = Draw2DRules.particleT(p.age, p.life)
            val size = Draw2DRules.particleSize(pe.startSize, pe.endSize, t, p.sizeScale)
            if (size <= 0f) continue
            val c = Draw2DRules.lerpColor(pe.startColor, pe.endColor, t)
            if (z != null) {
                Mat4.trs(m4, p.x, p.y, z, 0f, 0f, p.rotation, size, size, size)
                r.quadModel(m4, c, if (tex != null) 0 else 1, tex, 100f)
            } else {
                tmp.setTRS(p.x, p.y, p.rotation, size, size)
                r.quad(tmp, c, if (tex != null) 0 else 1, tex, ppu)
            }
        }
        if (pe.additive) GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    // ------------------------------------------------------------------ Screen-space UI

    private fun drawScreenUI(editing: Boolean, use3D: Boolean) {
        if (editing && !use3D) return // drawn in world space while editing 2D
        val items = sortedObjects().filter { isScreenSpace(it) }
        if (items.isEmpty()) return
        uiView.cx = 0f; uiView.cy = 0f; uiView.size = 5f; uiView.widthPx = width; uiView.heightPx = height
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        r.begin(uiView)
        val ppu = uiView.pixelsPerUnit
        for (go in items) drawObject2D(go, ppu, null)
    }

    // ------------------------------------------------------------------ 3D

    private fun render3D(editing: Boolean, camGo: GameObject?) {
        val scene = engine.scene
        val cam = camGo?.get<Camera3D>()
        val v: View3D
        if (editing) {
            editor!!.updateView3D()
            v = editor.view3D
        } else {
            v = gameView3D
            if (cam != null && camGo != null) {
                v.fov = cam.fov; v.near = cam.near; v.far = cam.far
                val target = if (cam.lookAtTarget && cam.follow.isNotBlank()) scene.find(cam.follow)?.world3 else null
                val jx = if (cam.shake > 0f) (rnd.nextFloat() * 2f - 1f) * cam.shake * 0.2f else 0f
                val jy = if (cam.shake > 0f) (rnd.nextFloat() * 2f - 1f) * cam.shake * 0.2f else 0f
                v.setFromWorld(camGo.world3, target?.let { floatArrayOf(it[12], it[13], it[14]) }, jx, jy)
            }
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        r3.drawSky(v, cam?.skyTop ?: 0xFF3B7BD4.toInt(), cam?.skyHorizon ?: 0xFFBFD8F0.toInt())

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)
        r3.setupLights(scene, v)

        val objs = sortedObjects()
        val transparent = ArrayList<Pair<Float, GameObject>>()
        for (go in objs) {
            val mr = go.get<MeshRenderer>() ?: continue
            if (GL.a(mr.color) < 0.999f) { transparent.add(v.distanceTo(go.world3[12], go.world3[13], go.world3[14]) to go); continue }
            drawMesh(go, mr)
        }
        // transparent meshes, sprites, text & particles: back to front, no depth writes
        GLES20.glDepthMask(false)
        for (go in objs) {
            if (go.get<MeshRenderer>() != null || isScreenSpace(go)) continue
            if (go.get<SpriteRenderer>() == null && go.get<TextRenderer>() == null && go.getAny<ParticleEmitter>() == null) continue
            transparent.add(v.distanceTo(go.world3[12], go.world3[13], go.world3[14]) to go)
        }
        r.begin(v.viewProj)
        for ((_, go) in transparent.sortedByDescending { it.first }) {
            val mr = go.get<MeshRenderer>()
            if (mr != null) drawMesh(go, mr) else drawObject2D(go, 100f, go.world3)
        }
        GLES20.glDepthMask(true)

        if (editing) drawEditorOverlay3D(v, editor!!)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawMesh(go: GameObject, mr: MeshRenderer) {
        val mesh = if (mr.mesh == MeshRenderer.MESHES.size - 1) {
            (if (mr.model.isNotBlank()) Meshes.model(engine.project.assetFile(mr.model)) else null) ?: Meshes.primitive(0)
        } else Meshes.primitive(mr.mesh)
        val tex = if (mr.texture.isNotBlank()) textures.image(mr.texture) else null
        val prog = if (mr.shader.isNotBlank()) shaders.mesh(mr.shader) else null
        r3.drawMesh(mesh, go.world3, mr, tex, prog)
    }

    private fun meshBounds(go: GameObject): Pair<FloatArray, FloatArray> {
        val mr = go.get<MeshRenderer>()
        if (mr != null) {
            val mesh = if (mr.mesh == MeshRenderer.MESHES.size - 1) {
                (if (mr.model.isNotBlank()) Meshes.model(engine.project.assetFile(mr.model)) else null) ?: Meshes.primitive(0)
            } else Meshes.primitive(mr.mesh)
            return mesh.min to mesh.max
        }
        return floatArrayOf(-0.5f, -0.5f, -0.05f) to floatArrayOf(0.5f, 0.5f, 0.05f)
    }

    private fun drawEditorOverlay3D(v: View3D, ed: EditorState) {
        r.begin(v.viewProj)
        if (ed.showGrid) {
            val step = if (ed.distance > 60f) 5f else 1f
            val n = 40
            val cx = floor(ed.orbitX / step) * step
            val cz = floor(ed.orbitZ / step) * step
            for (i in -n..n) {
                val x = cx + i * step
                val z = cz + i * step
                val cX = if (abs(x) < 1e-3f) 0xAA4D8CFF.toInt() else if (Math.round(x / step) % 5 == 0) 0x44FFFFFF else 0x22FFFFFF
                val cZ = if (abs(z) < 1e-3f) 0xAAFF5C5C.toInt() else if (Math.round(z / step) % 5 == 0) 0x44FFFFFF else 0x22FFFFFF
                r.line3(x, 0f, cz - n * step, x, 0f, cz + n * step, cX)
                r.line3(cx - n * step, 0f, z, cx + n * step, 0f, z, cZ)
            }
            r.flushLines(1f)
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        for (go in engine.scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val w = go.world3
            val x = w[12]; val y = w[13]; val z = w[14]
            val s = ed.gizmoLength3D(x, y, z) * 0.25f
            go.get<Camera3D>()?.let { cam ->
                val d = s * 3f
                val hh = (Math.tan(Math.toRadians(cam.fov / 2.0)) * d).toFloat()
                val hw = hh * v.aspect
                val corners = arrayOf(floatArrayOf(-hw, -hh, -d), floatArrayOf(hw, -hh, -d), floatArrayOf(hw, hh, -d), floatArrayOf(-hw, hh, -d)).map { Mat4.point(w, it[0], it[1], it[2]) }
                for (i in 0 until 4) {
                    val a = corners[i]; val b = corners[(i + 1) % 4]
                    r.line3(x, y, z, a[0], a[1], a[2], 0xCCFFFFFF.toInt())
                    r.line3(a[0], a[1], a[2], b[0], b[1], b[2], 0xCCFFFFFF.toInt())
                }
            }
            go.get<Light>()?.let { l ->
                val c = l.color or 0xFF000000.toInt()
                if (l.kind == 1) {
                    r.circle3(x, y, z, s, 0, c, 16); r.circle3(x, y, z, s, 1, c, 16); r.circle3(x, y, z, s, 2, c, 16)
                    if (go.id == ed.selectedId) r.circle3(x, y, z, l.range, 1, 0x66FFFFFF, 48)
                } else {
                    r.circle3(x, y, z, s, 2, c, 16)
                    val d = Mat4.dir(w, 0f, 0f, -1f)
                    r.line3(x, y, z, x + d[0] * s * 4, y + d[1] * s * 4, z + d[2] * s * 4, c)
                }
            }
            if (ed.showColliders || go.id == ed.selectedId) go.get<Collider3D>()?.let { c ->
                val color = if (c.isTrigger) 0xCC4FC3F7.toInt() else 0xCC66FF66.toInt()
                if (c.shape == 1) {
                    val p = Mat4.point(w, c.centerX, c.centerY, c.centerZ)
                    val rad = c.radius * max(Mat4.scaleOf(w, 0), max(Mat4.scaleOf(w, 1), Mat4.scaleOf(w, 2)))
                    r.circle3(p[0], p[1], p[2], rad, 0, color); r.circle3(p[0], p[1], p[2], rad, 1, color); r.circle3(p[0], p[1], p[2], rad, 2, color)
                } else {
                    r.wireBox(w, floatArrayOf(c.centerX - c.sizeX / 2, c.centerY - c.sizeY / 2, c.centerZ - c.sizeZ / 2),
                        floatArrayOf(c.centerX + c.sizeX / 2, c.centerY + c.sizeY / 2, c.centerZ + c.sizeZ / 2), color)
                }
            }
            if (go.get<MeshRenderer>() == null && go.get<Camera3D>() == null && go.get<Light>() == null &&
                go.get<SpriteRenderer>() == null && go.get<TextRenderer>() == null) {
                r.line3(x - s, y, z, x + s, y, z, 0x99FFFFFF.toInt())
                r.line3(x, y - s, z, x, y + s, z, 0x99FFFFFF.toInt())
                r.line3(x, y, z - s, x, y, z + s, 0x99FFFFFF.toInt())
            }
        }
        r.flushLines(2f)
        val sel = engine.scene.findById(ed.selectedId)
        if (sel != null && sel.isActiveInHierarchy()) {
            val (mn, mx) = meshBounds(sel)
            r.wireBox(sel.world3, mn, mx, 0xFFFF9F1C.toInt())
            r.flushLines(2f)
            drawGizmo3D(sel, ed)
        }
    }

    private fun drawGizmo3D(go: GameObject, ed: EditorState) {
        val w = go.world3
        val x = w[12]; val y = w[13]; val z = w[14]
        val len = ed.gizmoLength3D(x, y, z)
        val hi = 0xFFFFFF66.toInt()
        val red = if (ed.activeAxis == 1) hi else 0xFFFF4D4D.toInt()
        val green = if (ed.activeAxis == 2) hi else 0xFF5CE65C.toInt()
        val blue = if (ed.activeAxis == 3) hi else 0xFF4D8CFF.toInt()
        when (ed.tool) {
            Tool.MOVE, Tool.SCALE -> {
                r.line3(x, y, z, x + len, y, z, red)
                r.line3(x, y, z, x, y + len, z, green)
                r.line3(x, y, z, x, y, z + len, blue)
                r.flushLines(5f)
                val hs = len * 0.08f
                val mn = floatArrayOf(-hs, -hs, -hs); val mx = floatArrayOf(hs, hs, hs)
                val id = Mat4.identity()
                fun tip(px: Float, py: Float, pz: Float, c: Int) { id[12] = px; id[13] = py; id[14] = pz; r.wireBox(id, mn, mx, c) }
                tip(x + len, y, z, red); tip(x, y + len, z, green); tip(x, y, z + len, blue)
                tip(x, y, z, if (ed.activeAxis == 4) hi else 0xFFFFD24D.toInt())
                r.flushLines(3f)
            }
            Tool.ROTATE -> {
                r.circle3(x, y, z, len, 0, red, 64)
                r.circle3(x, y, z, len, 1, green, 64)
                r.circle3(x, y, z, len, 2, blue, 64)
                r.flushLines(3f)
            }
            Tool.HAND -> {}
        }
    }

    // ------------------------------------------------------------------ 2D editor overlay

    private fun drawGrid(view: View2D) {
        val ppu = view.pixelsPerUnit
        var step = 1f
        while (step * ppu < 24f) step *= 5f
        while (step * ppu > 200f) step /= 5f
        val left = view.cx - view.halfW
        val right = view.cx + view.halfW
        val bottom = view.cy - view.size
        val top = view.cy + view.size
        val minor = 0x22FFFFFF
        val major = 0x44FFFFFF
        var x = floor(left / step) * step
        var i = 0
        while (x <= right && i++ < 400) {
            val idx = Math.round(x / step)
            r.line(x, bottom, x, top, if (abs(x) < step * 0.01f) 0xAA7FA7FF.toInt() else if (idx % 5 == 0) major else minor)
            x += step
        }
        var y = floor(bottom / step) * step
        i = 0
        while (y <= top && i++ < 400) {
            val idx = Math.round(y / step)
            r.line(left, y, right, y, if (abs(y) < step * 0.01f) 0xAAFF7F7F.toInt() else if (idx % 5 == 0) major else minor)
            y += step
        }
        r.flushLines(1f)
    }

    private fun drawEditorOverlay(view: View2D, ed: EditorState) {
        val scene = engine.scene
        val ppu = view.pixelsPerUnit
        val aspect = view.aspect
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val w = go.world
            go.get<Camera2D>()?.let { cam ->
                val cw = cam.size * aspect
                tmp.a = 1f; tmp.b = 0f; tmp.c = 0f; tmp.d = 1f; tmp.tx = w.tx; tmp.ty = w.ty
                r.obb(tmp, cw, cam.size, 0xCCFFFFFF.toInt())
                val s = 12f / ppu
                r.line(w.tx - s, w.ty, w.tx + s, w.ty, 0xCCFFFFFF.toInt())
                r.line(w.tx, w.ty - s, w.tx, w.ty + s, 0xCCFFFFFF.toInt())
            }
            if (ed.showColliders || go.id == ed.selectedId) go.get<Collider2D>()?.let { c -> drawCollider(go, c) }
            if (go.get<SpriteRenderer>() == null && go.get<TextRenderer>() == null && go.get<Camera2D>() == null) {
                val s = 8f / ppu
                r.line(w.tx - s, w.ty, w.tx, w.ty + s, 0x99FFFFFF.toInt())
                r.line(w.tx, w.ty + s, w.tx + s, w.ty, 0x99FFFFFF.toInt())
                r.line(w.tx + s, w.ty, w.tx, w.ty - s, 0x99FFFFFF.toInt())
                r.line(w.tx, w.ty - s, w.tx - s, w.ty, 0x99FFFFFF.toInt())
            }
        }
        val sel = scene.findById(ed.selectedId)
        if (sel != null && sel.isActiveInHierarchy()) {
            r.obb(sel.world, 0.5f, 0.5f, 0xFFFF9F1C.toInt())
            r.flushLines(2f)
            drawGizmo(sel, ed, ppu)
        } else r.flushLines(2f)
    }

    private fun drawCollider(go: GameObject, c: Collider2D) {
        val w = go.world
        val cx = w.mapX(c.offsetX, c.offsetY)
        val cy = w.mapY(c.offsetX, c.offsetY)
        val color = if (c.isTrigger) 0xCC4FC3F7.toInt() else 0xCC66FF66.toInt()
        if (c.shape == 1) r.circleLines(cx, cy, c.radius * max(w.scaleX, w.scaleY), color)
        else {
            tmp.a = 1f; tmp.b = 0f; tmp.c = 0f; tmp.d = 1f; tmp.tx = cx; tmp.ty = cy
            r.obb(tmp, c.width * w.scaleX / 2, c.height * w.scaleY / 2, color)
        }
    }

    private fun drawGizmo(go: GameObject, ed: EditorState, ppu: Float) {
        val w = go.world
        val x = w.tx
        val y = w.ty
        val len = ed.gizmoLength()
        val hs = 14f / ppu * (ed.view.heightPx / 1080f).coerceAtLeast(0.6f)
        val red = if (ed.activeAxis == 1) 0xFFFFFF66.toInt() else 0xFFFF4D4D.toInt()
        val green = if (ed.activeAxis == 2) 0xFFFFFF66.toInt() else 0xFF5CE65C.toInt()
        val free = if (ed.activeAxis == 3) 0xFFFFFF66.toInt() else 0xCCFFD24D.toInt()
        when (ed.tool) {
            Tool.MOVE -> {
                r.line(x, y, x + len, y, red); r.line(x, y, x, y + len, green)
                r.flushLines(5f)
                val rot = Affine().setTRS(x + len + hs * 0.6f, y, -90f, hs * 1.8f, hs * 1.8f)
                r.quad(rot, red, 2, null, hs * 1.8f * ppu)
                val rot2 = Affine().setTRS(x, y + len + hs * 0.6f, 0f, hs * 1.8f, hs * 1.8f)
                r.quad(rot2, green, 2, null, hs * 1.8f * ppu)
                r.rect(x + len * 0.18f, y + len * 0.18f, hs * 1.6f, hs * 1.6f, free, 0, ppu)
            }
            Tool.ROTATE -> {
                r.circleLines(x, y, len, if (ed.activeAxis != 0) 0xFFFFFF66.toInt() else 0xFF4DA6FF.toInt(), 64)
                val a = Math.toRadians(go.rotation.toDouble())
                r.line(x, y, x + (Math.cos(a) * len).toFloat(), y + (Math.sin(a) * len).toFloat(), 0xFF4DA6FF.toInt())
                r.flushLines(4f)
                r.rect(x, y, hs, hs, 0xFF4DA6FF.toInt(), 1, ppu)
            }
            Tool.SCALE -> {
                r.line(x, y, x + len, y, red); r.line(x, y, x, y + len, green)
                r.flushLines(5f)
                r.rect(x + len, y, hs * 1.6f, hs * 1.6f, red, 0, ppu)
                r.rect(x, y + len, hs * 1.6f, hs * 1.6f, green, 0, ppu)
                r.rect(x, y, hs * 1.8f, hs * 1.8f, free, 0, ppu)
            }
            Tool.HAND -> {}
        }
    }

    companion object {
        val POST_CUSTOM = com.sengine.engine.core.ComponentRegistry.POST_FX.size - 1
        /** Ambient light map value used when a scene has 2D lights but no ambient light object. */
        const val LIGHT_AMBIENT = 0xFF2A2E38.toInt()
    }
}
