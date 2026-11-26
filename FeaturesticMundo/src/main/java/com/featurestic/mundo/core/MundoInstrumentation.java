package com.featurestic.mundo.core;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;

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
    private final String targetPkg;
    private final String sourceDir;

    public MundoInstrumentation(
            Instrumentation base,
            ClassLoader virtualLoader,
            Application virtualApp,
            String targetPkg,
            String sourceDir) {
        this.base = base;
        this.virtualLoader = virtualLoader;
        this.virtualApp = virtualApp;
        this.targetPkg = targetPkg;
        this.sourceDir = sourceDir;
    }

    public static void install(
            Object activityThread,
            ClassLoader virtualLoader,
            Application virtualApp,
            String targetPkg,
            String sourceDir) {
        try {
            Field instField = activityThread.getClass().getDeclaredField("mInstrumentation");
            instField.setAccessible(true);
            Instrumentation baseInst = (Instrumentation) instField.get(activityThread);
            if (baseInst instanceof MundoInstrumentation) return;
            instField.set(activityThread,
                    new MundoInstrumentation(baseInst, virtualLoader, virtualApp, targetPkg, sourceDir));
        } catch (Throwable ignored) {}
    }

    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (intent != null && intent.hasExtra(MundoBinderProxy.EXTRA_REAL_INTENT)) {
            Intent realIntent = intent.getParcelableExtra(MundoBinderProxy.EXTRA_REAL_INTENT);
            if (realIntent != null && realIntent.getComponent() != null) {
                String realClass = realIntent.getComponent().getClassName();
                try {
                    return (Activity) virtualLoader.loadClass(realClass).newInstance();
                } catch (Throwable ignored) {}
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
            }
        }
        if (MundoStreamproofGuard.isEnabled() && activity != null) {
            MundoStreamproofGuard.applyNow(activity);
        }
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
            Resources hostRes = activity.getResources();
            Resources virtRes = new Resources(am, hostRes.getDisplayMetrics(), hostRes.getConfiguration());
            Field resField = Context.class.getClassLoader() != null
                    ? activity.getBaseContext().getClass().getDeclaredField("mResources") : null;
            if (resField != null) {
                resField.setAccessible(true);
                resField.set(activity.getBaseContext(), virtRes);
            }
        } catch (Throwable ignored) {}
    }

    public ActivityResult execStartActivity(
            Context who, IBinder contextThread, IBinder token, Activity target,
            Intent intent, int requestCode, Bundle options) {
        try {
            Method m = Instrumentation.class.getDeclaredMethod(
                    "execStartActivity",
                    Context.class, IBinder.class, IBinder.class, Activity.class,
                    Intent.class, int.class, Bundle.class);
            m.setAccessible(true);
            return (ActivityResult) m.invoke(base, who, contextThread, token, target, intent, requestCode, options);
        } catch (Throwable t) {
            return null;
        }
    }
}
