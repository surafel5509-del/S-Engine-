package com.sengine.project

import com.sengine.engine.anim.*
import com.sengine.engine.core.Component
import com.sengine.engine.math.Mat4
import com.sengine.engine.render.Mesh
import com.sengine.engine.render.ModelAsset
import com.sengine.engine.render.ModelPart
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

/**
 * glTF 2.0 importer for triangle meshes, node hierarchies, PBR textures, one rigid skin per model,
 * and translation/rotation/scale animation. Unsupported required extensions fail explicitly.
 * No silent conversion to an unrelated primitive and no runtime dependency on a source URI.
 */
object GltfImporter {
    const val MAX_INPUT = 32L * 1024 * 1024
    class Result(val model: String, val materials: List<String>, val clips: List<String>, val textures: List<String>,
                val vertices: Int, val joints: Int, val warnings: List<String>) {
        fun summary() = "$model • $vertices vertices • $joints joints • ${clips.size} clips • ${materials.size} materials"
    }
    private class Input(val json: JSONObject, val binary: ByteArray?, val resolve: (String) -> ByteArray?)
    private fun arr(o: JSONObject, key: String) = o.optJSONArray(key) ?: JSONArray()

    fun inspect(bytes: ByteArray): JSONObject {
        require(bytes.size.toLong() <= MAX_INPUT) { "glTF input exceeds 32 MB" }
        return readInput(bytes) { null }.json
    }

