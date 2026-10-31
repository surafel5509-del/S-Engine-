package com.sengine

import com.sengine.engine.Engine
import com.sengine.engine.anim.*
import com.sengine.engine.core.*
import com.sengine.engine.math.Mat4
import com.sengine.engine.render.CpuSkinning
import com.sengine.engine.render.ModelAsset
import com.sengine.engine.render.ModelStore
import com.sengine.engine.render.gles2.Gles2Shaders
import com.sengine.engine.render.gles3.Gles3Shaders
import com.sengine.engine.script.ScriptDiagnostics
import com.sengine.engine.script.ScriptParameters
import com.sengine.project.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64

class StudioSystemsTest {
    private fun project():Project=Project(File(Files.createTempDirectory("studio-test").toFile(),"Game")).also{it.saveMeta()}
    private fun errors(e:Engine):MutableList<String>{val result=ArrayList<String>();e.listeners.add(object:Engine.Listener{override fun onLog(level:Int,message:String){if(level>=2)result+=message}});return result}
    private fun frames(e:Engine,n:Int)=repeat(n){synchronized(e.lock){e.tick(1f/60f)}}
    private fun script(p:Project,source:String):Engine{p.writeAsset("Behavior.js",source);val s=Scene("Main");s.gravityY=0f;s.create("Actor").add(ScriptComponent().also{it.script="Behavior.js"});p.saveScene(s);return Engine(p,s,audioEnabled=false)}

