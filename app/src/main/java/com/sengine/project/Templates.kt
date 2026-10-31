package com.sengine.project

import com.sengine.engine.core.*

/** New-project catalog. Legacy builders are regression fixtures, not shipped templates. */
object Templates {
    class Template(val id:String,val name:String,val description:String,val dimension:String,val features:List<String>,val build:(Project)->Unit)
    val all:List<Template> by lazy{listOf(
        Template("empty-2d","Empty 2D","A clean scene. Your cameras, your rules.","2d",listOf("No starter objects","2D viewport","Project-local assets")){p->
            p.dimension="2d";p.saveScene(Scene("Main"));p.startScene="Main"
        },
        Template("empty-3d","Empty 3D","An empty world with the 3D editor ready.","3d",listOf("No starter objects","PBR materials","glTF + skeletal animation")){p->
            p.dimension="3d";p.saveScene(Scene("Main"));p.startScene="Main"
        },
        Template("neon-relay","Neon Relay","A precision platformer about delivering the last signal.","2d",listOf("Coyote time + buffered jumps","Checkpoints","Collectibles + timed result")) { buildRelay(it) },
        Template("orbit-salvage","Orbit Salvage","Survive a debris field. Recover energy. Keep moving.","2d",listOf("Dual-axis flight","Pooling-friendly projectiles","Wave director + high score")) { buildOrbit(it) },
        Template("sentinel-protocol","Sentinel Protocol","A third-person recovery mission with an animated courier.","3d",listOf("Skeletal locomotion","PBR lighting","Physics + extraction mission")) { buildSentinel(it) },
        Template("studio-lab","Animation & Material Lab","An editable look-development scene, not a pre-rendered demo.","3d",listOf("Weighted robot rig","Idle / run clips","Live material spheres")) { buildLab(it) },
    )}
    fun byId(id:String)=all.firstOrNull{it.id==id}
    const val NEW_SCRIPT="""// S Engine Studio • JavaScript behaviour
// Lifecycle: start, update(dt), fixedUpdate(dt), lateUpdate(dt)
// Events: onEnable, onDisable, onTrigger, onAnimationEvent(name), onDestroy
// API: self, input, scene, time, events, timers, storage, require('./Module.js')
var speed = 5;
function start() {
    log('Ready:', self.name);
}
function update(dt) {
    // self.move(input.axisX * speed * dt, 0);
}
"""
    const val NEW_SHADER="""// S Engine effect ABI • GLSL ES
// Available: uTime, uParam, uTex, uUseTex, uColor, uResolution
vec4 effect(vec4 color, vec2 uv) {
    float pulse = 0.9 + 0.1 * sin(uTime * 2.0);
    return vec4(color.rgb * pulse, color.a);
}
"""
    private fun camera2(s:Scene,size:Float,bg:Long,follow:String=""):GameObject= s.create("Main Camera").also{it.add(Camera2D().also{c->c.size=size;c.background=bg.toInt();c.follow=follow})}
    private fun GameObject.sprite(color:Long,shape:Int=0)=also{add(SpriteRenderer().also{it.color=color.toInt();it.shape=shape})}
    private fun GameObject.script(name:String,params:String="")=also{add(ScriptComponent().also{it.script=name;it.params=params})}
    private fun GameObject.box(trigger:Boolean=false)=also{add(Collider2D().also{it.isTrigger=trigger})}
    private fun GameObject.body(type:Int=0)=also{add(Rigidbody2D().also{it.bodyType=type;it.friction=0f})}
    private fun text(s:Scene,name:String,value:String,y:Float,size:Float=.35f):GameObject=s.create(name).also{it.y=y;it.order=100;it.add(TextRenderer().also{t->t.text=value;t.screenSpace=true;t.size=size;t.bold=true})}
    private fun light(s:Scene):GameObject=s.create("Key Light").also{it.rotX=48f;it.rotY=-32f;it.add(Light().also{l->l.intensity=1.3f})}
    private fun mesh(s:Scene,name:String,x:Float,y:Float,z:Float,sx:Float,sy:Float,sz:Float,shape:Int,mat:String):GameObject=s.create(name).also{it.x=x;it.y=y;it.z=z;it.scaleX=sx;it.scaleY=sy;it.scaleZ=sz;it.add(MeshRenderer().also{it.mesh=shape;it.material=mat})}

