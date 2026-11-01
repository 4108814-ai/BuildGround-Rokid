package com.buildground.foldregression

import java.util.concurrent.atomic.AtomicBoolean
class ComponentName
interface IBinder
interface ServiceConnection {
 fun onServiceConnected(name:ComponentName?,binder:IBinder?)
 fun onServiceDisconnected(name:ComponentName?)
 fun onBindingDied(name:ComponentName?)
 fun onNullBinding(name:ComponentName?)
}
class Intent(val action:String) {
 fun setPackage(value:String)=this
 fun putExtra(key:String,value:String)=this
}
class Context {
 var binds=0;var unbinds=0;var accept=true
 val packageName="test.only"
 fun bindService(i:Intent,c:ServiceConnection,f:Int):Boolean {binds++;return accept}
 fun unbindService(c:ServiceConnection) {unbinds++}
 companion object {const val BIND_AUTO_CREATE=1}
}
class DeadObjectException:Exception()
object SystemClock {var now=0L;fun elapsedRealtime()=now}
object Log {fun i(t:String,m:String){};fun w(t:String,m:String){}}
class FakeBinder(val value:IMediaStreamService):IBinder
interface IMediaStreamService {
 val isDeviceConnected:Boolean
 object Stub {fun asInterface(b:IBinder)=(b as FakeBinder).value}
}
class Native(var value:Boolean):IMediaStreamService {
 var beforeRead:(()->Unit)?=null
 var reads=0
 override val isDeviceConnected:Boolean get(){reads++;beforeRead?.invoke();return value}
}
class LinkCallback {
 val cxr=mutableListOf<Boolean>();val bt=mutableListOf<Boolean>()
 fun onCXRLConnected(v:Boolean){cxr.add(v)}
 fun onGlassBtConnected(v:Boolean){bt.add(v)}
}
class CxrBaseline(val context:Context) {

 var service:IMediaStreamService?=null
 var bound=false
 var bindingPendingSinceMs=0L
 var glassConnected=false
 val linkCbk=LinkCallback()
 var registrations=0
 private val LOG_TAG="test"
 private val MEDIA_STREAM_ACTION="public.service"
 private val GLOBAL_PKG="vendor.app"
 private val EXTRA_AUTH_TOKEN="not-a-real-token"
 private val EXTRA_AUTH_PACKAGE="app"
 fun tryRegisterAllCallbacks(s:IMediaStreamService){registrations++}
 fun tryUnregisterAllCallbacks(s:IMediaStreamService){}
 private inline fun <T> tryCall(block:()->T):T?=try{block()}catch(t:Throwable){if(t is DeadObjectException) markDisconnected("dead binder",true);null}
 fun connected(s:IMediaStreamService){serviceConnection.onServiceConnected(null,FakeBinder(s))}
 fun disconnected(){serviceConnection.onServiceDisconnected(null)}
 fun died(){serviceConnection.onBindingDied(null)}
 fun rejected(){serviceConnection.onNullBinding(null)}
 fun isServiceConnected()=bound && service!=null
private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                markDisconnected("null binder", unbind = false)
                return
            }
            val svc = IMediaStreamService.Stub.asInterface(binder)
            service = svc
            bound = true
            tryRegisterAllCallbacks(svc)
            glassConnected = tryCall { svc.isDeviceConnected } ?: false
            Log.i(LOG_TAG, "service connected glassConnected=$glassConnected")
            linkCbk?.onCXRLConnected(true)
            linkCbk?.onGlassBtConnected(glassConnected)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            markDisconnected("service disconnected", unbind = true)
        }
        override fun onBindingDied(name: ComponentName?) {
            markDisconnected("binding died", unbind = true)
        }
        override fun onNullBinding(name: ComponentName?) {
            markDisconnected("null binding", unbind = true)
        }
    }
private fun markDisconnected(reason: String, unbind: Boolean) {
        val wasBound = bound
        val wasGlassConnected = glassConnected
        service = null
        bound = false
        glassConnected = false
        if (unbind && wasBound) {
            runCatching { context.unbindService(serviceConnection) }
        }
        Log.w(LOG_TAG, "service disconnected: $reason")
        linkCbk?.onCXRLConnected(false)
        if (wasGlassConnected) linkCbk?.onGlassBtConnected(false)
    }
fun connect(token: String): Boolean {
        if (bound && service != null) return true
        if (bound) {
            runCatching { context.unbindService(serviceConnection) }
            bound = false
        }
        val intent = Intent(MEDIA_STREAM_ACTION)
            .setPackage(GLOBAL_PKG)
            .putExtra(EXTRA_AUTH_TOKEN, token)
            .putExtra(EXTRA_AUTH_PACKAGE, context.packageName)
        bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) Log.w(LOG_TAG, "bindService returned false; is Hi Rokid (global) installed and authorized?")
        return bound
    }
