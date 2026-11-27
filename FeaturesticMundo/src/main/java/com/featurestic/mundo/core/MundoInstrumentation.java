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
    }

    @Override
    public void callActivityOnResume(Activity activity) {
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
}
