package com.sengine.project

import com.sengine.engine.anim.*
import com.sengine.engine.math.Mat4
import com.sengine.engine.render.Mesh
import com.sengine.engine.render.ModelAsset
import com.sengine.engine.render.ModelPart
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.*

/** Original, deterministic starter content. No third-party art or Unreal assets are bundled. */
object StudioAssets {
    fun installSurfaceLibrary(p:Project) {
        val size=128
        val color=ByteArray(size*size*4);val normal=ByteArray(color.size);val mr=ByteArray(color.size)
        for(y in 0 until size)for(x in 0 until size){
            val row=y/32;val bx=(x+if(row%2==0)0 else 16)%32;val by=y%32
            val edge=bx<2||by<2;val grain=((x*73+y*151+x*y*7) % 23)-11
            val height=if(edge)0.15f else 0.7f+grain/180f
            val i=(y*size+x)*4
            val base=if(edge)floatArrayOf(.25f,.29f,.32f)else floatArrayOf(.56f,.25f,.16f)
            for(k in 0..2)color[i+k]=(base[k].coerceIn(0f,1f)*255+grain).toInt().coerceIn(0,255).toByte()
            color[i+3]=255.toByte()
            val dx=if(bx==2) .4f else if(bx==31)-.4f else grain/100f
            val dy=if(by==2) .4f else if(by==31)-.4f else grain/100f
            val len=sqrt(dx*dx+dy*dy+1)
            normal[i]=((-dx/len*.5f+.5f)*255).toInt().toByte();normal[i+1]=((-dy/len*.5f+.5f)*255).toInt().toByte();normal[i+2]=((1/len*.5f+.5f)*255).toInt().toByte();normal[i+3]=255.toByte()
            mr[i]=(height*255).toInt().toByte();mr[i+1]=(if(edge).95f else .72f).times(255).toInt().toByte();mr[i+2]=0;mr[i+3]=255.toByte()
        }
        mapOf("StudioBrick_BaseColor.png" to color,"StudioBrick_Normal.png" to normal,"StudioBrick_ORM.png" to mr).forEach{(name,pixels)->
            if(!p.assetFile(name).exists())p.writeAssetBytes(name,png(size,size,pixels))
            p.textureSettings().set(name,TextureSettings().also{it.useSmooth();it.wrap=1;it.maxSize=1024})
        }
        val brick=Material("Studio Brick",0xFFFFFFFF.toInt(),roughness=.8f,baseColorMap="StudioBrick_BaseColor.png",normalMap="StudioBrick_Normal.png",metallicRoughnessMap="StudioBrick_ORM.png",occlusionMap="StudioBrick_ORM.png",tilingX=2f,tilingY=2f)
        p.materials().save("StudioBrick.mat",brick)
        Material.presets().forEach{p.materials().save(it.name.replace(" ","")+".mat",it)}
        p.saveTextureSettings()
    }

