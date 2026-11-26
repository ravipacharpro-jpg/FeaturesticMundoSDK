package com.featurestic.mundo.core;

public final class MundoNative {
    private static volatile boolean sLoaded = false;

    static {
        try {
            System.loadLibrary("MundoEngine");
            sLoaded = true;
        } catch (Throwable ignored) {
            sLoaded = false;
        }
    }

    private MundoNative() {}

    public static boolean isLoaded() {
        return sLoaded;
    }

    public static native void nativeInit(String hostPkg, String activePkg, String sandboxDataDir, String hostObbDir);
    public static native boolean nativeLoadMemfdSo(byte[] soBytes, String soname);
    public static native int nativeApplyAnogsOffsets(String targetPkg, String offsetsCsv);
    public static native void nativeSetStreamproof(boolean enabled);
    public static native boolean nativeIsStreamproofEnabled();
    public static native String nativeGetEngineVersion();
}
