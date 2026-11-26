package com.featurestic.mundo.core;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import net_62v.external.IMundoProcessCallback;
import net_62v.external.MetaCore;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import dalvik.system.PathClassLoader;

/**
 * Pure scratch ActivityThread + LoadedApk + ClassLoader virtual process runtime.
 */
public final class MundoVirtualRuntime {
    private static volatile boolean sBootstrapped = false;
    private static volatile Application sVirtualApp;

    private MundoVirtualRuntime() {}

    public static synchronized boolean bootstrapInStubProcess(
            Context stubContext, String targetPkg, String sourceDir, String nativeLibDir, int userId, Intent realIntent) {
        if (stubContext == null || targetPkg == null || targetPkg.isEmpty()) return false;

        MundoHiddenApi.exemptAll();
        MundoBinderProxy.install(stubContext, targetPkg);

        File sandboxData = MundoEngine.obtainAppDataDir(targetPkg, userId);
        if (!sandboxData.exists()) sandboxData.mkdirs();
        File hostObb = new File(Environment.getExternalStorageDirectory(), "Android/obb/" + targetPkg);

        if (MundoNative.isLoaded()) {
            MundoNative.nativeInit(stubContext.getPackageName(), targetPkg, sandboxData.getAbsolutePath(), hostObb.getAbsolutePath());
            MundoNative.nativeInstallVfsHooks();
        }

        if (!sBootstrapped) {
            try {
                PackageManager pm = stubContext.getPackageManager();
                PackageInfo pi = pm.getPackageInfo(targetPkg, 0);
                ApplicationInfo ai = pi.applicationInfo;
                if (ai != null) {
                    String apkPath = (sourceDir != null && !sourceDir.isEmpty()) ? sourceDir : ai.publicSourceDir;
                    String libPath = (nativeLibDir != null && !nativeLibDir.isEmpty()) ? nativeLibDir : ai.nativeLibraryDir;

                    ClassLoader virtualLoader = new PathClassLoader(apkPath, libPath, stubContext.getClassLoader().getParent());
                    bindLoadedApkAndApplication(stubContext, ai, virtualLoader, sandboxData);
                }
            } catch (Throwable ignored) {
            }
            sBootstrapped = true;
        }

        // Notify IMundoProcessCallback inside the virtual process
        IMundoProcessCallback cb = MetaCore.getProcessLifecycleCallback();
        if (cb != null) {
            try {
                cb.onCreate(targetPkg, targetPkg);
            } catch (Throwable ignored) {}
        }
        for (Object o : MundoEngine.getCallbacks()) {
            if (o instanceof IMundoProcessCallback && o != cb) {
                try {
                    ((IMundoProcessCallback) o).onCreate(targetPkg, targetPkg);
                } catch (Throwable ignored) {}
            }
        }

        return launchTargetActivity(stubContext, targetPkg, realIntent);
    }

    @SuppressWarnings("unchecked")
    private static void bindLoadedApkAndApplication(
            Context hostCtx, ApplicationInfo ai, ClassLoader virtualLoader, File sandboxData) {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentAt = atClass.getDeclaredMethod("currentActivityThread");
            currentAt.setAccessible(true);
            Object activityThread = currentAt.invoke(null);
            if (activityThread == null) return;

            ai.dataDir = sandboxData.getAbsolutePath();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ai.deviceProtectedDataDir = sandboxData.getAbsolutePath();
            }

            // Register virtual LoadedApk in ActivityThread.mPackages
            Class<?> compatClass = Class.forName("android.content.res.CompatibilityInfo");
            Field defaultCompatField = compatClass.getDeclaredField("DEFAULT_COMPATIBILITY_INFO");
            defaultCompatField.setAccessible(true);
            Object defaultCompat = defaultCompatField.get(null);

            Method getPkgNoCheck = atClass.getDeclaredMethod("getPackageInfoNoCheck", ApplicationInfo.class, compatClass);
            getPkgNoCheck.setAccessible(true);
            Object loadedApk = getPkgNoCheck.invoke(activityThread, ai, defaultCompat);
            if (loadedApk != null) {
                Field clField = loadedApk.getClass().getDeclaredField("mClassLoader");
                clField.setAccessible(true);
                clField.set(loadedApk, virtualLoader);

                Field mPackagesField = atClass.getDeclaredField("mPackages");
                mPackagesField.setAccessible(true);
                Map<String, Object> mPackages = (Map<String, Object>) mPackagesField.get(activityThread);
                if (mPackages != null) {
                    mPackages.put(ai.packageName, new java.lang.ref.WeakReference<>(loadedApk));
                }

                // Instantiate virtual Application if declared
                String appClassName = (ai.className != null && !ai.className.isEmpty())
                        ? ai.className : "android.app.Application";
                try {
                    Field instField = atClass.getDeclaredField("mInstrumentation");
                    instField.setAccessible(true);
                    Instrumentation inst = (Instrumentation) instField.get(activityThread);
                    if (inst != null) {
                        sVirtualApp = inst.newApplication(virtualLoader, appClassName, hostCtx);
                        if (sVirtualApp != null) {
                            sVirtualApp.onCreate();
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean launchTargetActivity(Context ctx, String targetPkg, Intent realIntent) {
        try {
            Intent launch = realIntent;
            if (launch == null) {
                launch = ctx.getPackageManager().getLaunchIntentForPackage(targetPkg);
            }
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
