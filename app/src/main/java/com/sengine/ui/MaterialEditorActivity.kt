package com.sengine.ui

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sengine.engine.Engine
import com.sengine.engine.core.*
import com.sengine.engine.render.EditorState
import com.sengine.engine.render.SceneRenderer
import com.sengine.project.Material
import com.sengine.project.ProjectManager

/** Live metallic/roughness authoring. Preview overrides are transient and never written to a scene. */
class MaterialEditorActivity : AppCompatActivity() {
    private lateinit var project: com.sengine.project.Project
    private lateinit var asset: String
    private lateinit var draft: Material
    private lateinit var gl: GLSurfaceView
    private lateinit var engine: Engine
    private val state=EditorState().also{it.mode3D=true;it.showGrid=false;it.distance=3.4f;it.pitch=12f;it.yaw=25f}
    private var dirty=false
    private var targetId=-1L

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        project=ProjectManager.open(this,intent.getStringExtra("project")?:error("Project required"))
        asset=intent.getStringExtra("asset")?:error("Material required")
        targetId=intent.getLongExtra("object",-1L)
        draft=project.materials().get(asset)?.copyMaterial()?:Material(asset.substringBeforeLast('.'))
        val scene=Scene("Material Preview").also{it.ambient=0xFF68788B.toInt()}
        scene.create("Surface").also{it.add(MeshRenderer().also{m->m.mesh=1;m.material=asset})}
        scene.create("Key").also{it.rotX=35f;it.rotY=-35f;it.add(Light().also{l->l.intensity=1.5f})}
        scene.create("Rim").also{it.x=3f;it.y=1f;it.z=-2f;it.add(Light().also{l->l.kind=1;l.intensity=2f;l.range=8f;l.color=0xFF82BFFF.toInt()})}
        engine=Engine(project,scene,audioEnabled=false,scriptsEnabled=false)
        project.materials().preview(asset,draft)
        val root=vbox().apply{setBackgroundColor(C.BG)}
        val bar=hbox().apply{setBackgroundColor(C.HEADER);setPadding(dp(12),dp(8),dp(12),dp(8))}
        bar.addView(button("←"){onBackPressedDispatcher.onBackPressed()})
        bar.addView(label(asset,17f,C.TEXT,true),lp(0,WRAP,1f).margins(dp(12),0,dp(8),0))
        bar.addView(button("Save",C.ACCENT,0xFFFFFFFF.toInt()){save()})
        root.addView(bar,lp(MATCH,WRAP))
        val body=hbox()
        gl=GLSurfaceView(this).apply{setEGLContextClientVersion(2);setRenderer(SceneRenderer(engine,state));renderMode=GLSurfaceView.RENDERMODE_CONTINUOUSLY}
        var lastX=0f;var lastY=0f
        gl.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{lastX=e.x;lastY=e.y};MotionEvent.ACTION_MOVE->{state.yaw+=(e.x-lastX)*.35f;state.pitch=(state.pitch+(e.y-lastY)*.25f).coerceIn(-70f,70f);lastX=e.x;lastY=e.y};else->{}};true}
        body.addView(gl,lp(0,MATCH,1f))
        val controls=vbox().apply{setPadding(dp(16),dp(12),dp(16),dp(20))}
        controls.addView(label("SURFACE / METALLIC–ROUGHNESS",12f,0xFF6BE7D0.toInt(),true))
        controls.addView(label("Drag the sphere to orbit. Changes preview live; Save publishes the reusable .mat asset. Direct-light PBR, not an Unreal node-graph equivalent.",12f,C.DIM).apply{setPadding(0,dp(6),0,dp(12))})
        colorRow(controls,"Base color",{draft.baseColor},{draft.baseColor=it})
        slider(controls,"Metallic",0f,1f,{draft.metallic},{draft.metallic=it})
        slider(controls,"Roughness",.04f,1f,{draft.roughness},{draft.roughness=it})
        val maps=vbox();controls.addView(maps)
        fun rebuildMaps(){
            maps.removeAllViews();maps.addView(label("TEXTURE INPUTS",12f,C.DIM,true).apply{setPadding(0,dp(14),0,dp(5))})
            listOf(Triple("Base color · sRGB","baseColorMap",{v:String->draft.baseColorMap=v}),Triple("Normal · linear tangent space","normalMap",{v:String->draft.normalMap=v}),Triple("Roughness G / metallic B · linear","metallicRoughnessMap",{v:String->draft.metallicRoughnessMap=v}),Triple("Occlusion R · linear","occlusionMap",{v:String->draft.occlusionMap=v}),Triple("Emission · sRGB","emissiveMap",{v:String->draft.emissiveMap=v})).forEach{(title,key,set)->
                val current=when(key){"baseColorMap"->draft.baseColorMap;"normalMap"->draft.normalMap;"metallicRoughnessMap"->draft.metallicRoughnessMap;"occlusionMap"->draft.occlusionMap;else->draft.emissiveMap}
                maps.addView(button("$title\n${current.ifBlank{"None"}}",C.PANEL2){pickTexture(title,current){set(it);changed();rebuildMaps()}}.apply{gravity=android.view.Gravity.START;textSize=12f},lp(MATCH,WRAP).margins(0,dp(3),0,dp(3)))
            }
        }
        rebuildMaps()
        slider(controls,"Normal strength",0f,2f,{draft.normalStrength},{draft.normalStrength=it})
        slider(controls,"Occlusion strength",0f,1f,{draft.occlusionStrength},{draft.occlusionStrength=it})
        colorRow(controls,"Emission color",{draft.emissiveColor},{draft.emissiveColor=it})
        slider(controls,"Emission strength",0f,16f,{draft.emissiveStrength},{draft.emissiveStrength=it})
        controls.addView(label("UV TRANSFORM",12f,C.DIM,true).apply{setPadding(0,dp(14),0,dp(5))})
        slider(controls,"Tile U",.01f,16f,{draft.tilingX},{draft.tilingX=it});slider(controls,"Tile V",.01f,16f,{draft.tilingY},{draft.tilingY=it})
        slider(controls,"Offset U",-4f,4f,{draft.offsetX},{draft.offsetX=it});slider(controls,"Offset V",-4f,4f,{draft.offsetY},{draft.offsetY=it})
        choiceRow(controls,"Alpha mode",listOf("Opaque","Mask","Blend"),{draft.alphaMode},{draft.alphaMode=it})
        slider(controls,"Alpha cutoff",0f,1f,{draft.alphaCutoff},{draft.alphaCutoff=it})
        check(controls,"Double sided",draft.doubleSided){draft.doubleSided=it;changed()}
        check(controls,"Unlit",draft.unlit){draft.unlit=it;changed()}
        controls.addView(button("Import texture library / original brick maps"){com.sengine.project.StudioAssets.installSurfaceLibrary(project);Toast.makeText(this,"Surface library added",Toast.LENGTH_SHORT).show();rebuildMaps()})
        body.addView(android.widget.ScrollView(this).apply{addView(controls)},lp(dp(370),MATCH))
        root.addView(body,lp(MATCH,0,1f));setContentView(root)
    }
    private fun changed(){dirty=true;project.materials().preview(asset,draft.copyMaterial())}
    private fun save(){try{project.materials().save(asset,draft);dirty=false;Toast.makeText(this,"Saved $asset",Toast.LENGTH_SHORT).show()}catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}}
    private fun pickTexture(title:String,current:String,onPick:(String)->Unit){
        val names=listOf("None")+project.listAssets(AssetKind.TEXTURE)
        MaterialAlertDialogBuilder(this).setTitle(title).setItems(names.toTypedArray()){_,i->onPick(if(i==0)"" else names[i])}.setNegativeButton("Cancel",null).show()
    }
    private fun colorRow(parent:LinearLayout,title:String,get:()->Int,set:(Int)->Unit){
        val swatch=View(this).apply{background=round(get(),dp(5).toFloat())}
        val row=hbox().apply{setPadding(0,dp(8),0,dp(8));addView(label(title,13f,C.TEXT),lp(0,WRAP,1f));addView(swatch,lp(dp(42),dp(28)))}
        row.setOnClickListener{ColorPickerDialog.show(this,get()){set(it);swatch.background=round(it,dp(5).toFloat());changed()}};parent.addView(row)
    }
    private fun slider(parent:LinearLayout,title:String,min:Float,max:Float,get:()->Float,set:(Float)->Unit){
        val row=hbox();val value=label("%.2f".format(get()),12f,C.DIM);row.addView(label(title,13f,C.TEXT),lp(0,WRAP,1f));row.addView(value,lp(dp(52),WRAP));parent.addView(row)
        parent.addView(SeekBar(this).apply{this.max=1000;progress=((get()-min)/(max-min)*1000).toInt().coerceIn(0,1000);setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){if(user){set(min+(max-min)*p/1000f);value.text="%.2f".format(get());changed()}}override fun onStartTrackingTouch(s:SeekBar?){}override fun onStopTrackingTouch(s:SeekBar?){}})},lp(MATCH,WRAP))
    }
    private fun choiceRow(parent:LinearLayout,title:String,options:List<String>,get:()->Int,set:(Int)->Unit){val b=button("$title: ${options[get()]}",C.PANEL2){MaterialAlertDialogBuilder(this).setItems(options.toTypedArray()){_,i->set(i);b.text="$title: ${options[i]}";changed()}.show()};parent.addView(b,lp(MATCH,WRAP).margins(0,dp(6),0,dp(6)))}
    private fun check(parent:LinearLayout,title:String,value:Boolean,set:(Boolean)->Unit){parent.addView(CheckBox(this).apply{text=title;isChecked=value;setTextColor(C.TEXT);setOnCheckedChangeListener{_,v->set(v)}})}
    override fun onResume(){super.onResume();if(::gl.isInitialized)gl.onResume()}
    override fun onPause(){if(::gl.isInitialized)gl.onPause();super.onPause()}
    override fun onDestroy(){if(::project.isInitialized)project.materials().preview(asset,null);super.onDestroy()}
    @Deprecated("Back dispatcher") override fun onBackPressed(){if(dirty)MaterialAlertDialogBuilder(this).setTitle("Unsaved material").setPositiveButton("Save & exit"){_,_->save();finish()}.setNegativeButton("Discard"){_,_->finish()}.setNeutralButton("Cancel",null).show()else super.onBackPressed()}
}
