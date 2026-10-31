package com.sengine.engine.script

import com.sengine.engine.Engine
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.physics.PhysicsWorld
import org.mozilla.javascript.*
import java.io.File

/**
 * Interpreted ES6-subset JavaScript runtime. Each component has an isolated scope; modules share
 * exports, never mutable instance globals. Host Java packages are not exposed to project scripts.
 */
class ScriptSystem(val engine:Engine,private val enabled:Boolean=true):PhysicsWorld.Listener {
    internal class Instance(val go:GameObject,val comp:ScriptComponent,val scope:Scriptable,val file:String){
        var started=false;var failed=false;var wasActive=false;var fixedAccumulator=0f;var calls=0L;var lastMs=0f
    }
    private class BudgetExceeded(message:String):RuntimeException(message)
    private class RuntimeContext:Context(){
        var check:()->Unit={}
        override fun observeInstructionCount(instructionCount:Int)=check()
    }
    private var cx:Context?=null;private var global:ScriptableObject?=null;private var ownerThread:Thread?=null
    private val instances=ArrayList<Instance>();private val wrappers=HashMap<Long,SObject>()
    private val compiled=HashMap<String,Pair<String,Script?>>();private val modules=HashMap<String,Scriptable>()
    private val loadingModules=HashSet<String>();private var active:Instance?=null;private var callDepth=0
    private var callStart=0L;private var spawnedThisFrame=0
    private val inputApi=SInput(engine)
    val events=ScriptEvents(this);val timers=ScriptTimers(this)
    val isRunning get()=cx!=null
    val currentOwner:Any? get()=active
    val instanceCount get()=instances.size
    val diagnostics get()=instances.map{mapOf("script" to it.file,"object" to it.go.name,"calls" to it.calls,"milliseconds" to it.lastMs,"failed" to it.failed)}
    var maxCallbackMs=35L
    var maxSpawnPerFrame=256

    fun begin(){
        if(!enabled)return
        end();val c=RuntimeContext();Context.enter(c);c.optimizationLevel=-1;c.languageVersion=Context.VERSION_ES6
        c.wrapFactory.isJavaPrimitiveWrap=false;c.instructionObserverThreshold=4096
        c.setClassShutter(ClassShutter { name -> name in setOf(
            "com.sengine.engine.script.SObject","com.sengine.engine.script.SScene","com.sengine.engine.script.SInput",
            "com.sengine.engine.script.SStorage","com.sengine.engine.script.STime","com.sengine.engine.script.SAudio",
            "com.sengine.engine.script.SConsole","com.sengine.engine.script.ScriptEvents","com.sengine.engine.script.ScriptTimers") })
        cx=c;ownerThread=Thread.currentThread();val g=c.initStandardObjects();global=g
        for(name in listOf("Packages","java","javax","org","com","edu","net","JavaAdapter","importClass","importPackage"))g.delete(name)
        inputApi.sync();put(g,"input",inputApi);put(g,"time",STime(engine));put(g,"scene",SScene(engine,this));put(g,"audio",SAudio(engine))
        put(g,"storage",SStorage(engine.scriptStorageProject));put(g,"console",SConsole(engine));put(g,"events",events);put(g,"timers",timers)
        c.evaluateString(g,PRELUDE,"engine-runtime.js",1,null)
        for(go in engine.scene.objects.toList())attach(go)
        startPending()
    }
    fun end(){
        if(cx!=null&&Thread.currentThread()===ownerThread){
            for(inst in instances.toList())if(inst.started&&!inst.failed&&inst.go.isActiveInHierarchy())call(inst,"onStop")
            Context.exit()
        }
        cx=null;global=null;ownerThread=null;active=null;callDepth=0;instances.clear();wrappers.clear();compiled.clear();modules.clear();loadingModules.clear();events.clear();timers.clear()
    }
    private fun put(scope:Scriptable,name:String,obj:Any){ScriptableObject.putProperty(scope,name,Context.javaToJS(obj,scope))}
    fun wrap(go:GameObject)=wrappers.getOrPut(go.id){SObject(go,engine,this)}
    fun toJs(go:GameObject?):Any?=go?.let{Context.javaToJS(wrap(it),global?:return null)}
    fun newArray(items:List<Any?>):Scriptable?{val c=cx?:return null;val g=global?:return null;return c.newArray(g,items.toTypedArray())}
    fun canSpawn():Boolean=spawnedThisFrame<maxSpawnPerFrame

