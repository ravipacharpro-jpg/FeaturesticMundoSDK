package net_62v.external;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.featurestic.mundo.core.MundoEngine;

import java.lang.reflect.InvocationHandler;

public final class MetaCore {
    private static volatile IMundoProcessCallback sProcessCallback;

    private MetaCore() {}

    public static void attachMetaBase(Context base) {
        MundoEngine.attachBaseContext(base);
    }

    public static Context getHostContext() {
        return MundoEngine.getHostContext();
    }

    public static void registerCoreCallback(Runnable callback) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            callback.run();
            if (sProcessCallback != null) {
                sProcessCallback.onInitialized();
            }
        });
    }

    public static void setProcessLifecycleCallback(IMundoProcessCallback callback) {
        sProcessCallback = callback;
        MundoEngine.registerCallback(callback);
        if (callback != null) {
            callback.onInitialized();
        }
    }

    public static IMundoProcessCallback getProcessLifecycleCallback() {
        return sProcessCallback;
    }

    public static void addServiceInterpreter(String serviceName, String interfaceName, InvocationHandler handler) {
        MundoEngine.addServiceInterpreter(serviceName + ":" + interfaceName, handler);
    }

    public static void addServiceInterpreter(String serviceName, InvocationHandler handler) {
        MundoEngine.addServiceInterpreter(serviceName, handler);
    }
}
