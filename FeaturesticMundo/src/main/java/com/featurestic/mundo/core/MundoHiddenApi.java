package com.featurestic.mundo.core;

import android.os.Build;

import java.lang.reflect.Method;

/**
 * Self-contained Android 9-16 Hidden API exemption via meta-reflection.
 */
public final class MundoHiddenApi {
    private static volatile boolean sExempted = false;

    private MundoHiddenApi() {}

    public static synchronized void exemptAll() {
        if (sExempted || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            sExempted = true;
            return;
        }
        try {
            if (MundoNative.isLoaded() && MundoNative.nativeExemptHiddenApi()) {
                sExempted = true;
                return;
            }
        } catch (Throwable ignored) {}
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);

            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", new Class[0]);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});

            Object runtime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(runtime, (Object) new String[]{"L"});
            sExempted = true;
        } catch (Throwable ignored) {
        }
    }
}
