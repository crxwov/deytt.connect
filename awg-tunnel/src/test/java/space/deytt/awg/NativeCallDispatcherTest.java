package space.deytt.awg;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class NativeCallDispatcherTest {
    @Test public void keepsAllCallsOnOneDedicatedThread() throws Exception {
        NativeCallDispatcher dispatcher = new NativeCallDispatcher();
        Thread nativeThread = dispatcher.call(Thread::currentThread);
        assertNotSame(Thread.currentThread(), nativeThread);
        assertEquals("deytt-awg-native", nativeThread.getName());
        AtomicReference<Thread> fromOtherCaller = new AtomicReference<>();
        Thread caller = new Thread(() -> fromOtherCaller.set(dispatcher.call(Thread::currentThread)));
        caller.start(); caller.join(2000);
        assertFalse(caller.isAlive());
        assertSame(nativeThread, fromOtherCaller.get());
        assertSame(nativeThread, dispatcher.call(() -> dispatcher.call(Thread::currentThread)));
    }

    @Test public void preservesErrorsAndWorkerAfterFailure() {
        NativeCallDispatcher dispatcher = new NativeCallDispatcher();
        Thread nativeThread = dispatcher.call(Thread::currentThread);
        IllegalStateException failure = new IllegalStateException("expected test error");
        try { dispatcher.call(() -> { throw failure; }); fail("exception lost"); }
        catch (IllegalStateException result) { assertSame(failure, result); }
        LinkageError linkage = new LinkageError("expected load error");
        try { dispatcher.call(() -> { throw linkage; }); fail("error lost"); }
        catch (LinkageError result) { assertSame(linkage, result); }
        assertSame(nativeThread, dispatcher.call(Thread::currentThread));
    }

    @Test public void interruptionWaitsForHandleAndRestoresFlag() throws Exception {
        NativeCallDispatcher dispatcher = new NativeCallDispatcher();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Integer> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            result.set(dispatcher.call(() -> { entered.countDown(); finish.await(); return 42; }));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        caller.interrupt();
        assertNull(result.get());
        finish.countDown(); caller.join(2000);
        assertFalse(caller.isAlive());
        assertEquals(Integer.valueOf(42), result.get());
        assertTrue(interrupted.get());
    }
}
