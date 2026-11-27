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
    private static volatile Context sHostContext;

    private MundoVirtualRuntime() {}

    public static Application getVirtualApplication() {
        return sVirtualApp;
    }

    public static ClassLoader getVirtualClassLoader() {
        return sVirtualLoader;
    }

    public static Context getHostContext() {
        return sHostContext;
    }

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
        sHostContext = stubContext.getApplicationContext() != null
                ? stubContext.getApplicationContext() : stubContext;

        MundoHiddenApi.exemptAll();
        MundoBinderProxy.install(stubContext, targetPkg);
        installHCallback();
        MundoBinderProxy.install(stubContext, targetPkg);

        File sandboxData = MundoEngine.obtainAppDataDir(targetPkg, userId);
        if (!sandboxData.exists()) sandboxData.mkdirs();
        for (String sub : new String[]{"databases", "files", "cache", "shared_prefs", "no_backup", "code_cache"}) {
            File subDir = new File(sandboxData, sub);
            if (!subDir.exists()) subDir.mkdirs();
        }
        File hostObb = new File(Environment.getExternalStorageDirectory(), "Android/obb/" + targetPkg);
        try {
            stubContext.getExternalFilesDir(null);
            stubContext.getExternalCacheDir();
            stubContext.getObbDir();
        } catch (Throwable ignored) {}

        if (MundoNative.isLoaded()) {
            MundoNative.nativeInit(stubContext.getPackageName(), targetPkg, sandboxData.getAbsolutePath(), hostObb.getAbsolutePath());
            MundoNative.nativeInstallVfsHooks();
        }
        MundoHiddenApi.exemptAll();
        MundoBinderProxy.install(stubContext, targetPkg);

        if (!sBootstrapped) {
            sBootstrapped = true;
            // Start plugin memfd + anogs watcher ONLY inside :mundo_pN virtual process before app init
            startVirtualProcessPayloadWatcher(targetPkg, pluginUrl, anogsOffsets);

            try {
                PackageManager pm = stubContext.getPackageManager();
                PackageInfo pi = pm.getPackageInfo(targetPkg,
                        PackageManager.GET_META_DATA
                                | PackageManager.GET_SHARED_LIBRARY_FILES
                                | PackageManager.GET_ACTIVITIES);
                MundoBinderProxy.setVirtualPackageInfo(pi);
                ApplicationInfo ai = pi.applicationInfo;
                if (ai != null) {
                    String apkPath = (sourceDir != null && !sourceDir.isEmpty()) ? sourceDir : ai.publicSourceDir;
                    String libPath = (nativeLibDir != null && !nativeLibDir.isEmpty()) ? nativeLibDir : ai.nativeLibraryDir;
                    StringBuilder dexPath = new StringBuilder(apkPath != null ? apkPath : "");
                    if (ai.sharedLibraryFiles != null) {
                        for (String lib : ai.sharedLibraryFiles) {
                            if (lib != null && !lib.isEmpty()) {
                                dexPath.append(File.pathSeparator).append(lib);
                            }
                        }
                    }
                    File legacyHttp = new File("/system/framework/org.apache.http.legacy.jar");
                    if (legacyHttp.exists() && dexPath.indexOf("org.apache.http.legacy.jar") < 0) {
                        dexPath.append(File.pathSeparator).append(legacyHttp.getAbsolutePath());
                    }

                    sVirtualLoader = new PathClassLoader(dexPath.toString(), libPath, stubContext.getClassLoader().getParent());
                    bindLoadedApkAndApplication(stubContext, ai, sVirtualLoader, sandboxData, apkPath);
                }
            } catch (Throwable t) {
                Log.e("MundoRuntime", "bootstrapInStubProcess classloader/bind failed", t);
            }
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

    private static volatile boolean sHCallbackInstalled = false;

    private static void installHCallback() {
        if (sHCallbackInstalled) return;
        sHCallbackInstalled = true;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentAt = atClass.getDeclaredMethod("currentActivityThread");
            currentAt.setAccessible(true);
            Object activityThread = currentAt.invoke(null);
            if (activityThread == null) return;

            Field mhField = atClass.getDeclaredField("mH");
            mhField.setAccessible(true);
            android.os.Handler mh = (android.os.Handler) mhField.get(activityThread);
            if (mh == null) return;

            Field cbField = android.os.Handler.class.getDeclaredField("mCallback");
            cbField.setAccessible(true);
            final android.os.Handler.Callback origCb = (android.os.Handler.Callback) cbField.get(mh);

            cbField.set(mh, (android.os.Handler.Callback) msg -> {
                if (msg != null) {
                    if (msg.what == 159 && msg.obj != null) {
                        com.featurestic.mundo.auth.GoogleSignInTokenHelper.inspectClientTransaction(msg.obj);
                    } else if (msg.what == 108 && msg.obj != null) {
                        inspectLegacySendResult(msg.obj);
                    }
                }
                return origCb != null && origCb.handleMessage(msg);
            });
            Log.i("MundoRuntime", "Installed ActivityThread.mH GoogleSignIn result callback");
        } catch (Throwable t) {
            Log.w("MundoRuntime", "installHCallback failed", t);
        }
    }

    private static void inspectLegacySendResult(Object resultData) {
        try {
            Field resultsField = resultData.getClass().getDeclaredField("results");
            resultsField.setAccessible(true);
            Object listObj = resultsField.get(resultData);
            if (listObj instanceof java.util.List) {
                for (Object ri : (java.util.List<?>) listObj) {
                    if (ri == null) continue;
                    Field dataField = ri.getClass().getDeclaredField("mData");
                    dataField.setAccessible(true);
                    Intent data = (Intent) dataField.get(ri);
                    if (data != null && data.hasExtra(
                            com.featurestic.mundo.auth.GoogleSignInTokenHelper.EXTRA_WEB_ID_TOKEN)) {
                        com.featurestic.mundo.auth.GoogleSignInTokenHelper.enrichSignInResultIntent(data, true);
                    }
                }
            }
        } catch (Throwable ignored) {}
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
                try {
                    Field credField = ApplicationInfo.class.getDeclaredField("credentialProtectedDataDir");
                    credField.setAccessible(true);
                    credField.set(ai, sandboxData.getAbsolutePath());
                } catch (Throwable ignored) {}
            }

            Class<?> compatClass = Class.forName("android.content.res.CompatibilityInfo");
            Field defaultCompatField = compatClass.getDeclaredField("DEFAULT_COMPATIBILITY_INFO");
            defaultCompatField.setAccessible(true);
            Object defaultCompat = defaultCompatField.get(null);

            Method getPkgNoCheck = atClass.getDeclaredMethod("getPackageInfoNoCheck", ApplicationInfo.class, compatClass);
            getPkgNoCheck.setAccessible(true);
            Object loadedApk = getPkgNoCheck.invoke(activityThread, ai, defaultCompat);
            if (loadedApk != null) {
                for (String df : new String[]{"mDataDirFile", "mDeviceProtectedDataDirFile", "mCredentialProtectedDataDirFile"}) {
                    try {
                        Field f = loadedApk.getClass().getDeclaredField(df);
                        f.setAccessible(true);
                        f.set(loadedApk, sandboxData);
                    } catch (Throwable ignored) {}
                }
                try {
                    Field mDataDirField = loadedApk.getClass().getDeclaredField("mDataDir");
                    mDataDirField.setAccessible(true);
                    mDataDirField.set(loadedApk, sandboxData.getAbsolutePath());
                } catch (Throwable ignored) {}
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
                Context appContext = hostCtx;
                try {
                    Class<?> contextImplClass = Class.forName("android.app.ContextImpl");
                    Method createAppContext = contextImplClass.getDeclaredMethod("createAppContext", atClass, loadedApk.getClass());
                    createAppContext.setAccessible(true);
                    Object created = createAppContext.invoke(null, activityThread, loadedApk);
                    if (created instanceof Context) {
                        appContext = (Context) created;
                        Log.i("MundoRuntime", "Created ContextImpl for " + ai.packageName + " pkg=" + appContext.getPackageName());
                    }
                } catch (Throwable t) {
                    Log.e("MundoRuntime", "createAppContext failed, using hostCtx", t);
                }

                try {
                    Field instField = atClass.getDeclaredField("mInstrumentation");
                    instField.setAccessible(true);
                    Instrumentation inst = (Instrumentation) instField.get(activityThread);
                    if (inst != null) {
                        Log.i("MundoRuntime", "Instantiating virtual Application: " + appClassName);
                        sVirtualApp = inst.newApplication(virtualLoader, appClassName, appContext);
                        try {
                            Field mAppField = loadedApk.getClass().getDeclaredField("mApplication");
                            mAppField.setAccessible(true);
                            mAppField.set(loadedApk, sVirtualApp);
                        } catch (Throwable ignored) {}
                        MundoInstrumentation.install(activityThread, virtualLoader, sVirtualApp, hostCtx.getPackageName(), ai.packageName, apkPath);
                        if (sVirtualApp != null) {
                            Log.i("MundoRuntime", "Calling sVirtualApp.onCreate()");
                            sVirtualApp.onCreate();
                            Log.i("MundoRuntime", "sVirtualApp.onCreate() completed");
                            try {
                                Intent testFb = new Intent();
                                testFb.setClassName(ai.packageName, "com.facebook.FacebookActivity");
                                Object ri = sVirtualApp.getPackageManager().resolveActivity(testFb, 0);
                                Log.i("MundoBinder", "SELF-TEST resolveActivity(FacebookActivity)=" + ri);
                            } catch (Throwable t) {
                                Log.e("MundoBinder", "SELF-TEST resolveActivity failed", t);
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.e("MundoRuntime", "newApplication/onCreate failed", t);
                }
            } else {
                MundoInstrumentation.install(activityThread, virtualLoader, sVirtualApp, hostCtx.getPackageName(), ai.packageName, apkPath);
            }
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