    fun import(project: Project, fileName: String, bytes: ByteArray, external: (String) -> ByteArray? = { project.readAssetBytes(it) }): Result {
        require(fileName.endsWith(".gltf",true) || fileName.endsWith(".glb",true)) { "Choose a .glb or .gltf model" }
        val input = readInput(bytes,external)
        val doc = input.json
        require(doc.optJSONObject("asset")?.optString("version") == "2.0") { "Only glTF 2.0 is supported" }
        val required = arr(doc,"extensionsRequired").let { a -> List(a.length()) { a.getString(it) } }
        val supportedExtensions = setOf("KHR_materials_unlit","KHR_texture_transform","KHR_materials_emissive_strength")
        require(required.all { it in supportedExtensions }) { "Unsupported required glTF extension: ${required.filterNot { it in supportedExtensions }.joinToString()}" }
        val buffers = List(arr(doc,"buffers").length()) { i ->
            val b = arr(doc,"buffers").getJSONObject(i); val uri = b.optString("uri")
            val data = if (uri.isEmpty()) input.binary ?: error("Missing GLB binary chunk") else resource(input,uri)
            require(data.size >= b.getInt("byteLength")) { "Truncated glTF buffer $i" }
            data
        }
        val accessors = arr(doc,"accessors")
        fun accessor(index: Int): Accessor {
            require(index in 0 until accessors.length()) { "Invalid accessor $index" }
            val a = accessors.getJSONObject(index)
            require(!a.has("sparse")) { "Sparse glTF accessors are not supported yet; export dense accessors" }
            val size = when(a.getString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> error("Unsupported accessor type") }
            val type = a.getInt("componentType"); val count = a.getInt("count")
            require(count in 0..1_000_000 && type in setOf(5120,5121,5122,5123,5125,5126))
            val width = when(type) { 5120,5121 -> 1; 5122,5123 -> 2; else -> 4 }
            val viewIndex = a.optInt("bufferView",-1)
            require(viewIndex in 0 until arr(doc,"bufferViews").length()) { "Accessor has no buffer view" }
            val view = arr(doc,"bufferViews").getJSONObject(viewIndex)
            val buffer = buffers.getOrNull(view.getInt("buffer")) ?: error("Invalid buffer reference")
            val stride = view.optInt("byteStride",size*width)
            require(stride >= size*width) { "Invalid accessor stride" }
            val offset = view.optInt("byteOffset")+a.optInt("byteOffset")
            require(offset >= 0 && offset+(count-1).coerceAtLeast(0)*stride+size*width <= view.optInt("byteLength")) { "Accessor exceeds buffer view" }
            require(view.getInt("byteOffset")+offset-a.optInt("byteOffset",0)+0 <= buffer.size)
            return Accessor(ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN),offset,stride,count,size,type,a.optBoolean("normalized"))
        }
        fun values(index: Int): FloatArray = accessor(index).read()
        val nodes = arr(doc,"nodes")
        require(nodes.length() <= 4096) { "glTF has too many nodes" }
        val parents = IntArray(nodes.length()) { -1 }
        for (i in 0 until nodes.length()) for (c in arr(nodes.getJSONObject(i),"children").let { a -> List(a.length()) { a.getInt(it) } }) {
            require(c in 0 until nodes.length() && parents[c] == -1 && c != i) { "Invalid glTF node hierarchy" }
            parents[c]=i
        }
        val locals = Array(nodes.length()) { i ->
            val n=nodes.getJSONObject(i)
            if(n.has("matrix")) {
                val a=valuesOf(n.getJSONArray("matrix")); require(a.size==16); a
            } else Mat4.identity().also { m ->
                val t=if(n.has("translation")) valuesOf(n.getJSONArray("translation")) else floatArrayOf(0f,0f,0f)
                val r=if(n.has("rotation")) valuesOf(n.getJSONArray("rotation")) else floatArrayOf(0f,0f,0f,1f)
                val s=if(n.has("scale")) valuesOf(n.getJSONArray("scale")) else floatArrayOf(1f,1f,1f)
                require(t.size==3 && r.size==4 && s.size==3)
                Joint.compose(t,Quaternion.normalize(r),s)
            }
        }
        val worlds=Array(nodes.length()) { Mat4.identity() }
        val visiting=BooleanArray(nodes.length()); val visited=BooleanArray(nodes.length())
        fun world(i: Int): FloatArray {
            require(i in 0 until nodes.length()); check(!visiting[i]) { "Cycle in glTF hierarchy" }
            if(visited[i]) return worlds[i]
            visiting[i]=true
            if(parents[i]>=0) Mat4.mul(worlds[i],world(parents[i]),locals[i]) else System.arraycopy(locals[i],0,worlds[i],0,locals[i].size)
            visiting[i]=false; visited[i]=true; return worlds[i]
        }
        for(i in 0 until nodes.length()) world(i)
        val output=LinkedHashMap<String,ByteArray>(); val warnings=ArrayList<String>()
        val base=fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"),"_").ifBlank { "Model" }
        fun reserve(ext: String, suffix: String=""): String {
            var n="$base$suffix$ext"; var i=2
            while(project.assetFile(n).exists() || output.containsKey(n)) n="${base}${suffix}_${i++}$ext"
            output[n]=ByteArray(0); return n
        }
        val textures=arr(doc,"textures"); val images=arr(doc,"images")
        val textureNames=HashMap<Int,String>()
        fun texture(index: Int, smooth: Boolean = true): String {
            if(index<0) return ""
            textureNames[index]?.let { return it }
            require(index in 0 until textures.length()) { "Invalid texture reference" }
            val t=textures.getJSONObject(index); val source=t.getInt("source")
            require(source in 0 until images.length()) { "Invalid image reference" }
            require(!images.getJSONObject(source).optString("mimeType").contains("ktx")) { "KTX2 textures require a Basis transcoder; export PNG/JPEG textures" }
            val image=images.getJSONObject(source)
            val data=if(image.has("uri")) resource(input,image.getString("uri")) else {
                val v=arr(doc,"bufferViews").getJSONObject(image.getInt("bufferView"))
                val b=buffers[v.getInt("buffer")]; val off=v.optInt("byteOffset"); val len=v.getInt("byteLength")
                require(off>=0 && len>0 && off+len<=b.size); b.copyOfRange(off,off+len)
            }
            require(data.size<=16*1024*1024) { "Texture exceeds 16 MB" }
            val mime=image.optString("mimeType")
            val ext=when { data.take(8)==listOf(137,80,78,71,13,10,26,10).map { it.toByte() } -> ".png"; mime.contains("jpeg") || (data[0]==0xFF.toByte() && data[1]==0xD8.toByte()) -> ".jpg"; mime.contains("webp") || String(data.copyOfRange(8,12),Charsets.US_ASCII)=="WEBP" -> ".webp"; else -> error("Use PNG, JPEG or WebP textures") }
            val name=reserve(ext,"_texture${textureNames.size}")
            output[name]=data; textureNames[index]=name; return name
        }
        val materials=arr(doc,"materials"); val materialNames=HashMap<Int,String>()
        fun material(index: Int): String {
            if(index<0) return ""
            materialNames[index]?.let{return it}
            val m=materials.getJSONObject(index); val p=m.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
            val factor=p.optJSONArray("baseColorFactor")?.let(::valuesOf) ?: floatArrayOf(1f,1f,1f,1f)
            val color=Component.parseColor(Material.hex((factor.sliceArray(0..3).mapIndexed { i,v -> ((v.coerceIn(0f,1f)*255).toInt()) shl when(i){0->24;1->16;2->8;else->0} }.fold(0){a,b->a or b})))
            val out=Material(name=m.optString("name","Material $index"),baseColor=color,
                metallic=p.optDouble("metallicFactor",1.0).toFloat(),roughness=p.optDouble("roughnessFactor",1.0).toFloat(),
                baseColorMap=p.optJSONObject("baseColorTexture")?.let{texture(it.getInt("index"))} ?: "",
                normalMap=m.optJSONObject("normalTexture")?.let{texture(it.getInt("index"))} ?: "",
                normalStrength=m.optJSONObject("normalTexture")?.optDouble("scale",1.0)?.toFloat() ?: 1f,
                metallicRoughnessMap=p.optJSONObject("metallicRoughnessTexture")?.let{texture(it.getInt("index"))} ?: "",
                occlusionMap=m.optJSONObject("occlusionTexture")?.let{texture(it.getInt("index"))} ?: "",
                occlusionStrength=m.optJSONObject("occlusionTexture")?.optDouble("strength",1.0)?.toFloat() ?: 1f,
                emissiveMap=m.optJSONObject("emissiveTexture")?.let{texture(it.getInt("index"))} ?: "",
                alphaMode=when(m.optString("alphaMode","OPAQUE")){"BLEND"->2;"MASK"->1;else->0},
                alphaCutoff=m.optDouble("alphaCutoff",0.5).toFloat(),doubleSided=m.optBoolean("doubleSided"),
                unlit=m.optJSONObject("extensions")?.has("KHR_materials_unlit")==true)
            m.optJSONArray("emissiveFactor")?.let{valuesOf(it)}?.let{c->out.emissiveColor=0xFF000000.toInt() or (c[0].coerceIn(0f,1f)*255).toInt().shl(16) or (c[1].coerceIn(0f,1f)*255).toInt().shl(8) or (c[2].coerceIn(0f,1f)*255).toInt(); if(c.any{it>0f}) out.emissiveStrength=1f}
            m.optJSONObject("extensions")?.optJSONObject("KHR_materials_emissive_strength")?.let{out.emissiveStrength=it.optDouble("emissiveStrength",1.0).toFloat()}
            p.optJSONObject("baseColorTexture")?.optJSONObject("extensions")?.optJSONObject("KHR_texture_transform")?.let{
                out.offsetX=it.optJSONArray("offset")?.optDouble(0,0.0)?.toFloat() ?: 0f; out.offsetY=it.optJSONArray("offset")?.optDouble(1,0.0)?.toFloat() ?: 0f
                out.tilingX=it.optJSONArray("scale")?.optDouble(0,1.0)?.toFloat() ?: 1f; out.tilingY=it.optJSONArray("scale")?.optDouble(1,1.0)?.toFloat() ?: 1f
                if(it.optDouble("rotation",0.0)!=0.0) warnings+="Texture rotation is not supported; UV scale/offset was imported"
            }
            val name=reserve(".mat","_material${materialNames.size}"); output[name]=out.toJson().toString(2).toByteArray(); materialNames[index]=name; return name
        }
        var jointOrder=IntArray(0)
        val skins=arr(doc,"skins"); var skeleton: Skeleton?=null; var skinNodes: IntArray?=null
        fun skeletonFor(index: Int): Skeleton {
            val skin=skins.getJSONObject(index); val joints=arr(skin,"joints").let{a->IntArray(a.length()){a.getInt(it)}}
            require(joints.isNotEmpty() && joints.size<=Skeleton.MAX_JOINTS) { "Skin needs 1–128 joints" }
            val old=skinNodes
            if(old!=null) require(old.contentEquals(joints)) { "Use one shared skeleton per model in this version" }
            skeleton?.let{return it}
            val ib=values(skin.getInt("inverseBindMatrices")); require(ib.size==joints.size*16)
            val mapping=joints.withIndex().associate{(i,n)->n to i}
            // Sort topologically even if the exporter listed child joints before parents.
            val ordered=ArrayList<Int>(); fun add(n: Int) { if(n in ordered)return; val p=parents[n]; if(p in mapping && p!=n)add(p); ordered.add(n) }
            joints.forEach(::add); val remap=ordered.withIndex().associate{(i,n)->n to i}
            val list=ordered.mapIndexed{i,n->
                val p=parents[n]; val local=if(p in mapping) { val inv=Mat4.identity(); require(Mat4.invert(inv,world(p))); Mat4.mul(Mat4.identity(),inv,world(n)) } else world(n).clone()
                val t=floatArrayOf(local[12],local[13],local[14])
                val s=floatArrayOf(Mat4.scaleOf(local,0),Mat4.scaleOf(local,1),Mat4.scaleOf(local,2))
                val r=matrixRotation(local,s)
                Joint(nodes.getJSONObject(n).optString("name","Joint $n"),mapping[p] ?: -1,t,r,s,ib.copyOfRange(mapping[n]!!*16,mapping[n]!!*16+16))
            }
            // Channel targets use the original joint-node order; preserve that order in a lookup.
            skinNodes=joints
            skeleton=Skeleton(list).also { jointOrder=ordered.toIntArray() }
            return skeleton!!
        }
        val parts=ArrayList<ModelPart>(); var totalVertices=0
        val meshes=arr(doc,"meshes")
        fun meshNode(nodeIndex: Int) {
            val node=nodes.getJSONObject(nodeIndex); val meshIndex=node.optInt("mesh",-1)
            if(meshIndex>=0) {
                val mesh=meshes.getJSONObject(meshIndex); val skinIndex=node.optInt("skin",-1)
                val rig=if(skinIndex>=0)skeletonFor(skinIndex) else null
                val transform=world(nodeIndex); val normal=Mat4.identity().also{Mat4.invert(it,transform);Mat4.transpose(it,it)}
                for(pi in 0 until arr(mesh,"primitives").length()) {
                    val p=mesh.getJSONArray("primitives").getJSONObject(pi); require(p.optInt("mode",4)==4){"Only triangle primitives are supported"}
                    require(!p.has("targets")){"Morph targets are not supported yet; bake them before export"}
                    val attributes=p.getJSONObject("attributes"); val pos=values(attributes.getInt("POSITION"))
                    require(pos.size%3==0 && pos.isNotEmpty()); val count=pos.size/3; totalVertices+=count
                    require(totalVertices<=250_000){"Model exceeds 250,000 vertices"}
                    val nrm=attributes.optInt("NORMAL",-1).takeIf{it>=0}?.let(::values) ?: FloatArray(pos.size)
                    val uv=attributes.optInt("TEXCOORD_0",-1).takeIf{it>=0}?.let(::values) ?: FloatArray(count*2)
                    val tangent=attributes.optInt("TANGENT",-1).takeIf{it>=0}?.let(::values)
                    val joints=attributes.optInt("JOINTS_0",-1).takeIf{it>=0}?.let(::values)
                    val weights=attributes.optInt("WEIGHTS_0",-1).takeIf{it>=0}?.let(::values)
                    if(rig!=null)require(joints!=null && weights!=null){"Skinned mesh lacks JOINTS_0 / WEIGHTS_0"}
                    require(nrm.size==pos.size && uv.size==count*2 && (tangent==null || tangent.size==count*4))
                    val data=FloatArray(count*8); val skin=if(rig!=null)FloatArray(count*8) else null
                    for(v in 0 until count) {
                        val o=v*3; val point=if(rig!=null)pos.copyOfRange(o,o+3) else Mat4.point(transform,pos[o],pos[o+1],pos[o+2])
                        val n=if(nrm.any{it!=0f})normalize(if(rig!=null)nrm.copyOfRange(o,o+3) else Mat4.dir(normal,nrm[o],nrm[o+1],nrm[o+2])) else floatArrayOf(0f,1f,0f)
                        data.set(v*8,point[0]);data[v*8+1]=point[1];data[v*8+2]=point[2];data[v*8+3]=n[0];data[v*8+4]=n[1];data[v*8+5]=n[2]
                        data[v*8+6]=uv[v*2];data[v*8+7]=uv[v*2+1]
                        if(skin!=null){var sum=0f;for(k in 0..3)sum+=weights!![v*4+k].coerceAtLeast(0f);if(sum<=0f){weights!![v*4]=1f;sum=1f}
                            for(k in 0..3){skin[v*8+k]=joints!![v*4+k];skin[v*8+4+k]=weights!![v*4+k].coerceAtLeast(0f)/sum}}
                    }
                    if(nrm.none{it!=0f})for(o in data.indices step 24){val n=faceNormal(data,o);for(k in 0..2){data[o+k*8+3]=n[0];data[o+k*8+4]=n[1];data[o+k*8+5]=n[2]}}
                    val indices=p.optInt("indices",-1).takeIf{it>=0}?.let(::accessor)
                    val expanded=indices?.let{a->
                        val ids=a.read();require(ids.size%3==0);val d=FloatArray(ids.size*8);val s=skin?.let{FloatArray(ids.size*8)};val t=tangent?.let{FloatArray(ids.size*4)}
                        ids.forEachIndexed{j,id->val i=id.toInt();require(i in 0 until count);System.arraycopy(data,i*8,d,j*8,8);if(s!=null)System.arraycopy(skin!!,i*8,s,j*8,8);if(t!=null)System.arraycopy(tangent!!,i*4,t,j*4,4)}
                        Triple(d,s,t)
                    } ?: Triple(data,skin,tangent)
                    parts.add(ModelPart(Mesh(expanded.first,expanded.second,expanded.third),material(p.optInt("material",-1)),rig!=null,mesh.optString("name","Part $pi")))
                }
            }
            val children=arr(node,"children");for(i in 0 until children.length())meshNode(children.getInt(i))
        }
        val sceneIndex=doc.optInt("scene",0);val scene=arr(doc,"scenes").getJSONObject(sceneIndex);val roots=arr(scene,"nodes")
        for(i in 0 until roots.length())meshNode(roots.getInt(i))
        require(parts.isNotEmpty()){"glTF scene has no triangle meshes"}
        val clips=ArrayList<String>()
        val animations=arr(doc,"animations")
        for(i in 0 until animations.length()){
            val a=animations.getJSONObject(i);val samplers=arr(a,"samplers");val tracks=ArrayList<AnimationTrack>();var duration=0f
            require(skeleton!=null){"This importer supports skeletal animation; bake static node animation before export"}
            for(c in 0 until arr(a,"channels").length()){
                val channel=a.getJSONArray("channels").getJSONObject(c);val sampler=samplers.getJSONObject(channel.getInt("sampler"));val target=channel.getJSONObject("target")
                val node=target.getInt("node");val joint=jointOrder.indexOf(node);require(joint>=0){"Animation targets a non-skeletal node"}
                val path=target.getString("path");require(path in setOf("translation","rotation","scale")){"Weight morph animation is not supported"}
                val times=values(sampler.getInt("input"));val data=values(sampler.getInt("output"));val interpolation=sampler.optString("interpolation","LINEAR")
                require(interpolation in setOf("LINEAR","STEP")){"Cubic spline animation must be baked to linear keyframes on export"}
                require(times.isNotEmpty() && data.size == times.size * (if(path=="rotation") 4 else 3)){"Invalid animation sampler dimensions"}
                tracks.add(AnimationTrack(joint,path,times,data,interpolation));duration=maxOf(duration,times.last())
            }
            if(tracks.isNotEmpty()){val name=reserve(".skanim","_${a.optString("name","Clip$i").replace(Regex("[^A-Za-z0-9_-]"),"_")}");val clip=SkeletalClip(a.optString("name","Clip $i"),tracks,duration.coerceAtLeast(1f/60f));output[name]=clip.toJson().toString().toByteArray();clips.add(name)}
        }
        val modelName=reserve(".smodel");val model=ModelAsset(base,parts,skeleton,clips)
        require(model.toJson().toString().toByteArray().size<=ModelAsset.MAX_BYTES){"Converted model exceeds mobile asset limit"}
        output[modelName]=model.toJson().toString().toByteArray()
        // Parse is complete. All names are new, so a failed multi-file import can be rolled back without touching user assets.
        val written=ArrayList<String>()
        try{output.forEach{(name,data)->project.writeAssetBytes(name,data);written.add(name)}
            textureNames.values.forEach{name->project.textureSettings().set(name,TextureSettings().also{it.useSmooth();it.wrap=1;it.maxSize=2048})}
            project.saveTextureSettings()
        }catch(e:Exception){written.forEach{project.assetFile(it).delete()};throw e}
        if(doc.optJSONObject("asset")?.optString("generator")?.contains("Blender")==true)warnings+="Blender export: +Y up / right-handed glTF coordinates are preserved"
        return Result(modelName,materialNames.values.toList(),clips,textureNames.values.toList(),totalVertices,skeleton?.joints?.size ?: 0,warnings)
    }

    private class Accessor(val buffer:ByteBuffer,val offset:Int,val stride:Int,val count:Int,val size:Int,val type:Int,val normalized:Boolean){
        fun read()=FloatArray(count*size){i->
            val at=offset+(i/size)*stride+(i%size)*when(type){5120,5121->1;5122,5123->2;else->4}
            require(at+4<=buffer.capacity())
            val v:Float=when(type){5120->buffer.get(at).toFloat();5121->buffer.get(at).toInt().and(255).toFloat();5122->buffer.getShort(at).toFloat();5123->buffer.getShort(at).toInt().and(65535).toFloat();5125->buffer.getInt(at).toLong().and(0xffffffffL).toFloat();else->buffer.getFloat(at)}
            val out=if(normalized)when(type){5121->v/255f;5123->v/65535f;5120->(v/127f).coerceAtLeast(-1f);5122->(v/32767f).coerceAtLeast(-1f);else->v}else v
            require(out.isFinite()){"Non-finite glTF accessor value"};out
        }
    }
    private fun valuesOf(a:JSONArray)=FloatArray(a.length()){a.getDouble(it).toFloat().also{v->require(v.isFinite())}}
    private fun normalize(v:FloatArray):FloatArray{val len=sqrt(v.fold(0f){s,v->s+v*v}).coerceAtLeast(1e-8f);return FloatArray(v.size){v[it]/len}}
    private fun faceNormal(d:FloatArray,o:Int):FloatArray{val ax=d[o+8]-d[o];val ay=d[o+9]-d[o+1];val az=d[o+10]-d[o+2];val bx=d[o+16]-d[o];val by=d[o+17]-d[o+1];val bz=d[o+18]-d[o+2];return normalize(floatArrayOf(ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx))}
    private fun matrixRotation(m: FloatArray, scale: FloatArray): FloatArray {
        val a=m[0]/scale[0]; val b=m[5]/scale[1]; val c=m[10]/scale[2]
        val trace=a+b+c
        val q=if(trace>0f){val k=sqrt(trace+1f)*2f;floatArrayOf((m[6]/scale[2]-m[9]/scale[1])/k,(m[8]/scale[0]-m[2]/scale[2])/k,(m[1]/scale[0]-m[4]/scale[1])/k,k/4f)}
        else if(a>b && a>c){val k=sqrt(1f+a-b-c)*2f;floatArrayOf(k/4f,(m[1]/scale[0]+m[4]/scale[1])/k,(m[8]/scale[0]+m[2]/scale[2])/k,(m[6]/scale[2]-m[9]/scale[1])/k)}
        else if(b>c){val k=sqrt(1f+b-a-c)*2f;floatArrayOf((m[1]/scale[0]+m[4]/scale[1])/k,k/4f,(m[6]/scale[2]+m[9]/scale[1])/k,(m[8]/scale[0]-m[2]/scale[2])/k)}
        else{val k=sqrt(1f+c-a-b)*2f;floatArrayOf((m[8]/scale[0]+m[2]/scale[2])/k,(m[6]/scale[2]+m[9]/scale[1])/k,k/4f,(m[1]/scale[0]-m[4]/scale[1])/k)}
        return Quaternion.normalize(q)
    }
    private fun readInput(bytes:ByteArray,external:(String)->ByteArray?):Input{
        require(bytes.size>=12){"Model is empty or truncated"}
        if(bytes.size>=20 && String(bytes,0,4,Charsets.US_ASCII)=="glTF"){
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);require(b.getInt(4)==2);val length=b.getInt(8).toLong() and 0xffffffffL;require(length==bytes.size.toLong()){"GLB length mismatch"}
            var json:JSONObject?=null;var bin:ByteArray?=null;var at=12
            while(at+8<=bytes.size){val len=b.getInt(at);val type=b.getInt(at+4);require(len>=0 && at+8+len<=bytes.size);val data=bytes.copyOfRange(at+8,at+8+len);if(type==0x4E4F534A)json=JSONObject(String(data,Charsets.UTF_8).trimEnd(' ','\u0000'));if(type==0x004E4942)bin=data;at+=8+len}
            return Input(json?:error("GLB has no JSON chunk"),bin,external)
        }
        return Input(JSONObject(String(bytes,Charsets.UTF_8)),null,external)
    }
    private fun resource(input:Input,uri:String):ByteArray{
        if(uri.startsWith("data:")){val comma=uri.indexOf(',');require(comma>0 && uri.substring(0,comma).endsWith(";base64"));return Base64.getDecoder().decode(uri.substring(comma+1))}
        require(!uri.contains("://") && !uri.startsWith("/") && !uri.split('/').contains("..")){"Only embedded or project-relative resources are allowed: $uri"}
        val clean=java.net.URLDecoder.decode(uri,"UTF-8")
        require(clean.split('/').none{it==".."||it.isEmpty()})
        return input.resolve(clean)?:error("Missing model resource '$clean'. Import a self-contained GLB, or import its ZIP with the buffer and textures.")
    }
}