    /** Non-recursive: callers explicitly attach descendants exactly once. */
    fun attach(go:GameObject){
        val c=cx?:return;val g=global?:return
        for(comp in go.components.filterIsInstance<ScriptComponent>()){
            if(comp.script.isBlank()||instances.any{it.comp===comp})continue
            if(!validAsset(comp.script)){report(comp.script,go,"Script path must stay inside assets");continue}
            val file=engine.project.assetFile(comp.script)
            if(!file.isFile||file.length()>ScriptDiagnostics.MAX_SOURCE_BYTES){report(comp.script,go,"Script is missing or exceeds 512 KB");continue}
            val stamp="${file.lastModified()}:${file.length()}"
            var script=compiled[comp.script]?.takeIf{it.first==stamp}?.second
            if(!compiled.containsKey(comp.script) || compiled[comp.script]?.first!=stamp){
                val raw=engine.project.readAsset(comp.script)
                val source=if(raw==null)null else if(comp.script.endsWith(".bp"))try{com.sengine.engine.blueprint.BlueprintCompiler.compile(com.sengine.engine.blueprint.Blueprint.parse(raw))}catch(e:Exception){report(comp.script,go,e.message?:"Invalid blueprint");null}else raw
                script=if(source==null)null else try{c.compileString(source,comp.script,1,null)}catch(e:RhinoException){report(comp.script,go,"${e.lineNumber()}: ${e.details()}");null}
                compiled[comp.script]=stamp to script
            }
            if(script==null)continue
            val scope=c.newObject(g);scope.prototype=g;scope.parentScope=null
            val self=Context.javaToJS(wrap(go),g);for(name in listOf("self","transform","gameObject"))ScriptableObject.putProperty(scope,name,self)
            put(scope,"require",ModuleRequire(this,comp.script))
            val inst=Instance(go,comp,scope,comp.script)
            instances.add(inst)
            try{
                val params=ScriptParameters.parse(comp.params);params.forEach{(k,v)->ScriptableObject.putProperty(scope,k,v)}
                guarded(inst,"initialize"){script.exec(c,scope)}
                params.forEach{(k,v)->ScriptableObject.putProperty(scope,k,v)}
            }catch(e:Exception){fail(inst,e)}
        }
    }
    fun attachTree(go:GameObject){attach(go);engine.scene.childrenOf(go).forEach(::attachTree)}
    private fun validAsset(name:String):Boolean=name.endsWith(".js")||name.endsWith(".bp")
    private fun startPending(){for(inst in instances.toList())if(!inst.started&&!inst.failed&&inst.go.isActiveInHierarchy()&&inst.comp.enabled){inst.started=true;inst.wasActive=true;call(inst,"start")}}
    fun update(dt:Float){
        if(cx==null)return
        spawnedThisFrame=0;inputApi.sync();startPending()
        var tap:GameObject?=null;if(engine.input.tapped)tap=engine.physics.overlapPoint(engine.scene,engine.input.touchX,engine.input.touchY)
        for(inst in instances.toList()){
            if(inst.failed||inst.go.destroyed)continue
            val activeNow=inst.go.isActiveInHierarchy()&&inst.comp.enabled
            if(activeNow!=inst.wasActive){inst.wasActive=activeNow;call(inst,if(activeNow)"onEnable" else "onDisable")}
            if(!activeNow||!inst.started)continue
            inst.fixedAccumulator+=dt.coerceIn(0f,0.1f);var steps=0
            while(inst.fixedAccumulator>=FIXED_DT&&steps++<6){call(inst,"fixedUpdate",FIXED_DT.toDouble());inst.fixedAccumulator-=FIXED_DT}
            call(inst,"update",dt.toDouble());if(tap===inst.go)call(inst,"onTap")
        }
        timers.update(engine.time)
    }
    fun lateUpdate(dt:Float){for(inst in instances.toList())if(eligible(inst))call(inst,"lateUpdate",dt.toDouble())}
    private fun eligible(inst:Instance)=inst.started&&!inst.failed&&!inst.go.destroyed&&inst.go.isActiveInHierarchy()&&inst.comp.enabled
    private fun guarded(inst:Instance,label:String,block:()->Any?):Any?{
        val budget=if(label=="start"||label=="initialize")250L else maxCallbackMs
        if(callDepth>=24)throw BudgetExceeded("Callback recursion limit (24) exceeded")
        val prior=active;val priorStart=callStart;val context=cx as? RuntimeContext
        active=inst;callDepth++;callStart=System.nanoTime();inst.calls++
        context?.check={if(System.nanoTime()-callStart>budget*1_000_000)throw BudgetExceeded("$label exceeded ${budget}ms; instance disabled")}
        return try{block()}finally{active=prior;callStart=priorStart;callDepth--;context?.check={}}
    }
    private fun fail(inst:Instance,e:Exception){
        inst.failed=true
        val detail=if(e is RhinoException) e.lineNumber().toString()+": "+e.details() else (e.message?:e.javaClass.simpleName)
        report(inst.file,inst.go,detail)
    }
    private fun report(file:String,go:GameObject,message:String){engine.log(2,"$file on '${go.name}': $message")}
    private fun call(inst:Instance,name:String,vararg args:Any?):Any?{
        val f=inst.scope.get(name,inst.scope);if(f !is org.mozilla.javascript.Function)return null
        val start=System.nanoTime()
        return try{guarded(inst,name){f.call(cx,inst.scope,inst.scope,args)}}catch(e:Exception){fail(inst,e);null}
        finally{inst.lastMs=(System.nanoTime()-start)/1e6f}
    }
    internal fun invokeOwner(owner:Any,label:String,callback:org.mozilla.javascript.Function,vararg args:Any?):Boolean{
        val inst=owner as? Instance?:return false;if(!eligible(inst))return false
        return try{guarded(inst,label){callback.call(cx,inst.scope,inst.scope,args)};true}catch(e:Exception){fail(inst,e);false}
    }
    fun sendMessage(go:GameObject,name:String,arg:Any?):Any?{
        var result:Any?=null;for(inst in instances.toList())if(inst.go===go&&eligible(inst))result=call(inst,name,arg)?:result;return result
    }
    private fun dispatch(go:GameObject,name:String,other:GameObject){for(inst in instances.toList())if(inst.go===go&&eligible(inst))call(inst,name,toJs(other))}
    fun onDestroyed(go:GameObject){
        for(inst in instances.toList())if(inst.go===go){if(inst.started&&!inst.failed)call(inst,"onDestroy");instances.remove(inst);events.removeOwner(inst);timers.removeOwner(inst)}
        if(instances.none{it.go===go})wrappers.remove(go.id)
    }
    override fun onCollisionEnter(a:GameObject,b:GameObject){dispatch(a,"onCollision",b);dispatch(b,"onCollision",a)}
    override fun onTriggerEnter(a:GameObject,b:GameObject){dispatch(a,"onTrigger",b);dispatch(b,"onTrigger",a)}
    override fun onTriggerExit(a:GameObject,b:GameObject){dispatch(a,"onTriggerExit",b);dispatch(b,"onTriggerExit",a)}

