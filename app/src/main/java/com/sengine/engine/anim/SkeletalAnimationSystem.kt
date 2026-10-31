package com.sengine.engine.anim

import com.sengine.engine.core.GameObject
import com.sengine.engine.core.MeshRenderer
import com.sengine.engine.core.SkeletalAnimator
import com.sengine.engine.render.ModelStore
import com.sengine.project.AtomicFiles
import com.sengine.project.Project
import org.json.JSONObject

/** Sampling is independent of rendering. One pose and one matrix palette per animated object. */
class SkeletalAnimationSystem(private val project:Project,val models:ModelStore) {
    private val clips=HashMap<String,Pair<String,SkeletalClip?>>()
    private val targets=HashMap<Long,SkeletonPose>()
    private val logged=HashSet<String>()
    val errors get()=logged.toList()
    fun clip(name:String):SkeletalClip? {
        if(!name.endsWith(".skanim"))return null
        val f=project.assetFile(name);if(!f.isFile||f.length()>8*1024*1024)return null
        val stamp="${f.lastModified()}:${f.length()}"
        clips[name]?.let{if(it.first==stamp)return it.second}
        val value=try{SkeletalClip.fromJson(JSONObject(f.readText()))}catch(_:Exception){null}
        clips[name]=stamp to value;return value
    }
    fun update(go:GameObject,a:SkeletalAnimator,dt:Float,playing:Boolean):Boolean {
        if(!a.enabled)return false
        val modelName=a.model.ifBlank{go.getAny<MeshRenderer>()?.model.orEmpty()}
        val model=models.get(modelName)?:return false
        val skeleton=model.skeleton
        if(skeleton==null){if(logged.add(modelName))logged.add("$modelName: no skeleton");return false}
        val stamp="${modelName}:${model.parts.size}:${skeleton.joints.size}"
        if(a.lastStamp!=stamp||a.pose?.skeleton!==skeleton){
            a.lastStamp=stamp;a.pose=SkeletonPose(skeleton);a.palette=FloatArray(skeleton.joints.size*16)
            a.current=a.clip.ifBlank{model.clips.firstOrNull().orEmpty()};a.playing=a.playOnStart;a.time=0f;a.finished=false
            targets[go.id]=SkeletonPose(skeleton);a.fadePose=null
        }
        a.requestedClip?.let{request->
            a.requestedClip=null
            if(clip(request)!=null){
                a.fadePose=a.pose?.let{source->SkeletonPose(skeleton).also{it.copyFrom(source)}}
                a.fadeDuration=a.requestedFade;a.fadeElapsed=0f;a.current=request;a.time=0f;a.playing=true;a.finished=false
            }else if(logged.add("$modelName:$request"))projectLog("$modelName: missing or invalid clip '$request'")
        }
        val c=if(!playing) a.editorClip ?: clip(a.current) else clip(a.current)
        a.activeClip=c
        val pose=a.pose!!;val target=targets.getOrPut(go.id){SkeletonPose(skeleton)}
        if(c!=null){
            val advance=playing&&a.playing||( !playing&&a.previewInEditor )
            if(advance&&!a.finished){
                val previous=a.time;a.time+=dt*a.speed
                if(a.time<0f)a.time=if(a.loop)mod(a.time,c.duration) else 0f
                if(a.time>=c.duration){if(a.loop)a.time=mod(a.time,c.duration)else{a.time=c.duration;a.finished=true;a.playing=false}}
                if(playing)for(event in c.events)if(event.time>previous&&event.time<=a.time)onEvent(go,event.name)
            }
            try{c.sample(skeleton,a.time,target);pose.copyFrom(target)}catch(e:Exception){if(logged.add(a.current))projectLog("${a.current}: ${e.message}")}
            if(a.fadePose!=null){
                a.fadeElapsed+=dt
                val t=if(a.fadeDuration<=0f)1f else (a.fadeElapsed/a.fadeDuration).coerceIn(0f,1f)
                target.copyFrom(pose);pose.blend(a.fadePose!!,target,t)
                if(t>=1f)a.fadePose=null
            }
        }
        skeleton.palette(pose,a.palette)
        return true
    }
    var onEvent:(GameObject,String)->Unit={_,_->}
    var onLog:(String)->Unit={}
    private fun projectLog(message:String)=onLog(message)
    fun clear(){targets.clear();logged.clear()}
    fun saveClip(name:String,clip:SkeletalClip){require(name.endsWith(".skanim"));AtomicFiles.writeText(project.assetFile(name),clip.toJson().toString(2));clips.remove(name)}
    private fun mod(value:Float,duration:Float):Float=((value%duration)+duration)%duration
}
