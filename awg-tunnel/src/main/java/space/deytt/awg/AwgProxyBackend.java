package space.deytt.awg;

import android.text.TextUtils;
import java.util.Objects;

/** A local authenticated SOCKS transport backed by AmneziaWG, without an Android TUN. */
public final class AwgProxyBackend implements AutoCloseable {
    public interface SocketProtector {
        boolean protect(int fd);
    }

    private static final NativeCallDispatcher NATIVE = new NativeCallDispatcher();
    // Read and written only on NATIVE's permanent thread. Loading here is as
    // important as native method dispatch: dlopen initializes the Go runtime.
    private static boolean libraryLoaded;

    private long handle;
    private final int port;

    private AwgProxyBackend(long handle, int port) {
        this.handle = handle;
        this.port = port;
    }

    public static AwgProxyBackend start(String userspaceConfig, String[] localAddresses,
            String[] dnsServers, int mtu, String username, String password, SocketProtector protector) {
        Objects.requireNonNull(userspaceConfig);
        Objects.requireNonNull(localAddresses);
        Objects.requireNonNull(dnsServers);
        Objects.requireNonNull(username);
        Objects.requireNonNull(password);
        Objects.requireNonNull(protector);
        String addresses = TextUtils.join(",", localAddresses);
        String dns = TextUtils.join(",", dnsServers);
        return NATIVE.call(() -> {
            if (!libraryLoaded) {
                System.loadLibrary("deytt-awg");
                libraryLoaded = true;
            }
            long handle = nativeStart(userspaceConfig, addresses, dns, mtu,
                    username, password, protector);
            if (handle == 0) throw new IllegalStateException("AmneziaWG proxy activation failed");
            int port = nativePort(handle);
            if (port <= 0) {
                nativeStop(handle);
                throw new IllegalStateException("AmneziaWG proxy listener unavailable");
            }
            return new AwgProxyBackend(handle, port);
        });
    }

    public int getPort() { return port; }

    @Override public synchronized void close() {
        if (handle != 0) {
            long current = handle;
            NATIVE.call(() -> { nativeStop(current); return null; });
            handle = 0;
        }
    }

    private static native long nativeStart(String settings, String addresses, String dns,
            int mtu, String username, String password, SocketProtector protector);
    private static native int nativePort(long handle);
    private static native void nativeStop(long handle);
}