    /** A real weighted mesh with a root/pelvis/spine/head/arms/legs skeleton, not a hierarchy of cubes. */
    fun installRobot(p:Project,prefix:String="Courier"):String {
        installSurfaceLibrary(p)
        val names=listOf("Root","Pelvis","Spine","Head","UpperArm_L","Forearm_L","UpperArm_R","Forearm_R","Thigh_L","Shin_L","Thigh_R","Shin_R")
        val parents=intArrayOf(-1,0,1,2,2,4,2,6,1,8,1,10)
        val positions=arrayOf(floatArrayOf(0f,0f,0f),floatArrayOf(0f,1f,0f),floatArrayOf(0f,1.45f,0f),floatArrayOf(0f,1.95f,0f),
            floatArrayOf(-.43f,1.6f,0f),floatArrayOf(-.68f,1.15f,0f),floatArrayOf(.43f,1.6f,0f),floatArrayOf(.68f,1.15f,0f),
            floatArrayOf(-.22f,.94f,0f),floatArrayOf(-.22f,.48f,0f),floatArrayOf(.22f,.94f,0f),floatArrayOf(.22f,.48f,0f))
        val inverse=Array(names.size){Mat4.identity().also{m->m[12]=-positions[it][0];m[13]=-positions[it][1];m[14]=-positions[it][2]}}
        val joints=names.mapIndexed{i,name->Joint(name,parents[i],if(parents[i]>=0)FloatArray(3){positions[i][it]-positions[parents[i]][it]}else positions[i].copyOf(),inverseBind=inverse[i])}
        val skeleton=Skeleton(joints)
        val data=ArrayList<Float>();val weights=ArrayList<Float>()
        fun box(j:Int,cx:Float,cy:Float,cz:Float,sx:Float,sy:Float,sz:Float){
            val source=MeshesData.cube();for(o in source.indices step 8){for(k in 0..2)data.add(source[o+k]*when(k){0->sx;1->sy;else->sz}+when(k){0->cx;1->cy;else->cz});for(k in 3..7)data.add(source[o+k]);for(k in 0..3)weights.add(if(k==0)j.toFloat()else 0f);for(k in 0..3)weights.add(if(k==0)1f else 0f)}
        }
        box(1,0f,1.03f,0f,.6f,.3f,.35f);box(2,0f,1.48f,0f,.72f,.55f,.4f);box(3,0f,1.96f,0f,.43f,.38f,.4f)
        box(4,-.5f,1.4f,0f,.22f,.45f,.25f);box(5,-.69f,1.02f,0f,.2f,.4f,.23f)
        box(6,.5f,1.4f,0f,.22f,.45f,.25f);box(7,.69f,1.02f,0f,.2f,.4f,.23f)
        box(8,-.22f,.74f,0f,.25f,.45f,.3f);box(9,-.22f,.3f,.05f,.23f,.48f,.37f)
        box(10,.22f,.74f,0f,.25f,.45f,.3f);box(11,.22f,.3f,.05f,.23f,.48f,.37f)
        val body="${prefix}Body.mat";val trim="${prefix}Trim.mat"
        p.materials().save(body,Material("Courier Armor",0xFF476477.toInt(),metallic=.7f,roughness=.3f))
        p.materials().save(trim,Material("Courier Signal",0xFF153E43.toInt(),emissiveColor=0xFF51E6C5.toInt(),emissiveStrength=1.8f,roughness=.24f))
        val model=ModelAsset(prefix,listOf(ModelPart(Mesh(data.toFloatArray(),weights.toFloatArray()),body,true,"Armor")),skeleton,listOf())
        val idle=ArrayList<AnimationTrack>();val run=ArrayList<AnimationTrack>()
        for((name,file)in listOf("Idle" to idle,"Run" to run)){
            val duration=if(name=="Idle")2f else .7f;val times=FloatArray(17){it*duration/16f}
            for(j in intArrayOf(2,4,6,8,10)){
                val values=FloatArray(times.size*4)
                times.forEachIndexed{k,t->val phase=t/duration*2*PI.toFloat();val angle=when(j){2->sin(phase)*2f;4,10->sin(phase)*(if(name=="Run")32f else 3f);6,8->-sin(phase)*(if(name=="Run")32f else 3f);else->0f}
                    val q=Quaternion.fromEuler(angle,0f,if(j==4)-4f else if(j==6)4f else 0f);System.arraycopy(q,0,values,k*4,4)}
                file.add(AnimationTrack(j,"rotation",times,values))
            }
            val t=FloatArray(times.size*3);times.forEachIndexed{k,time->t[k*3]=0f;t[k*3+1]=1f+(if(name=="Run")abs(sin(time/duration*2*PI.toFloat()))*.065f else sin(time/duration*2*PI.toFloat())*.018f);t[k*3+2]=0f}
            file.add(AnimationTrack(1,"translation",times,t))
            p.writeAsset("${prefix}_${name}.skanim",SkeletalClip(name,file,duration,if(name=="Run")listOf(AnimationEvent(.175f,"Footstep"),AnimationEvent(.525f,"Footstep"))else emptyList()).toJson().toString(2))
        }
        val name="$prefix.smodel";p.writeAsset(name,model.copyWithClips(listOf("${prefix}_Idle.skanim","${prefix}_Run.skanim")).toJson().toString())
        return name
    }
    private fun ModelAsset.copyWithClips(clips:List<String>)=ModelAsset(name,parts,skeleton,clips)

    /** Standards-compliant RGBA PNG encoder used for small original material maps. */
    fun png(width:Int,height:Int,rgba:ByteArray):ByteArray {
        require(width in 1..2048&&height in 1..2048&&rgba.size==width*height*4)
        val out=ByteArrayOutputStream()
        fun int(v:Int){out.write(v ushr 24);out.write(v ushr 16);out.write(v ushr 8);out.write(v)}
        fun chunk(type:String,data:ByteArray){int(data.size);val body=type.toByteArray(Charsets.US_ASCII)+data;out.write(body);val crc=CRC32();crc.update(body);int(crc.value.toInt())}
        out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        val ihdr=ByteArray(13);fun put(o:Int,v:Int){for(k in 0..3)ihdr[o+k]=(v ushr (24-k*8)).toByte()};put(0,width);put(4,height);ihdr[8]=8;ihdr[9]=6;chunk("IHDR",ihdr)
        val raw=ByteArray(height*(width*4+1));for(y in 0 until height){raw[y*(width*4+1)]=0;System.arraycopy(rgba,y*width*4,raw,y*(width*4+1)+1,width*4)}
        val compressed=ByteArray(raw.size+raw.size/10+128);val deflater=Deflater();deflater.setInput(raw);deflater.finish();val n=deflater.deflate(compressed);deflater.end();chunk("IDAT",compressed.copyOf(n));chunk("IEND",ByteArray(0));return out.toByteArray()
    }
    private object MeshesData{fun cube()=com.sengine.engine.render.Meshes.cube()}
}
