package com.sengine.ui

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.engine.Engine
import com.sengine.engine.anim.*
import com.sengine.engine.core.*
import com.sengine.engine.render.EditorState
import com.sengine.engine.render.ModelAsset
import com.sengine.engine.render.SceneRenderer
import com.sengine.project.ProjectManager

/** Timeline, joint posing and persistent keyframes, using the same sampler/skinning as the player. */
class SkeletalEditorActivity:AppCompatActivity() {
    private lateinit var project:com.sengine.project.Project
    private lateinit var modelAsset:String
    private lateinit var clipAsset:String
    private lateinit var model:ModelAsset
    private lateinit var skeleton:Skeleton
    private lateinit var engine:Engine
    private lateinit var animator:SkeletalAnimator
    private lateinit var gl:GLSurfaceView
    private lateinit var timeline:SeekBar
    private lateinit var timeLabel:TextView
    private lateinit var jointLabel:TextView
    private lateinit var clipLabel:TextView
    private lateinit var eventList:LinearLayout
    private lateinit var rotationFields:List<EditText>
    private val tracks=LinkedHashMap<Pair<Int,String>,SortedMap<Float,FloatArray>>()
    private val events=ArrayList<AnimationEvent>()
    private var duration=2f
    private var joint=0
    private var playing=false
    private var dirty=false
    private var scrubbing=false
    private var poseEditing=false
    private val handler=Handler(Looper.getMainLooper())
    private val state=EditorState().also{it.mode3D=true;it.showGrid=false;it.distance=4.5f;it.orbitY=1f;it.pitch=12f}
    private val refresh=object:Runnable{override fun run(){
        if(::animator.isInitialized){synchronized(engine.lock){timeline.progress=(animator.time/duration*1000).toInt().coerceIn(0,1000);timeLabel.text="%.2f / %.2f s".format(animator.time,duration)};handler.postDelayed(this,50)}
    }}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        project=ProjectManager.open(this,intent.getStringExtra("project")?:error("Project required"))
        modelAsset=intent.getStringExtra("model")?:""
        clipAsset=intent.getStringExtra("asset")?:""
        val loaded=project.listAssets(AssetKind.MODEL).mapNotNull{it to project.let{p->com.sengine.engine.render.ModelStore(p).get(it)}}.firstOrNull{(name,m)->(modelAsset.isBlank()||name==modelAsset)&&m?.skeleton!=null}
        if(loaded==null){Toast.makeText(this,"Import a glTF/GLB with a skin first. Static OBJ models do not contain skeletons.",Toast.LENGTH_LONG).show();finish();return}
        modelAsset=loaded.first;model=loaded.second!!;skeleton=model.skeleton!!
        val clip=project.readAsset(clipAsset)?.let{try{SkeletalClip.fromJson(org.json.JSONObject(it))}catch(_:Exception){null}}
        if(clipAsset.isNotBlank()&&clip==null){Toast.makeText(this,"Could not read skeletal clip",Toast.LENGTH_LONG).show();finish();return}
        duration=clip?.duration?:2f
        clip?.tracks?.forEach{t->tracks.getOrPut(t.joint to t.path){sortedMapOf()}.also{m->t.times.forEachIndexed{i,k->m[k]=t.values.copyOfRange(i*(if(t.path=="rotation")4 else 3),(i+1)*(if(t.path=="rotation")4 else 3))}}}
        clip?.events?.let(events::addAll)
        val scene=Scene("Animation Preview").also{it.ambient=0xFF718092.toInt()}
        scene.create("Rig").also{it.add(MeshRenderer().also{m->m.mesh=8;m.model=modelAsset});animator=it.add(SkeletalAnimator().also{a->a.model=modelAsset;a.clip=clipAsset;a.playOnStart=false;a.previewInEditor=true;a.speed=0f})}
        scene.create("Key Light").also{it.rotX=40f;it.rotY=-35f;it.add(Light().also{it.intensity=1.4f})}
        engine=Engine(project,scene,audioEnabled=false,scriptsEnabled=false)
        val root=vbox().apply{setBackgroundColor(C.BG)}
        val bar=hbox().apply{setBackgroundColor(C.HEADER);setPadding(dp(12),dp(7),dp(12),dp(7))}
        bar.addView(label("SKELETAL ANIMATION",16f,C.TEXT,true),lp(0,WRAP,1f))
        bar.addView(button("New clip"){newClip()});bar.addView(button("Save clip",C.ACCENT,0xFFFFFFFF.toInt()){save()})
        root.addView(bar,lp(MATCH,WRAP))
        val body=hbox();gl=GLSurfaceView(this).apply{setEGLContextClientVersion(2);setRenderer(SceneRenderer(engine,state));renderMode=GLSurfaceView.RENDERMODE_CONTINUOUSLY}
        var x=0f;var y=0f
        gl.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{x=e.x;y=e.y};MotionEvent.ACTION_MOVE->{state.yaw+=(e.x-x)*.3f;state.pitch=(state.pitch+(e.y-y)*.25f).coerceIn(-60f,60f);x=e.x;y=e.y};else->{}};true}
        body.addView(gl,lp(0,MATCH,1.2f))
        val panel=vbox().apply{setPadding(dp(16),dp(10),dp(16),dp(20))}
        panel.addView(label(modelAsset,14f,0xFF6BE7D0.toInt(),true));clipLabel=label(clipAsset.ifBlank{"Unsaved clip"},12f,C.DIM);panel.addView(clipLabel)
        panel.addView(label("${skeleton.joints.size} joints · ${model.parts.sumOf{it.mesh.vertexCount}} vertices · ${if(skeleton.joints.size<=32)"GPU skinning on ES 3 / CPU fallback on ES 2" else "CPU skinning (large rig)"}",12f,C.DIM))
        val clipRow=hbox();clipRow.addView(button("Open clip"){openClip()});clipRow.addView(button("Generate starter rig"){installRig()});panel.addView(clipRow)
        jointLabel=label("",14f,C.TEXT,true);panel.addView(jointLabel)
        val joints=hbox();joints.addView(button("Previous joint"){selectJoint((joint-1+skeleton.joints.size)%skeleton.joints.size)});joints.addView(button("Next joint"){selectJoint((joint+1)%skeleton.joints.size)});panel.addView(joints)
        panel.addView(label("LOCAL ROTATION / EULER DEGREES",11f,C.DIM,true).apply{setPadding(0,dp(12),0,dp(4))})
        rotationFields=(0..2).map{axis->field("0",true).also{f->panel.addView(label(listOf("X","Y","Z")[axis],11f,C.DIM));panel.addView(f,lp(MATCH,WRAP));f.setOnFocusChangeListener{_,focus->if(!focus)keyRotation()}}}
        val actions=hbox();actions.addView(button("Key rotation",C.ACCENT,0xFFFFFFFF.toInt()){keyRotation()});actions.addView(button("Key position"){keyPosition()});panel.addView(actions)
        panel.addView(label("Rotation and position keys are inserted at the playhead. Quaternion interpolation is used at runtime. This is a clip/joint editor, not a mesh-weight painting tool.",12f,C.DIM).apply{setPadding(0,dp(8),0,dp(12))})
        panel.addView(label("ANIMATION EVENTS",12f,C.DIM,true));eventList=vbox();panel.addView(eventList)
        val en=field("Footstep");panel.addView(en);panel.addView(button("Add event at playhead"){val n=en.text.toString().trim();if(n.isNotBlank()&&n.length<=64){events.add(AnimationEvent(currentTime(),n));events.sortBy{it.time};changed();refreshEvents()}else toast("Enter an event name (1–64 characters)")})
        body.addView(android.widget.ScrollView(this).apply{addView(panel)},lp(0,MATCH,1f));root.addView(body,lp(MATCH,0,1f))
        val transport=hbox().apply{setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundColor(C.PANEL)}
        val play=button("▶ Play"){playing=!playing;synchronized(engine.lock){animator.speed=if(playing)1f else 0f};(it as TextView).text=if(playing)"Ⅱ Pause" else "▶ Play"}
        transport.addView(play);timeLabel=label("",12f,C.DIM).apply{minWidth=dp(105);gravity=android.view.Gravity.CENTER};transport.addView(timeLabel)
        timeline=SeekBar(this).apply{max=1000;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user){scrubbing=true;synchronized(engine.lock){animator.time=p/1000f*duration;animator.speed=0f};playing=false;play.text="▶ Play";timeLabel.text="%.2f / %.2f s".format(p/1000f*duration,duration);loadJointFields()}}override fun onStartTrackingTouch(s:SeekBar?){scrubbing=true}override fun onStopTrackingTouch(s:SeekBar?){scrubbing=false}})}
        transport.addView(timeline,lp(0,WRAP,1f));root.addView(transport,lp(MATCH,WRAP));setContentView(root)
        selectJoint(0);refreshEvents();previewDraft()
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    private fun currentTime()=synchronized(engine.lock){animator.time}.coerceIn(0f,duration)
    private fun selectJoint(index:Int){joint=index;jointLabel.text=skeleton.joints[joint].name+"  ·  parent: "+skeleton.joints[joint].parent.takeIf{it>=0}?.let{skeleton.joints[it].name}.orEmpty();loadJointFields()}
    private fun loadJointFields(){if(!::rotationFields.isInitialized||poseEditing)return;poseEditing=true;val pose=synchronized(engine.lock){animator.pose};val q=pose?.rotation?.get(joint)?:skeleton.joints[joint].rotation;Quaternion.toEuler(q).forEachIndexed{i,v->rotationFields[i].setText("%.2f".format(v))};poseEditing=false}
    private fun keyRotation(){if(poseEditing)return;val values=rotationFields.map{it.text.toString().toFloatOrNull()};if(values.any{it==null||!it.isFinite()}){toast("Rotation values must be finite numbers");return};upsert(joint,"rotation",Quaternion.fromEuler(values[0]!!,values[1]!!,values[2]!!));changed()}
    private fun keyPosition(){val p=synchronized(engine.lock){animator.pose?.translation?.get(joint)?.copyOf()}?:return;val box=vbox().apply{setPadding(dp(20),dp(8),dp(20),0)};val fields=p.map{field(it.toString(),true).also{f->box.addView(f)}};MaterialAlertDialogBuilder(this).setTitle("Key local position").setView(box).setPositiveButton("Insert"){_,_->val v=fields.map{it.text.toString().toFloatOrNull()};if(v.any{it==null||!it.isFinite()})toast("Invalid position")else{upsert(joint,"translation",FloatArray(3){v[it]!!});changed()}}.setNegativeButton("Cancel",null).show()}
    private fun upsert(j:Int,path:String,value:FloatArray){tracks.getOrPut(j to path){sortedMapOf()}[currentTime()]=value.copyOf();previewDraft();toast("Key inserted: ${skeleton.joints[j].name} / $path")}
    private fun draft():SkeletalClip?{val list=tracks.filterValues{it.isNotEmpty()}.map{entry->val key=entry.key;val keys=entry.value;AnimationTrack(key.first,key.second,keys.keys.toFloatArray(),FloatArray(keys.size*keys.values.first().size).also{values->keys.values.forEachIndexed{i,v->System.arraycopy(v,0,values,i*v.size,v.size)}}};return if(list.isEmpty())null else SkeletalClip(clipAsset.substringBeforeLast('.').ifBlank{"New Clip"},list,duration,events.toList())}
    private fun previewDraft(){synchronized(engine.lock){animator.editorClip=draft()}}
    private fun changed(){dirty=true;previewDraft()}
    private fun refreshEvents(){eventList.removeAllViews();events.forEachIndexed{i,e->eventList.addView(button("%.2f s  ·  ${e.name}  ×".format(e.time),C.PANEL2){events.removeAt(i);changed();refreshEvents()}.apply{textSize=12f},lp(MATCH,WRAP))}
    private fun save(){val c=draft();if(c==null){toast("Insert at least one key before saving");return};val f=field(clipAsset.substringBeforeLast('.').ifBlank{"NewClip"});MaterialAlertDialogBuilder(this).setTitle("Save skeletal clip (.skanim)").setView(f).setPositiveButton("Save"){_,_->
        val n=f.text.toString().replace(Regex("[^A-Za-z0-9_-]"),"_").take(80);if(n.isBlank()){toast("Invalid clip name");return@setPositiveButton}
        val name="$n.skanim";val write={try{engine.skeletalAnimation.saveClip(name,c);clipAsset=name;clipLabel.text=name;dirty=false;if(!model.clips.contains(name))project.writeAsset(modelAsset,model.copyWithClips(model.clips+name).toJson().toString());toast("Saved $name")}catch(e:Exception){toast(e.message?:"Save failed")}}
        if(project.assetFile(name).exists()&&name!=clipAsset)MaterialAlertDialogBuilder(this).setTitle("Replace existing clip?").setPositiveButton("Replace"){_,_->write()}.setNegativeButton("Cancel",null).show()else write()
    }.setNegativeButton("Cancel",null).show()}
    private fun openClip(){val names=project.listAssets(AssetKind.ANIMATION).filter{it.endsWith(".skanim")};if(names.isEmpty()){toast("No .skanim clips in this project");return};fun open(){MaterialAlertDialogBuilder(this).setItems(names.toTypedArray()){_,i->if(dirty)toast("Save or discard the current clip before opening another")else startActivity(intent.putExtra("asset",names[i]).putExtra("model",modelAsset)).also{finish()}}.show()};if(dirty)MaterialAlertDialogBuilder(this).setTitle("Discard unsaved keys?").setPositiveButton("Discard"){_,_->dirty=false;open()}.setNegativeButton("Cancel",null).show()else open()}
    private fun newClip(){fun create(){tracks.clear();events.clear();clipAsset="";duration=2f;dirty=true;clipLabel.text="Unsaved clip";synchronized(engine.lock){animator.clip="";animator.current="";animator.time=0f;animator.editorClip=null};refreshEvents();toast("New two-second timeline. Pose a joint and insert a key.")};if(dirty)MaterialAlertDialogBuilder(this).setTitle("Discard unsaved keys?").setPositiveButton("Discard"){_,_->create()}.setNegativeButton("Cancel",null).show()else create()}
    private fun installRig(){MaterialAlertDialogBuilder(this).setTitle("Add original courier rig?").setMessage("Adds a weighted robot model, PBR materials, idle/run clips and texture maps to this project. Existing assets are preserved.").setPositiveButton("Add"){_,_->com.sengine.project.StudioAssets.installRobot(project,"StudioCourier");toast("Courier rig added to Assets")}.setNegativeButton("Cancel",null).show()}
    override fun onResume(){super.onResume();if(::gl.isInitialized){gl.onResume();handler.post(refresh)}}
    override fun onPause(){handler.removeCallbacks(refresh);if(::gl.isInitialized)gl.onPause();super.onPause()}
    @Deprecated("Back dispatcher")override fun onBackPressed(){if(dirty)MaterialAlertDialogBuilder(this).setTitle("Unsaved animation").setPositiveButton("Discard"){_,_->finish()}.setNegativeButton("Cancel",null).show()else super.onBackPressed()}
}
