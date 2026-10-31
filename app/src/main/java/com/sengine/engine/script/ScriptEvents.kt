package com.sengine.engine.script

import org.mozilla.javascript.Function

/** Bounded event bus. Subscriptions are owned by a script instance and cleaned up with it. */
class ScriptEvents(private val runtime: ScriptSystem) {
    data class Subscription(val id: Long, val owner: Any, val name: String, val callback: org.mozilla.javascript.Function, val once: Boolean)
    private val subscriptions=ArrayList<Subscription>();private var nextId=1L
    fun on(name:String,callback:Function):Double=subscribe(name,callback,false)
    fun once(name:String,callback:Function):Double=subscribe(name,callback,true)
    private fun subscribe(name:String,callback:Function,once:Boolean):Double{
        require(name.isNotBlank() && name.length<=96){"Event name must be 1–96 characters"}
        require(subscriptions.size<1024){"Event subscription limit reached"}
        val owner=runtime.currentOwner ?: error("Subscribe from a script callback or module")
        val s=Subscription(nextId++,owner,name,callback,once);subscriptions.add(s);return s.id.toDouble()
    }
    fun off(id:Double){subscriptions.removeAll{it.id.toLong()==id.toLong() && it.owner===runtime.currentOwner}}
    fun emit(name:String,payload:Any?):Double {
        var count=0.0
        for(s in subscriptions.toList().filter{it.name==name}){
            if(s.once)subscriptions.remove(s)
            if(runtime.invokeOwner(s.owner,"event:$name",s.callback,payload))count++
        }
        return count
    }
    fun removeOwner(owner:Any){subscriptions.removeAll{it.owner===owner}}
    fun clear(){subscriptions.clear()}
}

/** Stable frame-independent timers, with cancellation and per-instance ownership. */
class ScriptTimers(private val runtime:ScriptSystem) {
    private data class Timer(val id:Long,val owner:Any,val deadline:Double,val interval:Double,val callback:Function)
    private val timers=ArrayList<Timer>();private var next=1L
    fun after(seconds:Double,callback:Function):Double=add(seconds,0.0,callback)
    fun every(seconds:Double,callback:Function):Double=add(seconds,seconds,callback)
    private fun add(seconds:Double,interval:Double,callback:Function):Double{
        require(seconds.isFinite()&&seconds>=0.0&&seconds<=86400.0){"Timer delay must be 0–86400 seconds"}
        require(timers.size<2048){"Timer limit reached"}
        val owner=runtime.currentOwner ?: error("Timers require an active script")
        val t=Timer(next++,owner,runtime.engine.time+seconds,interval,callback);timers.add(t);return t.id.toDouble()
    }
    fun cancel(id:Double){timers.removeAll{it.id.toLong()==id.toLong()&&it.owner===runtime.currentOwner}}
    fun update(now:Double){
        for(t in timers.toList().filter{it.deadline<=now}){
            if(t.interval>0){
                var deadline=t.deadline+t.interval
                if(deadline<now)deadline=now+t.interval
                val index=timers.indexOf(t);if(index<0)continue;timers[index]=t.copy(deadline=deadline)
            }else timers.remove(t)
            runtime.invokeOwner(t.owner,"timer",t.callback)
        }
    }
    fun removeOwner(owner:Any){timers.removeAll{it.owner===owner}}
    fun clear(){timers.clear()}
}