fun disconnect() {
        service?.let { tryUnregisterAllCallbacks(it) }
        if (bound) {
            runCatching { context.unbindService(serviceConnection) }
            bound = false
        }
        service = null
        glassConnected = false
    }
}
class CxrCandidate(val context:Context) {

 var service:IMediaStreamService?=null
 var bound=false
 var bindingPendingSinceMs=0L
 var glassConnected=false
 val linkCbk=LinkCallback()
 var registrations=0
 private val LOG_TAG="test"
 private val MEDIA_STREAM_ACTION="public.service"
 private val GLOBAL_PKG="vendor.app"
 private val EXTRA_AUTH_TOKEN="not-a-real-token"
 private val EXTRA_AUTH_PACKAGE="app"
 fun tryRegisterAllCallbacks(s:IMediaStreamService){registrations++}
 fun tryUnregisterAllCallbacks(s:IMediaStreamService){}
 private inline fun <T> tryCall(block:()->T):T?=try{block()}catch(t:Throwable){if(t is DeadObjectException) markDisconnected("dead binder",true);null}
 fun connected(s:IMediaStreamService){serviceConnection.onServiceConnected(null,FakeBinder(s))}
 fun disconnected(){serviceConnection.onServiceDisconnected(null)}
 fun died(){serviceConnection.onBindingDied(null)}
 fun rejected(){serviceConnection.onNullBinding(null)}
 fun isServiceConnected()=bound && service!=null
private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                markDisconnected("null binder", unbind = true)
                return
            }
            val svc = IMediaStreamService.Stub.asInterface(binder)
            service = svc
            bound = true
            bindingPendingSinceMs = 0L
            tryRegisterAllCallbacks(svc)
            glassConnected = tryCall { svc.isDeviceConnected } ?: false
            Log.i(LOG_TAG, "service connected glassConnected=$glassConnected")
            linkCbk?.onCXRLConnected(true)
            linkCbk?.onGlassBtConnected(glassConnected)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            // Android retains this registration and reconnects it when the service returns.
            bindingPendingSinceMs = SystemClock.elapsedRealtime()
            markDisconnected("service disconnected", unbind = false)
        }
        override fun onBindingDied(name: ComponentName?) {
            markDisconnected("binding died", unbind = true)
        }
        override fun onNullBinding(name: ComponentName?) {
            markDisconnected("null binding", unbind = true)
        }
    }
private fun markDisconnected(reason: String, unbind: Boolean) {
        val wasBound = bound
        val wasGlassConnected = glassConnected
        service = null
        if (unbind) {
            bound = false
            bindingPendingSinceMs = 0L
        }
        glassConnected = false
        if (unbind && wasBound) {
            runCatching { context.unbindService(serviceConnection) }
        }
        Log.w(LOG_TAG, "service disconnected: $reason")
        linkCbk?.onCXRLConnected(false)
        if (wasGlassConnected) linkCbk?.onGlassBtConnected(false)
    }
fun connect(token: String): Boolean {
        if (bound && service != null) return true
        if (bound) {
            // Let Android reconnect transient loss. A bind can also never deliver a callback;
            // the caller's existing retry cadence may replace it after this bounded grace.
            if (SystemClock.elapsedRealtime() - bindingPendingSinceMs < 30_000L) return true
            runCatching { context.unbindService(serviceConnection) }
            bound = false
        }
        val intent = Intent(MEDIA_STREAM_ACTION)
            .setPackage(GLOBAL_PKG)
            .putExtra(EXTRA_AUTH_TOKEN, token)
            .putExtra(EXTRA_AUTH_PACKAGE, context.packageName)
        bindingPendingSinceMs = SystemClock.elapsedRealtime()
        bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) Log.w(LOG_TAG, "bindService returned false; is Hi Rokid (global) installed and authorized?")
        return bound
    }
fun disconnect() {
        service?.let { tryUnregisterAllCallbacks(it) }
        if (bound) {
            runCatching { context.unbindService(serviceConnection) }
            bound = false
        }
        service = null
        glassConnected = false
        bindingPendingSinceMs = 0L
    }
fun refreshDeviceConnection(): Boolean? {
        val expected = service ?: return null
        val connected = tryCall { expected.isDeviceConnected } ?: return null
        // A reply from a retired service must not overwrite a replacement's cached state.
        if (!bound || service !== expected) return null
        if (glassConnected != connected) {
            glassConnected = connected
            linkCbk?.onGlassBtConnected(connected)
        }
        return connected
    }
}

