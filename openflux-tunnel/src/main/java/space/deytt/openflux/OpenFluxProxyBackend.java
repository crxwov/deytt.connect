package space.deytt.openflux;

import java.util.Objects;

/** One encrypted Cups session, exposed only through authenticated loopback SOCKS5. */
public final class OpenFluxProxyBackend implements AutoCloseable {
    public interface SocketProtector {
        boolean protect(int fd);
        /** Physical-network resolvers. Never use the VPN interface DNS here. */
        default String[] dnsServers() { return new String[] { "1.1.1.1", "8.8.8.8" }; }
    }

    private static final NativeCallDispatcher NATIVE = new NativeCallDispatcher();
    private static boolean libraryLoaded;
    private long handle;
    private final int boundPort;

    private OpenFluxProxyBackend(long handle, int port) { this.handle = handle; this.boundPort = port; }

    public static OpenFluxProxyBackend start(String profileJson, String username, String password, SocketProtector protector) {
        Objects.requireNonNull(profileJson);
        Objects.requireNonNull(username);
        Objects.requireNonNull(password);
        Objects.requireNonNull(protector);
        String[] servers = Objects.requireNonNull(protector.dnsServers());
        StringBuilder dnsBuilder = new StringBuilder();
        for (String server : servers) {
            if (dnsBuilder.length() != 0) dnsBuilder.append(',');
            dnsBuilder.append(Objects.requireNonNull(server));
        }
        String dns = dnsBuilder.toString();
        return NATIVE.call(() -> {
            if (!libraryLoaded) { System.loadLibrary("openflux_jni"); libraryLoaded = true; }
            long handle = nativeStart(profileJson, username, password, dns, protector);
            if (handle == 0) throw new IllegalStateException(nativeError());
            int port = nativePort(handle);
            if (port <= 0) { nativeStop(handle); throw new IllegalStateException("OpenFlux listener unavailable"); }
            return new OpenFluxProxyBackend(handle, port);
        });
    }

    public synchronized int port() { return handle == 0 ? 0 : boundPort; }
    public synchronized boolean isConnected() { return handle != 0 && NATIVE.call(() -> nativeConnected(handle)); }
    public synchronized long bytesSent() { return handle == 0 ? 0 : NATIVE.call(() -> nativeBytesSent(handle)); }
    public synchronized long bytesReceived() { return handle == 0 ? 0 : NATIVE.call(() -> nativeBytesReceived(handle)); }

    @Override public synchronized void close() {
        if (handle == 0) return;
        long current = handle;
        NATIVE.call(() -> { nativeStop(current); return null; });
        handle = 0;
    }

    private static native long nativeStart(String profile, String username, String password, String dns, SocketProtector protector);
    private static native String nativeError();
    private static native int nativePort(long handle);
    private static native boolean nativeConnected(long handle);
    private static native long nativeBytesSent(long handle);
    private static native long nativeBytesReceived(long handle);
    private static native void nativeStop(long handle);
}
