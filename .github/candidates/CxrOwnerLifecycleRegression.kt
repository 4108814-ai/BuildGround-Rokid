package com.buildground.ownerregression
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Clock {var now=0L}
object Looper {
 val thread=Thread.currentThread();val main=Any()
 fun getMainLooper()=main
 fun myLooper():Any?=if(Thread.currentThread()===thread)main else null
}
object MainQueue {
 data class Entry(val task:Runnable,val due:Long)
 private val entries=mutableListOf<Entry>()
 @Synchronized fun add(r:Runnable,delay:Long){entries.add(Entry(r,Clock.now+delay))}
 @Synchronized fun remove(r:Runnable){entries.removeAll{it.task===r}}
 @Synchronized private fun poll():Entry? {val i=entries.indexOfFirst{it.due<=Clock.now};return if(i<0)null else entries.removeAt(i)}
 fun drain(){while(true){val e=poll()?:break;e.task.run()}}
 @Synchronized fun clear(){entries.clear()}
}
class Handler {
 fun post(r:Runnable):Boolean {MainQueue.add(r,0);return true}
 fun postDelayed(r:Runnable,delay:Long):Boolean {MainQueue.add(r,delay);return true}
 fun removeCallbacks(r:Runnable){MainQueue.remove(r)}
}
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
 var binds=0;var unbinds=0;var accept=true;var fail=false
 val connections=mutableListOf<ServiceConnection>();val packageName="test.only"
 fun bindService(i:Intent,c:ServiceConnection,f:Int):Boolean {if(fail)throw IllegalStateException();binds++;connections.add(c);return accept}
 fun unbindService(c:ServiceConnection){unbinds++}
 companion object{const val BIND_AUTO_CREATE=1}
}
class DeadObjectException:Exception()
object Log {fun i(t:String,m:String){};fun w(t:String,m:String){}}
class FakeBinder(val remote:IMediaStreamService):IBinder
interface IMediaStreamService {
 val isDeviceConnected:Boolean
 object Stub{fun asInterface(b:IBinder)=(b as FakeBinder).remote}
}
class Native(var value:Boolean):IMediaStreamService {
 var beforeRead:(()->Unit)?=null;var reads=0
 override val isDeviceConnected:Boolean get(){reads++;val snapshot=value;beforeRead?.invoke();return snapshot}
}
class GlassInfo {companion object{fun fromJson(s:String)=GlassInfo()}}
interface IDeviceStatusCallback {
 fun onDeviceConnectChanged(v:Boolean)
 fun onDeviceInfoNotifiy(v:String?)
 fun onWearingStatusNotify(v:Boolean)
 fun onCurrentScenesNotify(v:String?)
 abstract class Stub:IDeviceStatusCallback
}
class LinkCallback {
 val cxr=mutableListOf<Boolean>();val bt=mutableListOf<Boolean>()
 fun onCXRLConnected(v:Boolean){cxr.add(v)}
 fun onGlassBtConnected(v:Boolean){bt.add(v)}
 fun onGlassDeviceInfo(v:GlassInfo){}
 fun onGlassWearingStatus(v:Boolean){}
 fun onGlassCurrentScenesNotify(v:String?){}
}
class Workers {
 private val jobs=mutableListOf<()->Unit>()
 fun execute(f:()->Unit){jobs.add(f)}
 fun count()=jobs.size
 fun drain(){val snapshot=jobs.toList();jobs.clear();snapshot.forEach{it()}}
}

