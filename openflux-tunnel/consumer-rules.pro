# JNI resolves these names and the protector callback by their public ABI.
-keep class space.deytt.openflux.OpenFluxProxyBackend { *; }
-keep interface space.deytt.openflux.OpenFluxProxyBackend$SocketProtector { *; }
-keepclassmembers class * implements space.deytt.openflux.OpenFluxProxyBackend$SocketProtector {
    public boolean protect(int);
    public java.lang.String[] dnsServers();
}