    /** Project-contained CommonJS modules. Cache is per play session; circular imports fail clearly. */
    internal fun require(from:String,path:String):Any?{
        val c=cx?:error("require() is available while playing");val g=global?:error("Runtime unavailable")
        val base=File(from).parentFile?:File("")
        val file=File(base,path).normalize()
        require(!file.isAbsolute && !file.path.startsWith("..")){"require() cannot escape project assets"}
        val name=file.path.replace('\\','/')
        require(name.endsWith(".js") && name.length<=160){"require() accepts project .js modules"}
        val f=engine.project.assetFile(name);require(f.isFile&&f.length()<=ScriptDiagnostics.MAX_SOURCE_BYTES){"Module not found or too large: $name"}
        modules[name]?.let{return it}
        require(loadingModules.add(name)){"Circular module dependency: $name"}
        try{
            val scope=c.newObject(g);scope.prototype=g;scope.parentScope=null
            val exports=c.newObject(scope);put(scope,"exports",exports)
            val module=c.newObject(scope);ScriptableObject.putProperty(module,"exports",exports);put(scope,"module",module);put(scope,"require",ModuleRequire(this,name))
            val compiled=c.compileString(f.readText(),name,1,null);compiled.exec(c,scope)
            val result=ScriptableObject.getProperty(module,"exports");modules[name]=result as? Scriptable ?: c.newObject(scope).also{put(it,"value",result)};return modules[name]
        }finally{loadingModules.remove(name)}
    }
    private class ModuleRequire(val sys:ScriptSystem,val from:String):BaseFunction(){
        override fun getFunctionName()="require"
        override fun call(cx:Context,scope:Scriptable,thisObj:Scriptable,args:Array<out Any?>):Any?{
            require(args.size==1){"require(path) takes one module path"}
            return sys.require(from,Context.toString(args[0]))
        }
    }
    companion object{
        const val FIXED_DT=1f/60f
        const val PRELUDE="""
function log(){var a=[];for(var i=0;i<arguments.length;i++)a.push(String(arguments[i]));console.log(a.join(' '));}
function warn(m){console.warn(String(m));}function error(m){console.error(String(m));}
function random(a,b){if(a===undefined)return Math.random();return a+Math.random()*(b-a);}
function randomInt(a,b){return Math.floor(random(a,b+1));}
function clamp(v,a,b){return Math.max(a,Math.min(b,v));}function lerp(a,b,t){return a+(b-a)*t;}
function after(s,f){return timers.after(s,f);}function every(s,f){return timers.every(s,f);}function cancelTimer(id){timers.cancel(id);}
function assert(condition,message){if(!condition)throw new Error(message||'Assertion failed');}
function moveTowards(value,target,maxDelta){var d=target-value;return Math.abs(d)<=maxDelta?target:value+Math.sign(d)*maxDelta;}
function damp(a,b,smooth,dt){return lerp(a,b,1-Math.exp(-smooth*dt));}
"""
    }
}