class TestLink(val context:Context) {
 private val main=Handler()
 private val bindings=CxrBindingOwner<Binding,IMediaStreamService>()
 private val service:IMediaStreamService? get()=bindings.currentService()
 private var activeCallbacks:ConnectionCallbacks?=null
 val linkCbk:LinkCallback?=LinkCallback()
 var registrations=0;var unregisters=0
 private val LOG_TAG="test"
 private val MEDIA_STREAM_ACTION="public.service"
 private val GLOBAL_PKG="vendor.app"
 private val EXTRA_AUTH_TOKEN="not-real"
 private val EXTRA_AUTH_PACKAGE="app"
 fun deviceCallback():IDeviceStatusCallback=checkNotNull(activeCallbacks).deviceStatus
 fun pollCaptured(remote:IMediaStreamService):Boolean?=tryCall(remote){remote.isDeviceConnected}
 private fun registerCallbacks(remote:IMediaStreamService,c:ConnectionCallbacks){registrations++}
 private fun unregisterCallbacks(remote:IMediaStreamService,c:ConnectionCallbacks){unregisters++}
private inner class Binding : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!bindings.owns(this)) return
            if (binder == null) {
                releaseBinding(this, "null binder")
                return
            }
            val remote = IMediaStreamService.Stub.asInterface(binder)
            if (!bindings.connected(this, remote)) return
            val callbacks = ConnectionCallbacks(this, remote)
            activeCallbacks = callbacks
            registerCallbacks(remote, callbacks)
            val connected = tryCall(remote) { remote.isDeviceConnected } ?: false
            if (bindings.deviceEvent(this, remote, connected) == null) return
            Log.i(LOG_TAG, "service connected glassConnected=$connected")
            linkCbk?.onCXRLConnected(true)
            linkCbk?.onGlassBtConnected(connected)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val wasConnected = bindings.transientDisconnect(this) ?: return
            activeCallbacks = null
            Log.w(LOG_TAG, "service disconnected: service disconnected")
            linkCbk?.onCXRLConnected(false)
            if (wasConnected) linkCbk?.onGlassBtConnected(false)
        }

        override fun onBindingDied(name: ComponentName?) = releaseBinding(this, "binding died")
        override fun onNullBinding(name: ComponentName?) = releaseBinding(this, "null binding")
    }
private inner class ConnectionCallbacks(val binding:Binding,val remote:IMediaStreamService){
private fun dispatch(block: () -> Unit) {
            main.post {
                if (activeCallbacks === this && bindings.owns(binding)) block()
            }
        }
val deviceStatus = object : IDeviceStatusCallback.Stub() {
            override fun onDeviceConnectChanged(connected: Boolean) = dispatch {
                if (bindings.deviceEvent(binding, remote, connected) != null) {
                    linkCbk?.onGlassBtConnected(connected)
                }
            }
            override fun onDeviceInfoNotifiy(infoJson: String?) = dispatch {
                if (infoJson.isNullOrBlank()) return@dispatch
                runCatching { GlassInfo.fromJson(infoJson) }
                    .onSuccess { linkCbk?.onGlassDeviceInfo(it) }
                    .onFailure { Log.w(LOG_TAG, "parse glass info failed: ${it.javaClass.simpleName}") }
            }
            override fun onWearingStatusNotify(wearing: Boolean) = dispatch {
                linkCbk?.onGlassWearingStatus(wearing)
            }
            override fun onCurrentScenesNotify(scenesJson: String?) = dispatch {
                linkCbk?.onGlassCurrentScenesNotify(scenesJson)
            }
        }
}
private fun releaseBinding(binding: Binding, reason: String) {
        val retired = bindings.release(binding) ?: return
        activeCallbacks = null
        runCatching { context.unbindService(binding) }
        Log.w(LOG_TAG, "service disconnected: $reason")
        linkCbk?.onCXRLConnected(false)
        if (retired.deviceConnected) linkCbk?.onGlassBtConnected(false)
    }
