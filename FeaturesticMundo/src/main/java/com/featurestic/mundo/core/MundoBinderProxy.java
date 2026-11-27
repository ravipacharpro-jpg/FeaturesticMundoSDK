package com.featurestic.mundo.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Pure scratch dynamic Binder Proxy for ActivityManager & PackageManager.
 */
public final class MundoBinderProxy {
    public static final String EXTRA_REAL_INTENT = "mundo_real_intent";
    public static final String EXTRA_TARGET_PKG = "mundo_target_pkg";

    private static volatile boolean sInstalled = false;
    private static volatile String sHostPkg = "";
    private static volatile String sVirtualPkg = "";

    private MundoBinderProxy() {}

    public static synchronized void install(Context context, String virtualPkg) {
        if (context == null) return;
        sHostPkg = context.getPackageName();
        sVirtualPkg = virtualPkg != null ? virtualPkg : "";
        if (sInstalled) return;
        MundoHiddenApi.exemptAll();
        hookActivityManager();
        hookPackageManager();
        sInstalled = true;
    }

    public static void setVirtualPkg(String virtualPkg) {
        sVirtualPkg = virtualPkg != null ? virtualPkg : "";
    }

    private static void hookActivityManager() {
        try {
            Object singleton;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Class<?> amClass = Class.forName("android.app.ActivityManager");
                Field f = amClass.getDeclaredField("IActivityManagerSingleton");
                f.setAccessible(true);
                singleton = f.get(null);
            } else {
                Class<?> amnClass = Class.forName("android.app.ActivityManagerNative");
                Field f = amnClass.getDeclaredField("gDefault");
                f.setAccessible(true);
                singleton = f.get(null);
            }
            if (singleton == null) return;

            Class<?> singletonClass = Class.forName("android.util.Singleton");
            Method getMethod = singletonClass.getDeclaredMethod("get");
            getMethod.setAccessible(true);
            final Object rawAms = getMethod.invoke(singleton);
            if (rawAms == null) return;

            Field instanceField = singletonClass.getDeclaredField("mInstance");
            instanceField.setAccessible(true);

            Class<?> iamInterface = Class.forName("android.app.IActivityManager");
            Object proxy = Proxy.newProxyInstance(
                    iamInterface.getClassLoader(),
                    new Class[]{iamInterface},
                    new AmsInvocationHandler(rawAms)
            );
            instanceField.set(singleton, proxy);
        } catch (Throwable ignored) {
        }
    }

    private static void hookPackageManager() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentAt = atClass.getDeclaredMethod("currentActivityThread");
            currentAt.setAccessible(true);
            Object activityThread = currentAt.invoke(null);
            if (activityThread == null) return;

            Field spmField = atClass.getDeclaredField("sPackageManager");
            spmField.setAccessible(true);
            final Object rawPms = spmField.get(activityThread);
            if (rawPms == null) return;

            Class<?> ipmInterface = Class.forName("android.content.pm.IPackageManager");
            Object proxy = Proxy.newProxyInstance(
                    ipmInterface.getClassLoader(),
                    new Class[]{ipmInterface},
                    new PmsInvocationHandler(rawPms)
            );
            spmField.set(activityThread, proxy);
        } catch (Throwable ignored) {
        }
    }

    private static final class AmsInvocationHandler implements InvocationHandler {
        private final Object base;

        AmsInvocationHandler(Object base) {
            this.base = base;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            // Allow user-registered ServiceInterpreters first
            Map<String, InvocationHandler> custom = MundoEngine.getServiceInterpreters();
            InvocationHandler customAms = custom.get("activity");
            if (customAms != null) {
                return customAms.invoke(base, method, args);
            }

            if (args != null) {
                if ("startActivity".equals(name) || "startActivityAsUser".equals(name)) {
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] instanceof Intent) {
                            Intent orig = (Intent) args[i];
                            ComponentName comp = orig.getComponent();
                            if (comp != null && !sVirtualPkg.isEmpty() && sVirtualPkg.equals(comp.getPackageName())) {
                                int slot = Math.abs(sVirtualPkg.hashCode()) % 8;
                                Intent stub = new Intent();
                                stub.setClassName(sHostPkg, "com.featurestic.mundo.manifest.MundoStubActivity$P" + slot);
                                stub.putExtra(EXTRA_REAL_INTENT, orig);
                                stub.putExtra(EXTRA_TARGET_PKG, sVirtualPkg);
                                args[i] = stub;
                            }
                        } else if (args[i] instanceof String && sVirtualPkg.equals(args[i])) {
                            args[i] = sHostPkg;
                        }
                    }
                } else if ("getContentProvider".equals(name) || "registerReceiver".equals(name)
                        || "broadcastIntent".equals(name) || "broadcastIntentWithFeature".equals(name)
                        || "startService".equals(name) || "bindService".equals(name)
                        || "bindServiceInstance".equals(name) || "bindIsolatedService".equals(name)) {
                    if ("bindService".equals(name) || "bindServiceInstance".equals(name)
                            || "bindIsolatedService".equals(name)) {
                        for (Object arg : args) {
                            if (arg instanceof Intent) {
                                Intent svc = (Intent) arg;
                                String svcPkg = svc.getPackage();
                                if (svcPkg == null && svc.getComponent() != null) {
                                    svcPkg = svc.getComponent().getPackageName();
                                }
                                String action = svc.getAction();
                                if ("com.google.android.gms".equals(svcPkg)
                                        || (action != null && action.startsWith("com.google.android.gms"))) {
                                    return 0;
                                }
                            }
                        }
                    }
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] instanceof String && sVirtualPkg.equals(args[i])) {
                            args[i] = sHostPkg;
                        }
                    }
                }
            }
            return method.invoke(base, args);
        }
    }

    private static final class PmsInvocationHandler implements InvocationHandler {
        private final Object base;

        PmsInvocationHandler(Object base) {
            this.base = base;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Object res = method.invoke(base, args);
            if (!sVirtualPkg.isEmpty()) {
                if ("getPackageInfo".equals(name) && res instanceof PackageInfo) {
                    PackageInfo pi = (PackageInfo) res;
                    if (sVirtualPkg.equals(pi.packageName) && pi.applicationInfo != null) {
                        pi.applicationInfo.dataDir = MundoEngine.obtainAppDataDir(sVirtualPkg, 0).getAbsolutePath();
                    }
                } else if ("getApplicationInfo".equals(name) && res instanceof ApplicationInfo) {
                    ApplicationInfo ai = (ApplicationInfo) res;
                    if (sVirtualPkg.equals(ai.packageName)) {
                        ai.dataDir = MundoEngine.obtainAppDataDir(sVirtualPkg, 0).getAbsolutePath();
                    }
                }
            }
            return res;
        }
    }
}
