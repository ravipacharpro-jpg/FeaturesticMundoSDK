package com.featurestic.mundo.core;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.util.List;

public final class MundoStreamproofGuard {
    private static volatile boolean sEnabled = false;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MundoStreamproofGuard() {}

    public static void setEnabled(boolean enabled, Activity activity) {
        sEnabled = enabled;
        if (MundoNative.isLoaded()) {
            MundoNative.nativeSetStreamproof(enabled);
        }
        applyNow(activity);
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void applyNow(Activity activity) {
        final boolean secure = sEnabled;
        MAIN.post(() -> {
            if (activity != null && !activity.isFinishing()) {
                Window w = activity.getWindow();
                if (w != null) {
                    // Keep main game window recordable
                    w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    View decor = w.getDecorView();
                    if (decor instanceof ViewGroup) {
                        applyToSecondarySurfaces((ViewGroup) decor, secure, 0);
                    }
                }
            }
            applyToOverlayWindows(secure);
        });
    }

    private static void applyToSecondarySurfaces(ViewGroup group, boolean secure, int depth) {
        if (group == null || depth > 8) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof SurfaceView) {
                try {
                    // Secondary overlay surfaceViews get setSecure(true)
                    ((SurfaceView) child).setSecure(secure);
                } catch (Throwable ignored) {}
            } else if (child instanceof ViewGroup) {
                applyToSecondarySurfaces((ViewGroup) child, secure, depth + 1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyToOverlayWindows(boolean secure) {
        try {
            Class<?> wmgClass = Class.forName("android.view.WindowManagerGlobal");
            Object wmg = wmgClass.getMethod("getInstance").invoke(null);
            Field viewsField = wmgClass.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            List<View> views = (List<View>) viewsField.get(wmg);
            if (views == null) return;
            for (View v : views) {
                if (v == null) continue;
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams wlp = (WindowManager.LayoutParams) lp;
                    if (wlp.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW) {
                        if (secure) {
                            wlp.flags |= WindowManager.LayoutParams.FLAG_SECURE;
                        } else {
                            wlp.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                        }
                        WindowManager wm = (WindowManager) v.getContext().getSystemService(Activity.WINDOW_SERVICE);
                        if (wm != null) {
                            wm.updateViewLayout(v, wlp);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