private fun runOnOwner(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
private fun <T> onOwner(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = java.util.concurrent.FutureTask<T> { block() }
        main.post(task)
        var interrupted = false
        try {
            while (true) {
                try { return task.get() }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
private inline fun <T> tryCall(expectedService: IMediaStreamService? = service, block: () -> T): T? {
        val expected = bindings.probe()?.takeIf { it.service === expectedService }
        return try { block() } catch (error: Throwable) {
            Log.w(LOG_TAG, "AIDL call failed: ${error.javaClass.simpleName}")
            if (error is DeadObjectException && expected != null) {
                runOnOwner {
                    if (bindings.ownsService(expected)) releaseBinding(expected.binding, "dead binder")
                }
            }
            null
        }
    }
fun connect(token: String): Boolean = onOwner {
        // A pending bind has no timeout-driven cancellation. Android owns its completion.
        if (bindings.hasBinding()) return@onOwner true
        val next = Binding()
        check(bindings.reserve(next))
        val intent = Intent(MEDIA_STREAM_ACTION)
            .setPackage(GLOBAL_PKG)
            .putExtra(EXTRA_AUTH_TOKEN, token)
            .putExtra(EXTRA_AUTH_PACKAGE, context.packageName)
        val accepted = try {
            context.bindService(intent, next, Context.BIND_AUTO_CREATE)
        } catch (error: Throwable) {
            bindings.release(next)
            runCatching { context.unbindService(next) }
            throw error
        }
        if (!accepted) {
            bindings.release(next)
            runCatching { context.unbindService(next) }
            Log.w(LOG_TAG, "bindService returned false; is Hi Rokid (global) installed and authorized?")
        }
        accepted
    }
fun disconnect() = onOwner {
        val callbacks = activeCallbacks
        val retired = bindings.releaseCurrent()
        activeCallbacks = null
        if (retired != null) {
            runCatching { context.unbindService(retired.binding) }
            if (retired.service != null && callbacks != null) {
                unregisterCallbacks(retired.service, callbacks)
            }
        }
    }
fun refreshDeviceConnection(): Boolean? {
        val expected = bindings.probe() ?: return null
        val connected = tryCall(expected.service) { expected.service.isDeviceConnected } ?: return null
        runOnOwner {
            if (bindings.applyProbe(expected, connected) == true) {
                linkCbk?.onGlassBtConnected(connected)
            }
        }
        return connected
    }
fun hasActiveBinding(): Boolean = bindings.hasBinding()
fun isServiceConnected(): Boolean = bindings.currentService() != null
fun isGlassBtConnected(): Boolean = bindings.isDeviceConnected()
}

internal class PhoneRecovery(val cxrLink:TestLink) {
 var hubEnabled=true;var sppLoopStop=false
 val cxrRecoveryPolicy=CxrRecoveryPolicy()
 val cxrRecoveryHandler=Handler()
 val cxrRecoveryInFlight=AtomicBoolean(false)
 private var cxrRecoveryTick:Runnable?=null
 val executor=Workers()
 fun isCxrUp()=cxrLink.isServiceConnected() && cxrLink.isGlassBtConnected()
 fun startCxrIfTokenAvailable(){cxrLink.connect("test-only")}
 fun log(message:String){}
 fun start(){startCxrRecovery()}
 fun stop(){stopCxrRecovery();hubEnabled=false;sppLoopStop=true;cxrLink.disconnect()}
 fun restart(){hubEnabled=true;sppLoopStop=false;startCxrRecovery()}
 fun currentTick()=checkNotNull(cxrRecoveryTick)
private fun startCxrRecovery(resetBudget: Boolean = false) {
        if (cxrRecoveryTick != null && !resetBudget) return
        stopCxrRecovery()
        val generation = cxrRecoveryPolicy.start()
        val tick = object : Runnable {
            private var exhaustedLogged = false
            private var pendingSinceMs: Long? = null
            private var pendingLogged = false
            override fun run() {
                if (!hubEnabled || sppLoopStop || !cxrRecoveryPolicy.isCurrent(generation)) return
                val link = cxrLink
                val bindingActive = link?.hasActiveBinding() == true
                val connected = link?.isServiceConnected() == true
                val nowMs = Clock.now
                if (isCxrUp()) exhaustedLogged = false
                if (bindingActive && !connected) {
                    val since = pendingSinceMs ?: nowMs.also { pendingSinceMs = it }
                    if (!pendingLogged && nowMs - since >= 30_000L) {
                        pendingLogged = true
                        log("CXR binding still pending; retaining registration")
                    }
                } else {
                    pendingSinceMs = null
                    pendingLogged = false
                }
                when (cxrRecoveryPolicy.next(
                    generation, nowMs,
                    bindingActive, connected, isCxrUp(),
                )) {
                    CxrRecoveryPolicy.Action.CONNECT -> startCxrIfTokenAvailable()
                    CxrRecoveryPolicy.Action.REFRESH -> {
                        if (link != null && cxrRecoveryInFlight.compareAndSet(false, true)) {
                            executor.execute {
                                try {
                                    if (cxrRecoveryPolicy.isCurrent(generation)) link.refreshDeviceConnection()
                                } finally {
                                    cxrRecoveryInFlight.set(false)
                                }
                            }
                        }
                    }
                    CxrRecoveryPolicy.Action.NONE -> Unit
                }
                if (!bindingActive && !connected && cxrRecoveryPolicy.exhausted() && !exhaustedLogged) {
                    exhaustedLogged = true
                    log("CXR automatic bind retry budget exhausted; waiting for explicit start")
                }
                if (cxrRecoveryPolicy.isCurrent(generation)) {
                    cxrRecoveryHandler.postDelayed(this, if (isCxrUp()) 5_000L else 1_000L)
                }
            }
        }
        cxrRecoveryTick = tick
        cxrRecoveryHandler.post(tick)
    }
private fun stopCxrRecovery() {
        cxrRecoveryPolicy.stop()
        cxrRecoveryTick?.let(cxrRecoveryHandler::removeCallbacks)
        cxrRecoveryTick = null
    }
}
fun test(name:String,block:()->Unit){MainQueue.clear();Clock.now=0;block();println("PASS "+name)}
fun bound(value:Boolean=true):TestLink {
 val link=TestLink(Context());link.connect("test-only");link.context.connections.last().onServiceConnected(null,FakeBinder(Native(value)));return link
}
fun main(){
 test("32-second and 180-second pending callback never cancelled"){
  val l=TestLink(Context());l.connect("test-only")
  for(t in listOf(1_000L,30_000L,32_000L,180_000L)){Clock.now=t;l.connect("test-only")}
  check(l.context.binds==1 && l.context.unbinds==0)
  l.context.connections.single().onServiceConnected(null,FakeBinder(Native(true)))
  check(l.isServiceConnected() && l.isGlassBtConnected())
 }
 test("concurrent connect calls create only one binding"){
  val l=TestLink(Context());val gate=CountDownLatch(1)
  val threads=List(12){Thread{gate.await();check(l.connect("test-only"))}.apply{start()}}
  gate.countDown();val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
  while(threads.any{it.isAlive} && System.nanoTime()<deadline){MainQueue.drain();Thread.sleep(1)}
  threads.forEach{it.join(100)};check(threads.none{it.isAlive});check(l.context.binds==1)
 }
 test("ordinary disconnect retains registration and framework reconnect succeeds"){
  val l=bound();val c=l.context.connections.single();val old=l.deviceCallback()
  c.onServiceDisconnected(null);check(l.hasActiveBinding() && !l.isServiceConnected())
  Clock.now=180_000;l.connect("test-only");check(l.context.binds==1 && l.context.unbinds==0)
  c.onServiceConnected(null,FakeBinder(Native(true)));old.onDeviceConnectChanged(false);MainQueue.drain()
  check(l.isGlassBtConnected() && l.registrations==2)
 }
 test("interrupted connect waits for its reservation outcome and restores interrupt"){
  val l=TestLink(Context());val restored=AtomicBoolean(false)
  val thread=Thread{Thread.currentThread().interrupt();check(l.connect("test-only"));restored.set(Thread.currentThread().isInterrupted)}
  thread.start();val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
  while(thread.isAlive && System.nanoTime()<deadline){MainQueue.drain();Thread.sleep(1)}
  thread.join(100);check(!thread.isAlive && restored.get() && l.context.binds==1)
 }
 test("old service epoch is rejected even when framework reuses the same Binder object"){
  val owner=CxrBindingOwner<Any,Any>();val b=Any();val s=Any();check(owner.reserve(b));owner.connected(b,s)
  val old=checkNotNull(owner.probe());owner.transientDisconnect(b);owner.connected(b,s)
  check(!owner.ownsService(old) && owner.applyProbe(old,false)==null)
 }
 test("bindingDied releases once and old service callbacks cannot clear successor"){
  val l=bound();val old=l.context.connections.single();old.onBindingDied(null)
  check(!l.hasActiveBinding() && l.context.unbinds==1)
  l.connect("test-only");l.context.connections.last().onServiceConnected(null,FakeBinder(Native(true)))
  old.onServiceDisconnected(null);old.onBindingDied(null);old.onNullBinding(null)
  check(l.isServiceConnected() && l.isGlassBtConnected() && l.context.unbinds==1)
 }
 test("null callback and rejected bind do not retain an owner"){
  val l=TestLink(Context());l.connect("test-only");l.context.connections.single().onNullBinding(null)
  check(!l.hasActiveBinding() && l.context.unbinds==1)
  l.context.accept=false;check(!l.connect("test-only") && !l.hasActiveBinding())
 }
 test("bind exception clears reservation for an explicit later start"){
  val l=TestLink(Context());l.context.fail=true
  check(runCatching{l.connect("test-only")}.isFailure && !l.hasActiveBinding())
  l.context.fail=false;check(l.connect("test-only"))
 }
 test("queued device callback after stop is ignored"){
  val l=bound();val cb=l.deviceCallback();val count=l.linkCbk!!.bt.size
  cb.onDeviceConnectChanged(false);l.disconnect();MainQueue.drain()
  check(l.linkCbk!!.bt.size==count && !l.hasActiveBinding())
 }
 test("stop and restart reject old connected callback"){
  val l=TestLink(Context());l.connect("test-only");val old=l.context.connections.single();l.disconnect()
  l.connect("test-only");val current=l.context.connections.last();old.onServiceConnected(null,FakeBinder(Native(false)))
  check(!l.isServiceConnected());current.onServiceConnected(null,FakeBinder(Native(true)))
  check(l.isGlassBtConnected() && l.registrations==1)
 }
 test("old poll cannot overwrite newer device callback on the same binding"){
  val l=TestLink(Context());val n=Native(false);l.connect("test-only");l.context.connections.single().onServiceConnected(null,FakeBinder(n))
  val cb=l.deviceCallback();n.beforeRead={cb.onDeviceConnectChanged(true)}
  val worker=Thread{l.refreshDeviceConnection()};worker.start();worker.join(1_000);check(!worker.isAlive)
  MainQueue.drain();check(l.isGlassBtConnected())
 }
 test("old poll and dead-object failure cannot release replacement service"){
  val l=TestLink(Context());val n=Native(false);l.connect("test-only");val old=l.context.connections.single();old.onServiceConnected(null,FakeBinder(n))
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  n.beforeRead={entered.countDown();release.await();throw DeadObjectException()}
  val worker=Thread{l.refreshDeviceConnection()};worker.start();check(entered.await(1,TimeUnit.SECONDS))
  old.onBindingDied(null);l.connect("test-only");l.context.connections.last().onServiceConnected(null,FakeBinder(Native(true)))
  release.countDown();worker.join(1_000);check(!worker.isAlive);MainQueue.drain()
  check(l.isServiceConnected() && l.isGlassBtConnected() && l.context.unbinds==1)
 }
 test("call capturing a retired service before entering tryCall cannot release successor"){
  val l=TestLink(Context());val oldService=Native(false);l.connect("test-only")
  l.context.connections.single().onServiceConnected(null,FakeBinder(oldService))
  l.context.connections.single().onBindingDied(null);l.connect("test-only")
  l.context.connections.last().onServiceConnected(null,FakeBinder(Native(true)))
  oldService.beforeRead={throw DeadObjectException()};check(l.pollCaptured(oldService)==null)
  check(l.isServiceConnected() && l.isGlassBtConnected() && l.context.unbinds==1)
 }
 test("Control refresh runs with live SPP reader and no SPP retry"){
  val l=TestLink(Context());val n=Native(false);l.connect("test-only");l.context.connections.single().onServiceConnected(null,FakeBinder(n))
  val p=PhoneRecovery(l);p.start();MainQueue.drain();p.executor.drain();MainQueue.drain();check(!p.isCxrUp())
  n.value=true;Clock.now=5_000;MainQueue.drain();p.executor.drain();MainQueue.drain()
  check(p.isCxrUp() && l.context.binds==1);p.stop()
 }
 test("pending bind survives independent recovery ticks until delayed callback"){
  val l=TestLink(Context());l.connect("test-only");val c=l.context.connections.single();val p=PhoneRecovery(l);p.start()
  for(t in listOf(0L,30_000L,32_000L,180_000L)){Clock.now=t;MainQueue.drain();p.executor.drain()}
  check(l.context.binds==1 && l.context.unbinds==0)
  c.onServiceConnected(null,FakeBinder(Native(true)));check(p.isCxrUp());p.stop()
 }
 test("three automatic failed retries stop instead of infinite rebind"){
  val l=TestLink(Context());l.context.accept=false;val p=PhoneRecovery(l);p.start()
  for(t in listOf(0L,1_000L,6_000L,30_000L,180_000L)){Clock.now=t;MainQueue.drain();p.executor.drain()}
  check(l.context.binds==3 && p.cxrRecoveryPolicy.exhausted());p.stop()
 }
 test("healthy control does not poll or rebind"){
  val l=TestLink(Context());val n=Native(true);l.connect("test-only");l.context.connections.single().onServiceConnected(null,FakeBinder(n));val reads=n.reads
  val p=PhoneRecovery(l);p.start();MainQueue.drain();Clock.now=60_000;MainQueue.drain();p.executor.drain()
  check(n.reads==reads && l.context.binds==1);p.stop()
 }
 test("stop suppresses queued tick and refresh then restart owns a fresh generation"){
  val l=bound(false);val p=PhoneRecovery(l);p.start();MainQueue.drain();check(p.executor.count()==1)
  val old=p.currentTick();p.stop();p.executor.drain();old.run();MainQueue.drain();check(!l.hasActiveBinding())
  p.restart();MainQueue.drain();check(l.context.binds==2);p.stop()
 }
 test("policy only rearms retry budget after healthy control or explicit lifecycle start"){
  val p=CxrRecoveryPolicy();val g=p.start()
  for(t in listOf(0L,1_000L,6_000L)){check(p.next(g,t,false,false,false)==CxrRecoveryPolicy.Action.CONNECT)}
  check(p.next(g,60_000,false,false,false)==CxrRecoveryPolicy.Action.NONE)
  check(p.next(g,61_000,true,true,true)==CxrRecoveryPolicy.Action.NONE)
  check(p.next(g,62_000,false,false,false)==CxrRecoveryPolicy.Action.CONNECT)
  p.stop();check(p.next(g,90_000,false,false,false)==CxrRecoveryPolicy.Action.NONE)
  val newer=p.start();check(p.next(newer,90_000,false,false,false)==CxrRecoveryPolicy.Action.CONNECT)
 }
}
