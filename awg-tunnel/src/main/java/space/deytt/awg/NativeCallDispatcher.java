package space.deytt.awg;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Keeps this Go runtime off threads that have entered the independent libbox runtime. */
final class NativeCallDispatcher {
    private volatile Thread nativeThread;
    // Deliberately process-lifetime: never return this thread to a shared pool.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "deytt-awg-native");
        thread.setDaemon(true);
        nativeThread = thread;
        return thread;
    });

    <T> T call(Callable<T> action) {
        if (Thread.currentThread() == nativeThread) return invoke(action);
        Future<T> result = executor.submit(action);
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    return result.get();
                } catch (InterruptedException ignored) {
                    // A native start/stop cannot safely be abandoned. Finish it
                    // so its handle reaches the caller, then restore interruption.
                    interrupted = true;
                } catch (ExecutionException error) {
                    throw propagate(error.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static <T> T invoke(Callable<T> action) {
        try {
            return action.call();
        } catch (Throwable error) {
            throw propagate(error);
        }
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof Error) throw (Error) error;
        if (error instanceof RuntimeException) return (RuntimeException) error;
        return new IllegalStateException("AmneziaWG native operation failed", error);
    }
}