class ManualQueue {
 val tasks=mutableListOf<()->Unit>()
 fun execute(f:()->Unit){tasks.add(f)}
 fun post(f:()->Unit):Boolean {tasks.add(f);return true}
 fun run(){val copy=tasks.toList();tasks.clear();copy.forEach{it()}}
}
class PhoneRecovery {
 var hubEnabled=true;var sppLoopStop=false;var healthy=false
 var cxrLink:CxrCandidate?=null
 val executor=ManualQueue();val pinHandler=ManualQueue()
 val cxrRecoveryInFlight=AtomicBoolean(false)
 var bindRequests=0
 fun isCxrUp()=healthy
 fun startCxrIfTokenAvailable(){bindRequests++}
 fun tick(){requestCxrRecoveryForSppRetry()}
private fun requestCxrRecoveryForSppRetry() {
        if (!hubEnabled || sppLoopStop || isCxrUp() ||
            !cxrRecoveryInFlight.compareAndSet(false, true)
        ) return
        executor.execute {
            try {
                if (!hubEnabled || sppLoopStop || isCxrUp()) return@execute
                val link = cxrLink
                if (link?.isServiceConnected() == true) {
                    // Do not rebind a healthy native service or open anything on the glasses.
                    link.refreshDeviceConnection()
                } else {
                    // Serialize bind with the existing main-thread service stop/destroy path.
                    pinHandler.post {
                        if (hubEnabled && !sppLoopStop && !isCxrUp()) {
                            startCxrIfTokenAvailable()
                        }
                    }
                }
            } finally {
                cxrRecoveryInFlight.set(false)
            }
        }
    }
}

fun main(){
 val old=CxrBaseline(Context());old.connect("test");old.connected(Native(true));old.disconnected()
 check(old.context.unbinds==1 && !old.bound)
 println("PASS baseline transient disconnect destroys automatic binding")
 val c=CxrCandidate(Context());c.connect("test");c.connected(Native(true));c.disconnected()
 check(c.context.unbinds==0 && c.bound && c.service==null)
 c.connect("test");check(c.context.binds==1)
 println("PASS transient disconnect retains binding without churn")
 SystemClock.now=29_999;c.connect("test");check(c.context.binds==1)
 println("PASS pending bind receives bounded automatic-reconnect grace")
 SystemClock.now=30_000;c.connect("test");check(c.context.binds==2 && c.context.unbinds==1)
 println("PASS callback-never-arrives bind is replaced at next eligible retry")
 c.connected(Native(true));check(c.registrations==2 && c.linkCbk.bt.last())
 println("PASS framework reconnect restores callbacks and fresh state")
 c.died();check(!c.bound && c.context.unbinds==2)
 c.connect("test");check(c.context.binds==3)
 println("PASS dead binding releases registration and next retry rebinds")
 c.rejected();check(!c.bound && c.context.unbinds==3)
 println("PASS null binding releases registration")
 c.connect("test");val n=Native(false);c.connected(n);n.value=true
 check(c.refreshDeviceConnection()==true && c.linkCbk.bt.last())
 val notifications=c.linkCbk.bt.size;c.refreshDeviceConnection()
 check(c.linkCbk.bt.size==notifications)
 println("PASS missed positive callback refreshed without rebind or duplicate")
 n.value=false;check(c.refreshDeviceConnection()==false && !c.linkCbk.bt.last())
 println("PASS native false stays false; no fabricated connection")
 val next=Native(true);n.beforeRead={c.connected(next)}
 check(c.refreshDeviceConnection()==null && c.service===next && c.glassConnected)
 println("PASS retired-service reply cannot overwrite replacement")
 next.beforeRead={throw DeadObjectException()}
 check(c.refreshDeviceConnection()==null && !c.bound)
 c.connect("test");check(c.bound)
 println("PASS dead-object getter failure permits later rebind")
 c.disconnect();val count=c.context.binds;check(!c.bound && c.service==null && c.context.binds==count)
 println("PASS explicit disconnect does not self-restart")
 val p=PhoneRecovery();p.tick();p.tick();check(p.executor.tasks.size==1)
 p.executor.run();check(p.pinHandler.tasks.size==1)
 p.hubEnabled=false;p.pinHandler.run();check(p.bindRequests==0)
 println("PASS retry coalesced and posted bind suppressed after stop")
 val q=PhoneRecovery();q.tick();q.sppLoopStop=true;q.executor.run()
 check(q.pinHandler.tasks.isEmpty() && q.bindRequests==0)
 println("PASS destroy before worker suppresses recovery")
 val h=PhoneRecovery();h.healthy=true;h.tick();check(h.executor.tasks.isEmpty())
 println("PASS healthy control has no probe or rebind")
 val r=PhoneRecovery();val l=CxrCandidate(Context());l.connect("test");val native=Native(false);l.connected(native);native.value=true;r.cxrLink=l
 r.tick();r.executor.run();check(l.glassConnected && l.context.binds==1 && r.pinHandler.tasks.isEmpty())
 println("PASS stale control gets fresh native status off SPP thread")
}
