package com.featurestic.mundo.core;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.featurestic.mundo.auth.GoogleSignInTokenHelper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Pure scratch Instrumentation wrapper inside :mundo_pN process.
 * Redirects stub activity instantiation to the target app's real Activity class
 * using the virtual PathClassLoader and attaches virtual Application + Resources.
 */
public final class MundoInstrumentation extends Instrumentation {
    private final Instrumentation base;
    private final ClassLoader virtualLoader;
    private final Application virtualApp;
    private final String hostPkg;
    private final String targetPkg;
    private final String sourceDir;

    public MundoInstrumentation(
            Instrumentation base,
            ClassLoader virtualLoader,
            Application virtualApp,
            String hostPkg,
            String targetPkg,
            String sourceDir) {
        this.base = base;
        this.virtualLoader = virtualLoader;
        this.virtualApp = virtualApp;
        this.hostPkg = hostPkg;
        this.targetPkg = targetPkg;
        this.sourceDir = sourceDir;
    }

    public static void install(
            Object activityThread,
            ClassLoader virtualLoader,
            Application virtualApp,
            String hostPkg,
            String targetPkg,
            String sourceDir) {
        try {
            Field instField = activityThread.getClass().getDeclaredField("mInstrumentation");
            instField.setAccessible(true);
            Instrumentation baseInst = (Instrumentation) instField.get(activityThread);
            if (baseInst instanceof MundoInstrumentation) return;
            instField.set(activityThread,
                    new MundoInstrumentation(baseInst, virtualLoader, virtualApp, hostPkg, targetPkg, sourceDir));
            Log.i("MundoInst", "Installed MundoInstrumentation for " + targetPkg + " host=" + hostPkg);
        } catch (Throwable t) {
            Log.e("MundoInst", "install failed", t);
        }
    }

    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (intent != null && intent.hasExtra(MundoBinderProxy.EXTRA_REAL_INTENT)) {
            Intent realIntent = intent.getParcelableExtra(MundoBinderProxy.EXTRA_REAL_INTENT);
            if (realIntent != null && realIntent.getComponent() != null) {
                String realClass = realIntent.getComponent().getClassName();
                try {
                    Log.i("MundoInst", "newActivity instantiating real class: " + realClass);
                    return (Activity) virtualLoader.loadClass(realClass).newInstance();
                } catch (Throwable t) {
                    Log.e("MundoInst", "newActivity failed for " + realClass, t);
                }
            }
        }
        return base.newActivity(cl, className, intent);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        if (activity != null && activity.getClass().getClassLoader() == virtualLoader) {
            attachVirtualResources(activity);
            if (virtualApp != null) {
                try {
                    Field appField = Activity.class.getDeclaredField("mApplication");
                    appField.setAccessible(true);
                    appField.set(activity, virtualApp);
                } catch (Throwable ignored) {}
                try {
                    Context appBase = virtualApp.getBaseContext();
                    if (appBase != null) {
                        Field baseField = android.content.ContextWrapper.class.getDeclaredField("mBase");
                        baseField.setAccessible(true);
                        // Keep Window/Activity token context intact; update mPackageInfo & mBasePackageName if possible
                        Object origBase = baseField.get(activity);
                        if (origBase != null) {
                            try {
                                Field pkgInfoField = origBase.getClass().getDeclaredField("mPackageInfo");
                                pkgInfoField.setAccessible(true);
                                Object virtPkgInfo = pkgInfoField.get(appBase);
                                if (virtPkgInfo != null) {
                                    pkgInfoField.set(origBase, virtPkgInfo);
                                }
                            } catch (Throwable ignored) {}
                            try {
                                Field basePkgField = origBase.getClass().getDeclaredField("mBasePackageName");
                                basePkgField.setAccessible(true);
                                basePkgField.set(origBase, targetPkg);
                            } catch (Throwable ignored) {}
                            try {
                                Field opPkgField = origBase.getClass().getDeclaredField("mOpPackageName");
                                opPkgField.setAccessible(true);
                                opPkgField.set(origBase, targetPkg);
                            } catch (Throwable ignored) {}
                            try {
                                Field clField = origBase.getClass().getDeclaredField("mClassLoader");
                                clField.setAccessible(true);
                                clField.set(origBase, virtualLoader);
                            } catch (Throwable ignored) {}
                            for (String dirField : new String[]{
                                    "mDatabasesDir", "mFilesDir", "mCacheDir",
                                    "mPreferencesDir", "mNoBackupFilesDir", "mCodeCacheDir"}) {
                                try {
                                    Field df = origBase.getClass().getDeclaredField(dirField);
                                    df.setAccessible(true);
                                    df.set(origBase, null);
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            Intent stubIntent = activity.getIntent();
            if (stubIntent != null && stubIntent.hasExtra(MundoBinderProxy.EXTRA_REAL_INTENT)) {
                Intent realIntent = stubIntent.getParcelableExtra(MundoBinderProxy.EXTRA_REAL_INTENT);
                if (realIntent != null) {
                    activity.setIntent(realIntent);
                    ComponentName cn = realIntent.getComponent();
                    if (cn != null) {
                        try {
                            Field compField = Activity.class.getDeclaredField("mComponent");
                            compField.setAccessible(true);
                            compField.set(activity, cn);
                        } catch (Throwable ignored) {}
                        try {
                            ActivityInfo realAi = activity.getPackageManager()
                                    .getActivityInfo(cn, PackageManager.GET_META_DATA);
                            Field aiField = Activity.class.getDeclaredField("mActivityInfo");
                            aiField.setAccessible(true);
                            aiField.set(activity, realAi);
                            if (realAi.screenOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                                activity.setRequestedOrientation(realAi.screenOrientation);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
        if (MundoStreamproofGuard.isEnabled() && activity != null) {
            MundoStreamproofGuard.applyNow(activity);
        }
        Log.i("MundoInst", "callActivityOnCreate: " + (activity != null ? activity.getClass().getName() : "null"));
        base.callActivityOnCreate(activity, icicle);
        if (MundoNative.isLoaded()) {
            MundoNative.nativeInstallVfsHooks();
        }
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        if (MundoNative.isLoaded()) {
            MundoNative.nativeInstallVfsHooks();
        }
        base.callActivityOnResume(activity);
        if (MundoStreamproofGuard.isEnabled() && activity != null) {
            MundoStreamproofGuard.applyNow(activity);
        }
    }

    private void attachVirtualResources(Activity activity) {
        if (sourceDir == null || sourceDir.isEmpty()) return;
        try {
            AssetManager am = AssetManager.class.newInstance();
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            addAssetPath.invoke(am, sourceDir);
            Resources hostRes = activity.getBaseContext().getResources();
            Resources virtRes = new Resources(am, hostRes.getDisplayMetrics(), hostRes.getConfiguration());

            Field baseResField = activity.getBaseContext().getClass().getDeclaredField("mResources");
            baseResField.setAccessible(true);
            baseResField.set(activity.getBaseContext(), virtRes);

            try {
                Class<?> ctw = android.view.ContextThemeWrapper.class;
                Field actResField = ctw.getDeclaredField("mResources");
                actResField.setAccessible(true);
                actResField.set(activity, virtRes);
                Field actThemeField = ctw.getDeclaredField("mTheme");
                actThemeField.setAccessible(true);
                actThemeField.set(activity, null);
            } catch (Throwable t) {
                Log.e("MundoInst", "ContextThemeWrapper mResources set failed", t);
            }
            Log.i("MundoInst", "Attached virtual resources for " + activity.getClass().getSimpleName());
        } catch (Throwable t) {
            Log.e("MundoInst", "attachVirtualResources failed", t);
        }
    }

    public ActivityResult execStartActivity(
            Context who, IBinder contextThread, IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
        Intent dispatchIntent = intent;
        try {
            if (intent != null && !intent.hasExtra(MundoBinderProxy.EXTRA_REAL_INTENT)) {
                ComponentName cn = intent.getComponent();
                String intentPkg = cn != null ? cn.getPackageName() : intent.getPackage();
                if (targetPkg != null && targetPkg.equals(intentPkg)) {
                    int slot = Math.abs(targetPkg.hashCode()) % 8;
                    Intent wrapped = new Intent();
                    wrapped.setClassName(hostPkg,
                            "com.featurestic.mundo.manifest.MundoStubActivity$P" + slot);
                    wrapped.putExtra(MundoBinderProxy.EXTRA_REAL_INTENT, intent);
                    wrapped.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    dispatchIntent = wrapped;
                    Log.i("MundoInst", "execStartActivity wrapped virtual target: " + cn);
                } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
                    String scheme = intent.getData().getScheme();
                    String host = intent.getData().getHost();
                    if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                            && isOAuthHost(host)) {
                        Intent web = new Intent();
                        web.setClassName(hostPkg, "com.featurestic.mundo.manifest.MundoOAuthWebActivity");
                        web.putExtra("mundo_oauth_url", intent.getDataString());
                        web.putExtra(MundoBinderProxy.EXTRA_TARGET_PKG, targetPkg);
                        dispatchIntent = web;
                        Log.i("MundoInst", "execStartActivity routed OAuth URL to MundoOAuthWebActivity: " + host);
                    }
                } else if (isGoogleSignInIntent(intent, intentPkg, cn)) {
                    GoogleSignInTokenHelper.prepareOutboundSignInIntent(intent, targetPkg, hostPkg);
                    Intent gAuth = new Intent();
                    gAuth.setClassName(hostPkg, "com.featurestic.mundo.manifest.MundoGoogleAuthActivity");
                    gAuth.putExtra("mundo_google_target_pkg", targetPkg);
                    String clientId = GoogleSignInTokenHelper.getLastServerClientId();
                    if (clientId != null && !clientId.isEmpty()) {
                        gAuth.putExtra("mundo_google_server_client_id", clientId);
                    }
                    String scopes = GoogleSignInTokenHelper.getLastRequestedScopesString();
                    if (scopes != null && !scopes.isEmpty()) {
                        gAuth.putExtra("mundo_google_scopes", scopes);
                    }
                    dispatchIntent = gAuth;
                    Log.i("MundoInst", "execStartActivity routed Google Sign-In to MundoGoogleAuthActivity");
                }
            }
        } catch (Throwable t) {
            Log.e("MundoInst", "execStartActivity wrap failed", t);
        }
        try {
            Method m = Instrumentation.class.getDeclaredMethod(
                    "execStartActivity",
                    Context.class, IBinder.class, IBinder.class, Activity.class,
                    Intent.class, int.class, Bundle.class);
            m.setAccessible(true);
            return (ActivityResult) m.invoke(base, who, contextThread, token, target, dispatchIntent, requestCode, options);
        } catch (Throwable t) {
            Log.e("MundoInst", "execStartActivity invoke failed", t);
            return null;
        }
    }

    private static boolean isGoogleSignInIntent(Intent intent, String intentPkg, ComponentName cn) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (action != null && (action.startsWith("com.google.android.gms.auth.")
                || "com.google.android.gms.common.account.CHOOSE_ACCOUNT".equals(action))) {
            return true;
        }
        if ("com.google.android.gms".equals(intentPkg) && cn != null) {
            String cls = cn.getClassName();
            if (cls.contains("SignIn") || cls.contains("Auth")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOAuthHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase();
        return h.contains("facebook.com") || h.contains("fb.com")
                || h.contains("twitter.com") || h.equals("x.com") || h.endsWith(".x.com")
                || h.contains("globh.com") || h.contains("krafton.com")
                || h.contains("discord.com") || h.contains("discordapp.com")
                || h.contains("appleid.apple.com") || h.contains("vk.com")
                || h.contains("accounts.google.com") || h.contains("line.me")
                || h.contains("kakao.com") || h.contains("tiktok.com");
    }
}