    private fun buildRelay(p:Project){
        p.dimension="2d";p.orientation=0
        p.writeAsset("RelayController.js","""var speed=6, jump=10, coyote=0, buffer=0, spawnX=0, spawnY=0, elapsed=0, cells=0, won=false;
function start(){spawnX=self.x;spawnY=self.y;events.on('relay:cell',function(){cells++;updateHUD();});}
function update(dt){
 if(won)return;elapsed+=dt;coyote=self.grounded?.12:Math.max(0,coyote-dt);buffer=input.aDown?.14:Math.max(0,buffer-dt);
 self.vx=input.axisX*speed;if(input.axisX!==0)self.flipX=input.axisX<0;
 if(buffer>0&&coyote>0){self.vy=jump;buffer=0;coyote=0;audio.beep();}
 if(!input.a&&self.vy>3)self.vy*=.9;
 if(self.y < -6)respawn();updateHUD();
}
function respawn(){self.setPosition(spawnX,spawnY);self.setVelocity(0,0);scene.shake(.25);}
function onTrigger(other){if(other.tag==='Hazard')respawn();if(other.tag==='Checkpoint'){spawnX=other.x;spawnY=other.y+.7;events.emit('relay:checkpoint',other.name);}if(other.tag==='Goal'&&cells>=5){won=true;var best=storage.get('best',99999);if(elapsed<best)storage.set('best',elapsed);scene.find('HUD').text='RELAY RESTORED  •  '+elapsed.toFixed(2)+' s';}}
function updateHUD(){if(!won)scene.find('HUD').text='SIGNAL '+cells+' / 5   •   '+elapsed.toFixed(1)+' s';}
""")
        p.writeAsset("EnergyCell.js","""var origin=0;function start(){origin=self.y;}function update(dt){self.y=origin+Math.sin(time.time*3+origin)*.1;}
function onTrigger(other){if(other.tag!=='Player')return;events.emit('relay:cell',self.id);audio.beep();self.destroy();}""")
        val s=Scene("Main");s.gravityY=-22f;camera2(s,5.5f,0xFF101C2C,"Player")
        text(s,"HUD","SIGNAL 0 / 5",4.4f)
        fun platform(name:String,x:Float,y:Float,w:Float,h:Float= .5f){s.create(name).also{it.x=x;it.y=y;it.scaleX=w;it.scaleY=h;it.sprite(0xFF315066);it.box()}}
        platform("Ground",0f,-2f,14f,1f);platform("Step A",5f,-.6f,3f);platform("Step B",9f,.7f,3f);platform("Checkpoint Deck",13f,1.4f,4f);platform("Relay Deck",18f,2.1f,5f)
        val player=s.create("Player").also{it.x=-5f;it.y=-1f;it.scaleX=.65f;it.scaleY=.9f;it.tag="Player";it.order=10;it.sprite(0xFF63E6CD);it.box();it.body();it.script("RelayController.js")}
        listOf(-2f to -1f,1f to -1f,5f to .3f,9f to 1.6f,13f to 2.3f).forEach{v->s.create("Energy Cell").also{it.x=v.first;it.y=v.second;it.scaleX=.35f;it.scaleY=.35f;it.tag="Cell";it.sprite(0xFFFFC96B,1);it.add(Collider2D().also{c->c.shape=1;c.isTrigger=true});it.script("EnergyCell.js")}}
        s.create("Checkpoint").also{it.x=12.5f;it.y=2f;it.scaleX=.6f;it.scaleY=.7f;it.tag="Checkpoint";it.sprite(0xFF70A5FF);it.box(true)}
        s.create("Hazard").also{it.x=16f;it.y=-2f;it.scaleX=2f;it.scaleY=1f;it.tag="Hazard";it.sprite(0xFFF2767E,2);it.box(true)}
        s.create("Relay").also{it.x=19f;it.y=3f;it.scaleX=.7f;it.scaleY=1.2f;it.tag="Goal";it.sprite(0xFF63E6CD);it.box(true)}
        p.saveScene(s);p.startScene=s.name
    }
    private fun buildOrbit(p:Project){
        p.dimension="2d"
        p.writeAsset("Pilot.js","""var speed=7,cooldown=0,hull=3,invincible=0;function start(){events.on('salvage:score',function(n){var score=storage.get('runScore',0)+n;storage.set('runScore',score);scene.find('HUD').text='SALVAGE '+score+'   HULL '+hull;});storage.set('runScore',0);}
function update(dt){self.vx=input.axisX*speed;self.vy=input.axisY*speed;self.x=clamp(self.x,-11,11);self.y=clamp(self.y,-6,6);cooldown-=dt;invincible-=dt;if((input.a||input.touching)&&cooldown<=0){cooldown=.18;var shot=scene.spawn('Pulse',self.x,self.y+.5);if(shot)shot.tag='Pulse';}}
function onTrigger(other){if(other.tag==='Debris'&&invincible<=0){hull--;invincible=1;scene.shake(.4);other.destroy();events.emit('salvage:score',0);if(hull<=0){var score=storage.get('runScore',0);storage.set('best',Math.max(score,storage.get('best',0)));scene.find('HUD').text='SIGNAL LOST  •  '+score+'  •  B to retry';self.active=false;events.emit('salvage:over',true);}}}
""")
        p.writeAsset("Pulse.js","function update(dt){if(self.y>8)self.destroy();}function onTrigger(other){if(other.tag==='Debris'){events.emit('salvage:score',10);audio.beep();other.destroy();self.destroy();}}")
        p.writeAsset("Debris.js","function start(){self.vy=-random(1.8,4);self.angularVelocity=random(-1,1);}function update(dt){if(self.y < -8)self.destroy();}")
        p.writeAsset("Director.js","""var elapsed=0,over=false;function start(){events.on('salvage:over',function(){over=true;});every(1.1,function(){if(over)return;var d=scene.spawn('Debris',random(-10,10),8);if(d)d.tag='Debris';});}
function update(dt){elapsed+=dt;if(input.bDown&&over)scene.reload();}""")
        val s=Scene("Main");s.gravityY=0f;camera2(s,7f,0xFF0B1424)
        text(s,"HUD","SALVAGE 0   HULL 3",6f)
        s.create("Player").also{it.tag="Player";it.scaleX=.7f;it.scaleY=.9f;it.sprite(0xFF6BE7D0,2);it.box();it.body();it.script("Pilot.js")}
        s.create("Director").script("Director.js")
        fun prefab(name:String,color:Long,shape:Int,tag:String,script:String,vy:Float){s.create(name).also{it.active=false;it.y=30f;it.tag=tag;it.scaleX=.45f;it.scaleY=.45f;it.sprite(color,shape);it.add(Collider2D().also{c->c.isTrigger=true;c.shape=if(shape==1)1 else 0});it.body().script(script);it.getAny<Rigidbody2D>()!!.startVy=vy}}
        prefab("Pulse",0xFFFFD078,0,"Pulse","Pulse.js",15f);prefab("Debris",0xFFE77783,1,"Debris","Debris.js",-3f)
        p.saveScene(s);p.startScene=s.name
    }
    private fun buildSentinel(p:Project){
        p.dimension="3d";StudioAssets.installSurfaceLibrary(p);val rig=StudioAssets.installRobot(p)
        p.writeAsset("CourierController.js","""var speed=4,turn=170,extracted=false,cells=0;
function start(){events.on('mission:cell',function(){cells++;scene.find('HUD').text='RECOVERED '+cells+' / 4';});self.crossFade('Courier_Idle.skanim',.15);}
function update(dt){if(extracted){if(input.bDown)scene.reload();return;}var moving=Math.abs(input.axisY)>.12;self.rotY-=input.axisX*turn*dt;
 var f=self.forward();self.vx=f[0]*input.axisY*speed;self.vz=f[2]*input.axisY*speed;
 self.crossFade(moving?'Courier_Run.skanim':'Courier_Idle.skanim',.16);
 if(input.aDown&&self.grounded)self.vy=6.5;if(self.y < -5)self.setPosition(0,1,4);
}
function onAnimationEvent(name){if(name==='Footstep')events.emit('courier:step',self.id);}
function onTrigger(other){if(other.tag==='Cell'){other.destroy();events.emit('mission:cell',other.id);}if(other.tag==='Extraction'&&cells>=4){extracted=true;self.crossFade('Courier_Idle.skanim',.2);scene.find('HUD').text='MISSION COMPLETE  •  B to replay';}}
""")
        val s=Scene("Main");s.ambient=0xFF536273.toInt();s.fog=true;s.fogStart=22f;s.fogEnd=65f;s.fogColor=0xFF1C2C3D.toInt()
        s.create("Main Camera").also{it.y=4f;it.z=8f;it.add(Camera3D().also{c->c.follow="Player";c.offsetY=3.3f;c.offsetZ=6f;c.skyTop=0xFF152536.toInt();c.skyHorizon=0xFF667E8D.toInt()})};light(s)
        text(s,"HUD","RECOVERED 0 / 4",4.3f,.3f)
        mesh(s,"Ground",0f,-.2f,0f,32f,.4f,32f,0,"StudioBrick.mat").also{it.add(Collider3D().also{c->c.sizeX=32f;c.sizeY=.4f;c.sizeZ=32f})}
        val player=s.create("Player").also{it.y=.1f;it.z=4f;it.tag="Player";it.add(MeshRenderer().also{m->m.mesh=8;m.model=rig});it.add(SkeletalAnimator().also{a->a.model=rig;a.clip="${rig.substringBefore('.')}_Idle.skanim"});it.add(Collider3D().also{c->c.sizeX=.65f;c.sizeY=2.1f;c.sizeZ=.5f;c.centerY=1.05f});it.add(Rigidbody3D());it.script("CourierController.js")}
        listOf(-5f to -3f,5f to -5f,-7f to -10f,7f to -12f).forEachIndexed{i,(x,z)->mesh(s,"Energy Core $i",x,1f,z,.55f,.55f,.55f,1,"SignalEmitter.mat").also{it.tag="Cell";it.add(Collider3D().also{c->c.shape=1;c.radius=.8f;c.isTrigger=true});it.add(ScriptComponent().also{sc->sc.script="CoreRotation.js"})}}
        p.writeAsset("CoreRotation.js","function update(dt){self.rotY+=60*dt;}")
        mesh(s,"Extraction",0f,.05f,-14f,3f,.1f,3f,3,"SignalEmitter.mat").also{it.tag="Extraction";it.add(Collider3D().also{c->c.shape=1;c.radius=2f;c.isTrigger=true})}
        for(i in 0..5){mesh(s,"Barrier $i",-10f+i*4f,1f,-7f,1.2f,2f,1.2f,0,"BrushedSteel.mat").also{it.add(Collider3D().also{c->c.sizeX=1.2f;c.sizeY=2f;c.sizeZ=1.2f})}}
        p.saveScene(s);p.startScene=s.name
    }
    private fun buildLab(p:Project){
        p.dimension="3d";val rig=StudioAssets.installRobot(p,"LabCourier")
        val s=Scene("Main");s.ambient=0xFF738092.toInt();light(s)
        s.create("Main Camera").also{it.add(Camera3D().also{c->c.follow="Courier";c.offsetX=4f;c.offsetY=2.8f;c.offsetZ=6f;c.skyTop=0xFF172333.toInt();c.skyHorizon=0xFF526575.toInt()})}
        mesh(s,"Stage",0f,-.1f,0f,16f,.2f,12f,0,"RoughConcrete.mat")
        s.create("Courier").also{it.add(MeshRenderer().also{m->m.mesh=8;m.model=rig});it.add(SkeletalAnimator().also{a->a.model=rig;a.clip="LabCourier_Idle.skanim"})}
        listOf("Ceramic","BrushedSteel","Copper","StudioBrick","SignalEmitter").forEachIndexed{i,name->mesh(s,name,-4f+i*2f,.65f,-2.5f,1.1f,1.1f,1.1f,1,"$name.mat")}
        text(s,"HUD","LOOK DEVELOPMENT  •  Select a sphere to edit its material",4.2f,.28f)
        p.saveScene(s);p.startScene=s.name
    }
}