    @Test fun catalogContainsOnlyNewStartersAndEmptyProjectsHaveNoObjects(){
        assertEquals(setOf("empty-2d","empty-3d","neon-relay","orbit-salvage","sentinel-protocol","studio-lab"),Templates.all.map{it.id}.toSet())
        for(id in listOf("empty-2d","empty-3d")){val p=project();Templates.byId(id)!!.build(p);p.dimension=id.drop(6);p.templateId=id;p.saveMeta();assertTrue(p.loadScene(p.startScene).objects.isEmpty());assertEquals(id.drop(6),Project(p.dir).dimension);assertEquals(id,Project(p.dir).templateId);assertTrue(p.listAssets().isEmpty())}
    }
    @Test fun everyNewStarterCompilesAndRunsInRealEngine(){
        for(t in Templates.all.filter{it.id !in setOf("empty-2d","empty-3d")}){
            val p=project();t.build(p);p.saveMeta()
            for(name in p.listAssets(AssetKind.SCRIPT))assertTrue("$name in ${t.id}",ScriptDiagnostics.validate(name,p.readAsset(name)!!).ok)
            val e=Engine(p,p.loadScene(p.startScene),audioEnabled=false)
            val errors=errors(e);e.gameView.widthPx=1280;e.gameView.heightPx=720
            e.play();frames(e,120)
            assertTrue("${t.id}: $errors",errors.isEmpty())
            if(t.id=="sentinel-protocol"){
                val a=requireNotNull(e.scene.find("Player")).getAny<SkeletalAnimator>()!!
                assertNotNull(a.pose);assertTrue(a.palette.isNotEmpty())
            }
            e.stop();frames(e,1);e.release()
        }
    }
    @Test fun empty3DStartsAndAcceptsAModel(){val p=project();Templates.byId("empty-3d")!!.build(p);val e=Engine(p,p.loadScene("Main"),audioEnabled=false);e.play();frames(e,2);val name=StudioAssets.installRobot(p);e.scene.create("Courier").add(MeshRenderer().also{it.model=name});frames(e,2);assertEquals(1,e.scene.objects.size);e.release()}
    @Test fun offlinePlannerSelectsTheActualEmptyAndStudioTemplates(){
        for((prompt,id,dimension) in listOf(Triple("empty 3d world","empty-3d",com.sengine.aibuilder.spec.GameDimension.THREE_D),Triple("empty canvas","empty-2d",com.sengine.aibuilder.spec.GameDimension.TWO_D),Triple("material animation lab","studio-lab",com.sengine.aibuilder.spec.GameDimension.THREE_D))){
            val spec=com.sengine.aibuilder.provider.DemoGamePlanner.plan(prompt,null)
            assertEquals(id,spec.templateId);assertEquals(dimension,spec.dimension);assertTrue(com.sengine.aibuilder.spec.GameSpecValidator.validate(spec).issues.toString(),com.sengine.aibuilder.spec.GameSpecValidator.validate(spec).valid)
        }
    }
    @Test fun gltfBaseColorConvertsLinearRgbToSrgbWithoutSwappingAlpha(){
        val p=project();val bin=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);listOf(0f,0f,0f,1f,0f,0f,0f,1f,0f).forEach{bin.putFloat(it)}
        val uri="data:application/octet-stream;base64,"+Base64.getEncoder().encodeToString(bin.array())
        val doc="""{"asset":{"version":"2.0"},"buffers":[{"uri":"$uri","byteLength":36}],"bufferViews":[{"buffer":0,"byteLength":36}],"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}],"materials":[{"pbrMetallicRoughness":{"baseColorFactor":[1,0,0,0.5]}}],"meshes":[{"primitives":[{"attributes":{"POSITION":0},"material":0}]}],"nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}"""
        val result=GltfImporter.import(p,"Color.gltf",doc.toByteArray());val material=p.materials().get(result.materials.single())!!
        assertEquals(0x80FF0000.toInt(),material.baseColor);assertTrue(material.isTransparent)
    }
    @Test fun atomicReplacementPreservesThePreviousDocumentOnFailure(){
        val p=project();p.writeAsset("Save.json","original");assertEquals("updated",Project(p.dir).let{it.writeAsset("Save.json","updated");it.readAsset("Save.json")})
        val directory=p.assetFile("Directory");assertTrue(directory.mkdirs())
        assertThrows(java.io.IOException::class.java){AtomicFiles.writeText(directory,"cannot replace a directory")}
        assertTrue(directory.isDirectory);assertEquals("updated",p.readAsset("Save.json"));assertFalse(p.assetsDir.listFiles()!!.any{it.name.endsWith(".tmp")})
    }
    @Test fun skeletalComponentAndProceduralOverridesSurviveSceneReload(){
        val p=project();val rig=StudioAssets.installRobot(p);val scene=Scene("Main");val go=scene.create("Robot")
        go.add(MeshRenderer().also{it.model=rig});go.add(SkeletalAnimator().also{it.model=rig;it.clip="Courier_Idle.skanim"})
        p.saveScene(scene);val e=Engine(p,p.loadScene("Main"),audioEnabled=false);val a=e.scene.find("Robot")!!.getAny<SkeletalAnimator>()!!
        e.play();frames(e,1);assertTrue(com.sengine.engine.script.SObject(e.scene.find("Robot")!!,e,e.scripts).setBoneRotation("Head",0.0,35.0,0.0))
        val before=a.pose!!.rotation[3].copyOf();frames(e,2);assertArrayEquals(before,a.pose!!.rotation[3],1e-5f)
        com.sengine.engine.script.SObject(e.scene.find("Robot")!!,e,e.scripts).clearBoneOverrides();frames(e,1);assertFalse(before.contentEquals(a.pose!!.rotation[3]));e.release()
    }
    @Test fun materialRoundTripsAllMapsAndClampsInvalidInputs(){
        val p=project();val m=Material("Alloy",metallic=.7f,roughness=.2f,normalMap="Normal.png",metallicRoughnessMap="ORM.png",occlusionMap="ORM.png",emissiveMap="Emission.png",alphaMode=2,doubleSided=true)
        p.materials().save("Alloy.mat",m);val loaded=p.materials().get("Alloy.mat")!!;assertEquals(m.toJson().toString(),loaded.toJson().toString());assertTrue(loaded.isTransparent)
        loaded.roughness=-2f;loaded.metallic=4f;loaded.normalized();assertEquals(.04f,loaded.roughness,0f);assertEquals(1f,loaded.metallic,0f)
        p.materials().preview("Alloy.mat",Material("Transient"));assertEquals("Transient",p.materials().get("Alloy.mat")!!.name);p.materials().preview("Alloy.mat",null);assertEquals("Alloy",p.materials().get("Alloy.mat")!!.name)
    }
    @Test fun generatedSurfaceMapsAreRealPngsAndRobotIsWeighted(){
        val p=project();val rig=StudioAssets.installRobot(p);assertEquals(listOf(137,80,78,71,13,10,26,10).map{it.toByte()},p.readAssetBytes("StudioBrick_Normal.png")!!.take(8))
        val model=ModelStore(p).get(rig)!!;val skeleton=model.skeleton!!;assertEquals(12,model.skeleton!!.joints.size);assertTrue(model.parts.all{it.skinned&&it.mesh.skin!=null});assertEquals(2,model.clips.size)
        for(name in model.clips){val clip=SkeletalClip.fromJson(JSONObject(p.readAsset(name)!!));val pose=clip.sample(skeleton,clip.duration*.5f);assertTrue(pose.rotation.any{it.size==4});val palette=skeleton.palette(pose);assertTrue(palette.all{it.isFinite()})}
    }
    @Test fun skeletonRestPaletteIsIdentityAndParentMotionMovesChild(){
        val s=Skeleton(listOf(Joint("Root"),Joint("Child",0,floatArrayOf(0f,2f,0f))))
        val pose=s.restPose();val palette=s.palette(pose);for(j in 0..1)for(k in 0..15)assertEquals(if(k%5==0)1f else 0f,palette[j*16+k],1e-5f)
        pose.translation[0][0]=3f;val world=s.worldMatrices(pose);assertEquals(3f,world[1][12],1e-5f);assertEquals(2f,world[1][13],1e-5f)
        val q=Quaternion.fromEuler(30f,70f,-40f);val euler=Quaternion.toEuler(q);val q2=Quaternion.fromEuler(euler[0],euler[1],euler[2]);val original=Quaternion.matrix(q);val restored=Quaternion.matrix(q2);for(k in 0..15)assertEquals(original[k],restored[k],1e-4f)
    }
    @Test fun clipInterpolationEventsAndCrossfadeUseRealPoses(){
        val p=project();val rig=StudioAssets.installRobot(p);val e=Engine(p,Scene("Main"),audioEnabled=false)
        val go=e.scene.create("Robot");go.add(MeshRenderer().also{it.model=rig});val a=go.add(SkeletalAnimator());a.model=rig;a.clip="Courier_Idle.skanim";a.playOnStart=false
        assertTrue(e.skeletalAnimation.update(go,a,0f,true));val first=a.pose!!.rotation[4].copyOf();a.request("Courier_Run.skanim",.2f);e.skeletalAnimation.update(go,a,.1f,true);assertEquals("Courier_Run.skanim",a.current);assertNotNull(a.fadePose);e.skeletalAnimation.update(go,a,.2f,true);assertNull(a.fadePose);assertFalse(first.contentEquals(a.pose!!.rotation[4]));e.release()
    }
    @Test fun cpuSkinningTransformsPositionsAndPreservesUnitNormals(){
        val p=project();val rig=StudioAssets.installRobot(p);val m=ModelStore(p).get(rig)!!;val skeleton=m.skeleton!!;val pose=skeleton.restPose();pose.translation[0][1]=2f;val source=m.parts[0].mesh;val target=com.sengine.engine.render.Mesh(FloatArray(source.vertexCount*8));CpuSkinning.apply(source,skeleton.palette(pose),target);assertEquals(source.vertexCount,target.vertexCount)
        for(i in 0 until target.vertexCount){assertEquals(source.buffer.get(i*8+1)+2f,target.buffer.get(i*8+1),1e-4f);val n=FloatArray(3){target.buffer.get(i*8+3+it)};assertEquals(1f,kotlin.math.sqrt(n.sumOf{(it*it).toDouble()}).toFloat(),1e-4f)}
    }
    @Test fun scriptScopesModulesTimersAndEventsAreIsolatedAndCleaned(){
        val p=project();p.writeAsset("Math.js","var instanceCounter=0;module.exports={value:42,increment:function(){return ++instanceCounter;}};")
        val e=script(p,"var math=require('./Math.js'),n=0;function score(){return n;}function start(){events.on('ping',function(v){n=v;});after(0,function(){n=math.value;});}function update(dt){}")
        val second=e.scene.objects[0].add(ScriptComponent().also{it.script="Behavior.js"})
        val errs=errors(e);e.play();frames(e,3);assertTrue(errs.toString(),errs.isEmpty());assertEquals(42.0,e.scripts.sendMessage(e.scene.objects[0],"score",null));e.scripts.events.emit("ping",7.0);assertEquals(7.0,e.scripts.sendMessage(e.scene.objects[0],"score",null));e.release();assertEquals(0,e.scripts.instanceCount)
    }
    @Test fun scriptContextIsOnlyExitedByItsOwnerThread(){
        val e=script(project(),"function start(){}function update(dt){}")
        var failure:Throwable?=null
        val ready=java.util.concurrent.CountDownLatch(1);val stop=java.util.concurrent.CountDownLatch(1)
        val worker=Thread{try{e.scripts.begin();ready.countDown();stop.await();e.scripts.end()}catch(t:Throwable){failure=t;ready.countDown()}}
        worker.start();assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));assertNull(failure);assertTrue(e.scripts.isRunning)
        e.scripts.end();assertTrue("UI-thread cleanup must not exit a GL-thread context",e.scripts.isRunning)
        stop.countDown();worker.join(5000);assertNull(failure);assertFalse(e.scripts.isRunning);assertEquals(0,e.scripts.instanceCount)
    }
    @Test fun cancelledTimersAndRemovedEventsDoNotRun(){
        val p=project();val e=script(p,"var n=0,id=0,eventId=0;function start(){id=after(.01,function(){n=100;});cancelTimer(id);eventId=events.on('ping',function(){n=200;});events.off(eventId);}function score(){return n;}function update(dt){}")
        val errs=errors(e);e.play();frames(e,3);e.scripts.events.emit("ping",null);assertTrue(errs.toString(),errs.isEmpty());assertEquals(0.0,e.scripts.sendMessage(e.scene.objects[0],"score",null));e.release()
    }
    @Test fun infiniteScriptIsDisabledWithoutStoppingEngine(){
        val e=script(project(),"var n=0;function start(){}function update(dt){while(true){n++;}}");val errs=errors(e);e.maxCallbackMsForTest(10);e.play();frames(e,3);assertTrue(errs.any{it.contains("exceeded")});assertEquals(1,e.scripts.diagnostics.count{it["failed"]==true});e.release()
    }
    private fun Engine.maxCallbackMsForTest(ms:Long){scripts.maxCallbackMs=ms}
    @Test fun hostAccessIsBlockedAndSyntaxDiagnosticsDoNotExecute(){
        val e=script(project(),"function start(){var f=Java.type('java.io.File');}");val errs=errors(e);e.play();frames(e,2);assertTrue(errs.isNotEmpty());e.release()
        val report=ScriptDiagnostics.validate("Bad.js","function start(){");assertFalse(report.ok);assertTrue(report.issues.first().line>=1)
        assertEquals("a,b",ScriptParameters.parse("label=\"a,b\",speed=4")["label"]);assertEquals(4.0,ScriptParameters.parse("label=\"a,b\",speed=4")["speed"])
    }
    @Test fun shaderSourcesHaveSkinningOnlyWhereSupportedAndNoDuplicateTangents(){
        assertFalse(Gles2Shaders.supportsGpuSkin);assertFalse(Gles2Shaders.meshVs.contains("uBones"));assertTrue(Gles3Shaders.supportsGpuSkin);assertTrue(Gles3Shaders.skinnedMeshVs.contains("uBones[32]"))
        for(fs in listOf(Gles2Shaders.meshFs("vec4 effect(vec4 c,vec2 uv){return c;}"),Gles3Shaders.meshFs("vec4 effect(vec4 c,vec2 uv){return c;}"))){assertTrue(fs.contains("uNormalMap"));assertTrue(fs.contains("uUVTransform"));assertEquals(1,Regex("(?:varying|in) vec3 vTangent;").findAll(fs).count());assertEquals(0,Regex("base\\.rgb=pow").findAll(fs).count())}
    }
    @Test fun glbImportsIndexedGeometryAndRejectsBadResourceWithoutWrites(){
        val p=project();val bin=ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
        listOf(0f,0f,0f,1f,0f,0f,0f,1f,0f).forEach{bin.putFloat(it)};listOf(0,1,2).forEach{bin.putShort(it.toShort())};
        val uri="data:application/octet-stream;base64,"+Base64.getEncoder().encodeToString(bin.array())
        val doc="""{"asset":{"version":"2.0"},"buffers":[{"uri":"$uri","byteLength":42}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}"""
        val glb=glb(doc,ByteArray(0));val result=GltfImporter.import(p,"Triangle.glb",glb);assertEquals(3,result.vertices);assertEquals(3,ModelStore(p).get(result.model)!!.parts[0].mesh.vertexCount)
        val before=p.listAssets();assertThrows(IllegalArgumentException::class.java){GltfImporter.import(p,"Bad.gltf",doc.replace(uri,"../secret.bin").toByteArray())};assertEquals(before,p.listAssets())
    }
    private fun glb(json:String,bin:ByteArray):ByteArray{val j=json.toByteArray();val jp=(j.size+3)/4*4;val bp=(bin.size+3)/4*4;val b=ByteBuffer.allocate(12+8+jp+if(bin.isEmpty())0 else 8+bp).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0x46546c67);b.putInt(2);b.putInt(b.capacity());b.putInt(jp);b.putInt(0x4e4f534a);b.put(j);repeat(jp-j.size){b.put(32)};if(bin.isNotEmpty()){b.putInt(bp);b.putInt(0x004e4942);b.put(bin)};return b.array()}
}
