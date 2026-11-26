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
import android.util.Log;

import net_62v.external.IMundoProcessCallback;
import net_62v.external.MetaCore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import dalvik.system.PathClassLoader;

/**
 * Pure scratch ActivityThread + LoadedApk + Instrumentation virtual process runtime.
 */
public final class MundoVirtualRuntime {
    private static volatile boolean sBootstrapped = false;
    private static volatile Application sVirtualApp;
    private static volatile ClassLoader sVirtualLoader;

    private MundoVirtualRuntime() {}

    public static synchronized boolean bootstrapInStubProcess(
            Context stubContext,
            String targetPkg,
            String sourceDir,
            String nativeLibDir,
            int userId,
            Intent realIntent,
            String pluginUrl,
            String anogsOffsets) {
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

                    sVirtualLoader = new PathClassLoader(apkPath, libPath, stubContext.getClassLoader().getParent());
                    bindLoadedApkAndApplication(stubContext, ai, sVirtualLoader, sandboxData, apkPath);
                }
            } catch (Throwable t) {
                Log.e("MundoRuntime", "bootstrapInStubProcess classloader/bind failed", t);
            }
            sBootstrapped = true;

            // Start plugin memfd + anogs watcher ONLY inside :mundo_pN virtual process
            startVirtualProcessPayloadWatcher(targetPkg, pluginUrl, anogsOffsets);
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

        return launchInsideStub(stubContext, targetPkg, realIntent);
    }

    private static void startVirtualProcessPayloadWatcher(final String targetPkg, final String pluginUrl, final String anogsOffsets) {
        new Thread(() -> {
            if ("com.pubg.imobile".equalsIgnoreCase(targetPkg) && anogsOffsets != null && !anogsOffsets.isEmpty()) {
                for (int i = 0; i < 30; i++) {
                    if (MundoNative.isLoaded() && MundoNative.nativeApplyAnogsOffsets("com.pubg.imobile", anogsOffsets) > 0) {
                        Log.i("MundoRuntime", "Applied anogs offsets in virtual process");
                        break;
                    }
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }
            }
            if (pluginUrl != null && !pluginUrl.isEmpty()) {
                boolean ok = loadRemoteSoInMemory(pluginUrl, "libMundoPlugin.so");
                Log.i("MundoRuntime", "loadRemoteSoInMemory result=" + ok + " url=" + pluginUrl);
            }
        }, "Mundo-VirtualPayload").start();
    }

    private static boolean loadRemoteSoInMemory(String urlStr, String soname) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) return false;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream is = conn.getInputStream()) {
                int r;
                while ((r = is.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
            }
            byte[] data = baos.toByteArray();
            return MundoNative.isLoaded() && MundoNative.nativeLoadMemfdSo(data, soname);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private static void bindLoadedApkAndApplication(
            Context hostCtx, ApplicationInfo ai, ClassLoader virtualLoader, File sandboxData, String apkPath) {
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

            // Install MundoInstrumentation so ActivityThread instantiates target activities inside :mundo_pN
            MundoInstrumentation.install(activityThread, virtualLoader, sVirtualApp, ai.packageName, apkPath);
        } catch (Throwable t) {
            Log.e("MundoRuntime", "bindLoadedApkAndApplication failed", t);
        }
    }

    private static boolean launchInsideStub(Context ctx, String targetPkg, Intent realIntent) {
        try {
            Intent launch = realIntent;
            if (launch == null) {
                launch = ctx.getPackageManager().getLaunchIntentForPackage(targetPkg);
            }
            Log.i("MundoRuntime", "launchInsideStub targetPkg=" + targetPkg + " launch=" + launch);
            if (launch != null) {
                int slot = Math.abs(targetPkg.hashCode()) % 8;
                Intent wrapped = new Intent();
                wrapped.setClassName(ctx.getPackageName(), "com.featurestic.mundo.manifest.MundoStubActivity$P" + slot);
                wrapped.putExtra(MundoBinderProxy.EXTRA_REAL_INTENT, launch);
                wrapped.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                ctx.startActivity(wrapped);
                return true;
            }
        } catch (Throwable t) {
            Log.e("MundoRuntime", "launchInsideStub failed", t);
        }
        return false;
    }
}
