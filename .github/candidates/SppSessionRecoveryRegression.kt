package com.buildground.spprecovery

import com.anezium.rokidbus.glasses.SppSessionOwner as GlassOwner
import com.anezium.rokidbus.phone.SppClientSessionOwner as PhoneOwner
import com.buildground.baseline.phone.SppClientSessionOwner as OldPhoneOwner
import java.util.Collections
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

private class Session(val value: Int)
private fun CountDownLatch.ready() = check(await(3, TimeUnit.SECONDS)) { "Latch did not complete" }
private var passed = 0
private fun test(name: String, body: () -> Unit) { body(); passed++; println("PASS $name") }

fun main() {
    test("baseline late connect publication reproduced") {
        val old = OldPhoneOwner<Session> { }
        old.clearCurrent() // stop/destroy currently clears only published sessions
        val late = Session(1)
        old.install(late)
        check(old.current() === late)
        old.clearCurrent()
    }
    test("blocked Glasses callback cannot block ownership or new reader admission") {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val states=Collections.synchronizedList(mutableListOf<Boolean>())
        val owner=GlassOwner<Session>({}, { up -> states.add(up); if(up){entered.countDown();release.await()} })
        val a=Session(1);val b=Session(2)
        try {
            check(owner.install(a));entered.ready()
            check(owner.install(b));check(owner.current() === b)
            check(!owner.clearIfCurrent(a));check(owner.clearIfCurrent(b));check(owner.current()==null)
        } finally {release.countDown()}
        owner.awaitIdle();check(states.last()==false);owner.close()
    }
    test("blocked retired socket cannot hold replacement reader or later installation") {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val owner=GlassOwner<Session>({ old -> if(old.value==1){entered.countDown();release.await()} }, {})
        try {
            check(owner.install(Session(1)));val b=Session(2);check(owner.install(b));entered.ready()
            check(owner.isCurrent(b));val c=Session(3);check(owner.install(c));check(owner.isCurrent(c))
        } finally {release.countDown();owner.close()}
    }
    test("permanently blocked native closes have bounded retirement capacity") {
        val entered=CountDownLatch(2);val release=CountDownLatch(1)
        val owner=GlassOwner<Session>({entered.countDown();release.await()}, {})
        try {
            check(owner.install(Session(1)));check(owner.install(Session(2)));val c=Session(3)
            check(owner.install(c));entered.ready();check(!owner.install(Session(4)));check(owner.isCurrent(c))
        } finally {release.countDown();owner.close()}
    }
    test("callback exception cannot strand state or future notifications") {
        val down=CountDownLatch(1)
        val owner=GlassOwner<Session>({}, {up -> if(up) throw IllegalStateException("injected") else down.countDown()})
        val a=Session(1);check(owner.install(a));owner.awaitIdle();check(owner.clearIfCurrent(a));down.ready();owner.close()
    }
    test("native close exception does not block successor state") {
        val seen=CountDownLatch(1)
        val owner=GlassOwner<Session>({seen.countDown();throw IllegalStateException("injected")}, {})
        try {check(owner.install(Session(1)));val b=Session(2);check(owner.install(b));seen.ready();check(owner.isCurrent(b))}
        finally {owner.close()}
    }
    test("reentrant clear from callback converges without deadlock") {
        val down=CountDownLatch(1);lateinit var owner: GlassOwner<Session>
        owner=GlassOwner({}, {up -> if(up) owner.current()?.let{owner.clearIfCurrent(it)} else down.countDown()})
        check(owner.install(Session(1)));down.ready();check(owner.current()==null);owner.close()
    }
    test("duplicate installation of identical owner does not retire it") {
        val retired=AtomicInteger();val owner=GlassOwner<Session>({retired.incrementAndGet()}, {})
        val a=Session(1);check(owner.install(a));check(owner.install(a));owner.awaitIdle();check(retired.get()==0);owner.close()
    }
    test("stale Glasses read is ineligible after replacement") {
        val owner=GlassOwner<Session>({}, {});val a=Session(1);val b=Session(1)
        try {check(owner.install(a));check(owner.install(b));check(!owner.isCurrent(a));check(owner.isCurrent(b));check(!owner.clearIfCurrent(a))}
        finally {owner.close()}
    }
    test("Glasses stop returns even if callback is blocked and prevents resurrection") {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val down=CountDownLatch(1)
        val owner=GlassOwner<Session>({}, {up -> if(up){entered.countDown();release.await()}else down.countDown()})
        try {check(owner.install(Session(1)));entered.ready();owner.close();check(owner.current()==null);check(!owner.install(Session(2)))}
        finally {release.countDown()}
        down.ready()
    }
    test("installation preserves caller interrupt status without waiting on future") {
        val owner=GlassOwner<Session>({}, {});val result=ArrayBlockingQueue<Boolean>(1)
        // put is interruptible: use a non-blocking offer in the interrupted thread.
        val safe=Thread {Thread.currentThread().interrupt();result.offer(owner.install(Session(1)) && Thread.currentThread().isInterrupted)}
        safe.start();safe.join(3000);check(result.poll(1,TimeUnit.SECONDS)==true);owner.close()
    }
    test("Phone rejects connect returning after shutdown") {
        val owner=PhoneOwner<Session>{};val ticket=owner.beginAttempt();owner.shutdown()
        check(!owner.install(Session(1),ticket));check(owner.current()==null);check(owner.beginAttempt()==null)
    }
    test("Phone pause/resume rejects previous connect generation") {
        val owner=PhoneOwner<Session>{};val old=owner.beginAttempt();owner.setEnabled(false);check(owner.beginAttempt()==null)
        owner.setEnabled(true);check(!owner.install(Session(1),old));check(owner.install(Session(2),owner.beginAttempt()));owner.shutdown()
    }
    test("Phone explicit clear invalidates pending connect but permits fresh retry") {
        val owner=PhoneOwner<Session>{};val old=owner.beginAttempt();owner.clearCurrent()
        check(!owner.install(Session(1),old));check(owner.install(Session(2),owner.beginAttempt()));owner.shutdown()
    }
    test("Phone shutdown is permanent for this service instance") {
        val owner=PhoneOwner<Session>{};owner.shutdown();owner.setEnabled(true)
        check(owner.beginAttempt()==null);check(!owner.install(Session(1)))
    }
    test("Phone lifecycle fence is applied before possibly blocking native close") {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val owner=PhoneOwner<Session>{entered.countDown();release.await()};val ticket=owner.beginAttempt()
        check(owner.install(Session(1),ticket));val thread=Thread{owner.shutdown()};thread.start()
        try {entered.ready();check(owner.current()==null);check(owner.beginAttempt()==null);check(!owner.install(Session(2),ticket))}
        finally {release.countDown();thread.join(3000)}
    }
    test("Phone stale writer cannot retire successor and stale reader is ineligible") {
        val owner=PhoneOwner<Session>{};val a=Session(1);val b=Session(1)
        check(owner.install(a));check(owner.install(b));check(!owner.retire(a));check(owner.current()===b);check(!owner.isCurrent(a));owner.shutdown()
    }
    test("concurrent shutdown and delayed publication cannot leave active Phone session") {
        repeat(50) {
            val owner=PhoneOwner<Session>{};val ticket=owner.beginAttempt();val gate=CountDownLatch(1)
            val t=Thread{gate.await();owner.install(Session(1),ticket)};t.start();gate.countDown();owner.shutdown();t.join(3000)
            check(owner.current()==null)
        }
    }
    test("Phone pause preserves current transport until ordered cleanup") {
        val closed=AtomicInteger();val owner=PhoneOwner<Session>{closed.incrementAndGet()}
        val a=Session(1);check(owner.install(a));val ticket=owner.beginAttempt()
        owner.setEnabled(false);check(owner.current()===a);check(closed.get()==0)
        check(!owner.install(Session(2),ticket));owner.clearCurrent();check(closed.get()==1)
    }
    test("Phone permanent stop fences publication before ordered transport cleanup") {
        val closed=AtomicInteger();val owner=PhoneOwner<Session>{closed.incrementAndGet()}
        val a=Session(1);check(owner.install(a));val ticket=owner.beginAttempt()
        owner.stopAccepting();owner.setEnabled(true);check(owner.current()===a);check(closed.get()==0)
        check(owner.beginAttempt()==null);check(!owner.install(Session(2),ticket))
        owner.clearCurrent();check(owner.current()==null);check(closed.get()==1)
    }
    println("TOTAL $passed PASS; no Android/Bluetooth/device execution")
}
