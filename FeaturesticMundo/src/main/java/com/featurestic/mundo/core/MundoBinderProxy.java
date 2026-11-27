package com.featurestic.mundo.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
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
    private static volatile PackageInfo sVirtualPackageInfo = null;
    private static volatile ApplicationInfo sVirtualAppInfo = null;
    private static final Map<String, ActivityInfo> sVirtualActivities = new HashMap<>();

    private MundoBinderProxy() {}

    public static String getHostPkg() {
        return sHostPkg;
    }

    public static String getVirtualPkg() {
        return sVirtualPkg;
    }

    public static void setVirtualPackageInfo(PackageInfo pi) {
        if (pi == null) return;
        sVirtualPackageInfo = pi;
        if (pi.applicationInfo != null) {
            sVirtualAppInfo = pi.applicationInfo;
        }
        if (pi.activities != null) {
            synchronized (sVirtualActivities) {
                sVirtualActivities.clear();
                for (ActivityInfo ai : pi.activities) {
                    if (ai != null && ai.name != null) {
                        sVirtualActivities.put(ai.name, ai);
                    }
                }
            }
        }
    }

    public static synchronized void install(Context context, String virtualPkg) {
        if (context == null) return;
        sHostPkg = context.getPackageName();
        sVirtualPkg = virtualPkg != null ? virtualPkg : "";
        if (sInstalled) return;
        MundoHiddenApi.exemptAll();
        hookActivityManager();
        hookActivityTaskManager();
        hookPackageManager(context);
        sInstalled = true;
    }

    private static void hookActivityTaskManager() {
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Field f = atmClass.getDeclaredField("IActivityTaskManagerSingleton");
            f.setAccessible(true);
            Object singleton = f.get(null);
            if (singleton == null) return;

            Class<?> singletonClass = Class.forName("android.util.Singleton");
            Method getMethod = singletonClass.getDeclaredMethod("get");
            getMethod.setAccessible(true);
            final Object rawAtm = getMethod.invoke(singleton);
            if (rawAtm == null) return;

            Field instanceField = singletonClass.getDeclaredField("mInstance");
            instanceField.setAccessible(true);

            Class<?> iatmInterface = Class.forName("android.app.IActivityTaskManager");
            Object proxy = Proxy.newProxyInstance(
                    iatmInterface.getClassLoader(),
                    new Class[]{iatmInterface},
                    new AmsInvocationHandler(rawAtm)
            );
            instanceField.set(singleton, proxy);
        } catch (Throwable ignored) {}
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

    private static void hookPackageManager(Context context) {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method getPmMethod = atClass.getDeclaredMethod("getPackageManager");
            getPmMethod.setAccessible(true);
            Object rawPms = getPmMethod.invoke(null);

            Field spmField = atClass.getDeclaredField("sPackageManager");
            spmField.setAccessible(true);
            if (rawPms == null) {
                rawPms = spmField.get(null);
            }
            if (rawPms == null) {
                Log.w("MundoBinder", "hookPackageManager: rawPms is null");
                return;
            }

            Class<?> ipmInterface = Class.forName("android.content.pm.IPackageManager");
            Object proxy = Proxy.newProxyInstance(
                    ipmInterface.getClassLoader(),
                    new Class[]{ipmInterface},
                    new PmsInvocationHandler(rawPms)
            );
            spmField.set(null, proxy);

            if (context != null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    if (pm != null) {
                        Field mPmField = pm.getClass().getDeclaredField("mPM");
                        mPmField.setAccessible(true);
                        mPmField.set(pm, proxy);
                    }
                } catch (Throwable ignored) {}
            }
            Log.i("MundoBinder", "hookPackageManager: installed");
        } catch (Throwable t) {
            Log.e("MundoBinder", "hookPackageManager failed", t);
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
                } else {
                    if ("startService".equals(name)) {
                        for (Object arg : args) {
                            if (arg instanceof Intent) {
                                Intent svc = (Intent) arg;
                                ComponentName svcComp = svc.getComponent();
                                String svcPkg = svcComp != null ? svcComp.getPackageName() : svc.getPackage();
                                if (!sVirtualPkg.isEmpty() && sVirtualPkg.equals(svcPkg)) {
                                    return svcComp != null ? svcComp : new ComponentName(sVirtualPkg, "");
                                }
                            }
                        }
                    }
                    if ("stopService".equals(name)) {
                        for (Object arg : args) {
                            if (arg instanceof Intent) {
                                Intent svc = (Intent) arg;
                                ComponentName svcComp = svc.getComponent();
                                String svcPkg = svcComp != null ? svcComp.getPackageName() : svc.getPackage();
                                if (!sVirtualPkg.isEmpty() && sVirtualPkg.equals(svcPkg)) {
                                    return 0;
                                }
                            }
                        }
                    }
                    if ("bindService".equals(name) || "bindServiceInstance".equals(name)
                            || "bindIsolatedService".equals(name)) {
                        boolean isGmsBind = false;
                        for (Object arg : args) {
                            if (arg instanceof Intent) {
                                Intent svc = (Intent) arg;
                                String svcPkg = svc.getPackage();
                                if (svcPkg == null && svc.getComponent() != null) {
                                    svcPkg = svc.getComponent().getPackageName();
                                }
                                String action = svc.getAction();
                                if (!sVirtualPkg.isEmpty() && sVirtualPkg.equals(svcPkg)) {
                                    return 0;
                                }
                                if ("com.google.android.gms".equals(svcPkg)
                                        || (action != null && action.startsWith("com.google.android.gms"))) {
                                    isGmsBind = true;
                                }
                            }
                        }
                        if (isGmsBind) {
                            wrapGmsServiceConnectionArg(args);
                        }
                    }
                    if ("getIntentSender".equals(name) || "getIntentSenderWithFeature".equals(name)
                            || "getIntentSenderWithSourceToken".equals(name)) {
                        for (Object arg : args) {
                            if (arg instanceof Intent[]) {
                                Intent[] intents = (Intent[]) arg;
                                for (int j = 0; j < intents.length; j++) {
                                    Intent orig = intents[j];
                                    if (orig != null && orig.getComponent() != null
                                            && !sVirtualPkg.isEmpty()
                                            && sVirtualPkg.equals(orig.getComponent().getPackageName())) {
                                        int slot = Math.abs(sVirtualPkg.hashCode()) % 8;
                                        Intent stub = new Intent();
                                        stub.setClassName(sHostPkg, "com.featurestic.mundo.manifest.MundoStubActivity$P" + slot);
                                        stub.putExtra(EXTRA_REAL_INTENT, orig);
                                        stub.putExtra(EXTRA_TARGET_PKG, sVirtualPkg);
                                        intents[j] = stub;
                                    }
                                }
                            }
                        }
                    }
                    if ("checkPermission".equals(name) || "checkPermissionWithToken".equals(name)) {
                        if (args.length > 0 && args[0] instanceof String) {
                            String perm = (String) args[0];
                            if (android.Manifest.permission.READ_EXTERNAL_STORAGE.equals(perm)
                                    || android.Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(perm)) {
                                return PackageManager.PERMISSION_GRANTED;
                            }
                        }
                    }
                    // Rewrite callingPackage from virtualPkg to hostPkg across all AMS calls
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] instanceof String && sVirtualPkg.equals(args[i])) {
                            args[i] = sHostPkg;
                        }
                    }
                }
            }
            Object res;
            try {
                res = method.invoke(base, args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                throw cause != null ? cause : ite;
            }
            if (("getContentProvider".equals(name) || "getContentProviderExternal".equals(name))
                    && res != null && !sVirtualPkg.isEmpty()) {
                wrapContentProviderHolder(res);
            }
            return res;
        }

        private static void wrapContentProviderHolder(Object holder) {
            try {
                Field provField = holder.getClass().getDeclaredField("provider");
                provField.setAccessible(true);
                final Object rawProv = provField.get(holder);
                if (rawProv == null || Proxy.isProxyClass(rawProv.getClass())) return;
                Class<?> icpClass = Class.forName("android.content.IContentProvider");
                if (!icpClass.isInstance(rawProv)) return;
                Object proxy = Proxy.newProxyInstance(
                        icpClass.getClassLoader(),
                        new Class<?>[]{icpClass},
                        (p, m, mArgs) -> {
                            if (mArgs != null) {
                                for (int i = 0; i < mArgs.length; i++) {
                                    if (mArgs[i] instanceof String && sVirtualPkg.equals(mArgs[i])) {
                                        mArgs[i] = sHostPkg;
                                    } else if (Build.VERSION.SDK_INT >= 31
                                            && mArgs[i] instanceof android.content.AttributionSource) {
                                        fixAttributionSource((android.content.AttributionSource) mArgs[i]);
                                    }
                                }
                            }
                            try {
                                return m.invoke(rawProv, mArgs);
                            } catch (java.lang.reflect.InvocationTargetException ite) {
                                Throwable cause = ite.getCause();
                                throw cause != null ? cause : ite;
                            }
                        });
                provField.set(holder, proxy);
            } catch (Throwable ignored) {}
        }

        private static void fixAttributionSource(android.content.AttributionSource as) {
            if (as == null || sHostPkg.isEmpty()) return;
            try {
                Field stateField = android.content.AttributionSource.class
                        .getDeclaredField("mAttributionSourceState");
                stateField.setAccessible(true);
                Object state = stateField.get(as);
                if (state != null) {
                    Field pkgField = state.getClass().getDeclaredField("packageName");
                    pkgField.setAccessible(true);
                    Object curPkg = pkgField.get(state);
                    if (curPkg instanceof String && sVirtualPkg.equals(curPkg)) {
                        pkgField.set(state, sHostPkg);
                    }
                    Field uidField = state.getClass().getDeclaredField("uid");
                    uidField.setAccessible(true);
                    uidField.setInt(state, android.os.Process.myUid());
                }
            } catch (Throwable ignored) {}
        }

        private static void wrapGmsServiceConnectionArg(Object[] args) {
            if (args == null) return;
            try {
                final Class<?> iscClass = Class.forName("android.app.IServiceConnection");
                for (int i = 0; i < args.length; i++) {
                    if (args[i] != null && iscClass.isInstance(args[i])) {
                        final Object origConn = args[i];
                        final android.os.IBinder[] holder = new android.os.IBinder[1];
                        final Object connProxy = Proxy.newProxyInstance(
                                iscClass.getClassLoader(),
                                new Class<?>[]{iscClass},
                                (p, m, mArgs) -> {
                                    String mName = m.getName();
                                    if ("asBinder".equals(mName)) {
                                        return holder[0];
                                    }
                                    if ("connected".equals(mName) && mArgs != null && mArgs.length >= 2
                                            && mArgs[1] instanceof android.os.IBinder) {
                                        mArgs[1] = com.featurestic.mundo.auth.GmsBrokerCompat.wrap(
                                                (android.os.IBinder) mArgs[1]);
                                    }
                                    return m.invoke(origConn, mArgs);
                                });
                        holder[0] = new android.os.Binder() {
                            @Override
                            public android.os.IInterface queryLocalInterface(String descriptor) {
                                if ("android.app.IServiceConnection".equals(descriptor)) {
                                    return (android.os.IInterface) connProxy;
                                }
                                return super.queryLocalInterface(descriptor);
                            }

                            @Override
                            protected boolean onTransact(
                                    int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                                    throws android.os.RemoteException {
                                if (code == android.os.IBinder.FIRST_CALL_TRANSACTION) {
                                    data.enforceInterface("android.app.IServiceConnection");
                                    ComponentName comp = (data.readInt() != 0)
                                            ? ComponentName.CREATOR.createFromParcel(data) : null;
                                    android.os.IBinder svc = data.readStrongBinder();
                                    boolean dead = data.readInt() != 0;
                                    android.os.IBinder wrapped = com.featurestic.mundo.auth.GmsBrokerCompat.wrap(svc);
                                    try {
                                        Method cm = origConn.getClass().getMethod(
                                                "connected", ComponentName.class, android.os.IBinder.class, boolean.class);
                                        cm.setAccessible(true);
                                        cm.invoke(origConn, comp, wrapped, dead);
                                        return true;
                                    } catch (Throwable t) {
                                        try {
                                            Method cm2 = origConn.getClass().getMethod(
                                                    "connected", ComponentName.class, android.os.IBinder.class);
                                            cm2.setAccessible(true);
                                            cm2.invoke(origConn, comp, wrapped);
                                            return true;
                                        } catch (Throwable ignored) {}
                                    }
                                }
                                return super.onTransact(code, data, reply, flags);
                            }
                        };
                        args[i] = connProxy;
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.w("MundoBinder", "wrapGmsServiceConnectionArg failed", t);
            }
        }
    }

    private static final class PmsInvocationHandler implements InvocationHandler {
        private final Object base;

        PmsInvocationHandler(Object base) {
            this.base = base;
        }

        private static boolean isBlockedSocialPkg(String pkg) {
            return "com.facebook.katana".equals(pkg)
                    || "com.facebook.orca".equals(pkg)
                    || "com.twitter.android".equals(pkg);
        }

        private static ActivityInfo findOrSynthesizeVirtualActivity(ComponentName comp) {
            if (comp == null || sVirtualPkg.isEmpty()) return null;
            String pkg = comp.getPackageName();
            String cls = comp.getClassName();
            if (cls == null || cls.isEmpty()) return null;
            boolean isVirtualTarget = sVirtualPkg.equals(pkg);
            ActivityInfo cached;
            synchronized (sVirtualActivities) {
                cached = sVirtualActivities.get(cls);
            }
            if (cached != null && (isVirtualTarget || sHostPkg.equals(pkg))) {
                return cached;
            }
            if (!isVirtualTarget) return null;
            ActivityInfo ai = new ActivityInfo();
            ai.packageName = sVirtualPkg;
            ai.name = cls;
            ai.applicationInfo = sVirtualAppInfo;
            ai.exported = true;
            ai.enabled = true;
            return ai;
        }

        private static ResolveInfo wrapActivityResolveInfo(ActivityInfo ai) {
            ResolveInfo ri = new ResolveInfo();
            ri.activityInfo = ai;
            ri.match = 0x100000;
            ri.priority = 0;
            ri.preferredOrder = 0;
            return ri;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (("getPackageInfo".equals(name) || "getApplicationInfo".equals(name))
                    && args != null && args.length > 0 && args[0] instanceof String) {
                String qPkg = (String) args[0];
                if (isBlockedSocialPkg(qPkg)) {
                    throw new PackageManager.NameNotFoundException(qPkg);
                }
            }
            if ("resolveIntent".equals(name) && args != null && args.length > 0 && args[0] instanceof Intent) {
                Intent intent = (Intent) args[0];
                ComponentName comp = intent.getComponent();
                String intentPkg = comp != null ? comp.getPackageName() : intent.getPackage();
                if (isBlockedSocialPkg(intentPkg)) {
                    return null;
                }
            }
            if ("getActivityInfo".equals(name) && args != null && args.length > 0 && args[0] instanceof ComponentName) {
                ComponentName comp = (ComponentName) args[0];
                if (isBlockedSocialPkg(comp.getPackageName())) {
                    throw new PackageManager.NameNotFoundException(comp.toString());
                }
                ActivityInfo virt = findOrSynthesizeVirtualActivity(comp);
                if (virt != null) {
                    return virt;
                }
            }
            Object res = null;
            Throwable caught = null;
            try {
                res = method.invoke(base, args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                caught = ite.getCause() != null ? ite.getCause() : ite;
            }
            if ("resolveIntent".equals(name) || "getActivityInfo".equals(name) || "queryIntentActivities".equals(name)) {
                Log.i("MundoBinder", "PMS " + name + " arg0=" + (args != null && args.length > 0 ? args[0] : "null"));
            }
            if ("resolveIntent".equals(name) && res == null && args != null && args.length > 0 && args[0] instanceof Intent) {
                Intent intent = (Intent) args[0];
                ActivityInfo virt = findOrSynthesizeVirtualActivity(intent.getComponent());
                if (virt != null) {
                    Log.i("MundoBinder", "resolveIntent virtual hit: " + intent.getComponent());
                    return wrapActivityResolveInfo(virt);
                }
            }
            if (caught != null) {
                throw caught;
            }
            if (!sVirtualPkg.isEmpty()) {
                if ("getPackageInfo".equals(name) && res instanceof PackageInfo) {
                    PackageInfo pi = (PackageInfo) res;
                    if (sVirtualPkg.equals(pi.packageName) && pi.applicationInfo != null) {
                        pi.applicationInfo.dataDir = MundoEngine.obtainAppDataDir(sVirtualPkg, 0).getAbsolutePath();
                        mergeVirtualMetaData(pi.applicationInfo);
                    } else if (sHostPkg.equals(pi.packageName) && pi.applicationInfo != null) {
                        mergeVirtualMetaData(pi.applicationInfo);
                    }
                } else if ("getApplicationInfo".equals(name) && res instanceof ApplicationInfo) {
                    ApplicationInfo ai = (ApplicationInfo) res;
                    if (sVirtualPkg.equals(ai.packageName)) {
                        ai.dataDir = MundoEngine.obtainAppDataDir(sVirtualPkg, 0).getAbsolutePath();
                        mergeVirtualMetaData(ai);
                    } else if (sHostPkg.equals(ai.packageName)) {
                        mergeVirtualMetaData(ai);
                    }
                }
            }
            return res;
        }

        private static void mergeVirtualMetaData(ApplicationInfo target) {
            if (target == null || sVirtualAppInfo == null || sVirtualAppInfo.metaData == null) return;
            android.os.Bundle merged = new android.os.Bundle(sVirtualAppInfo.metaData);
            if (target.metaData != null) {
                merged.putAll(target.metaData);
            }
            merged.putBoolean("IMSDK_FACEBOOK_LOGIN_USING_WEB", true);
            merged.putBoolean("IMSDK_GOOGLE_CREDENTIALS_MANAGER_ENABLED", false);
            target.metaData = merged;
        }
    }
}
