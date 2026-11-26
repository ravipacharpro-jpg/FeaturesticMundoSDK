package net_62v.external;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import com.featurestic.mundo.core.MundoEngine;

import java.util.ArrayList;
import java.util.List;

public final class MetaActivityManager {
    private MetaActivityManager() {}

    public static void initialize() {
        MundoEngine.preloadNextProcess();
    }

    public static void acquirePreloadNextProcess() {
        MundoEngine.preloadNextProcess();
    }

    public static void launchApp(String packageName) {
        MundoEngine.launchVirtualApp(MundoEngine.getHostContext(), packageName, 0);
    }

    public static void launchApp(String packageName, String userId) {
        int uid = 0;
        try { uid = Integer.parseInt(userId); } catch (Throwable ignored) {}
        MundoEngine.launchVirtualApp(MundoEngine.getHostContext(), packageName, uid);
    }

    public static void launchAppWithSplash(Context context, String packageName) {
        MundoEngine.launchVirtualApp(context, packageName, 0);
    }

    public static void launchIntent(Intent intent) {
        Context ctx = MundoEngine.getHostContext();
        if (ctx != null && intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        }
    }

    public static boolean isAppRunning(String packageName) {
        List<ActivityManager.RunningAppProcessInfo> procs = getRunningProcessInfo(packageName);
        return procs != null && !procs.isEmpty();
    }

    public static void killAppByPkg(String packageName) {
        Context ctx = MundoEngine.getHostContext();
        if (ctx == null || packageName == null) return;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(packageName);
        } catch (Throwable ignored) {}
    }

    public static void killAllApps() {
        // Virtual processes cleanup hook
    }

    public static List<ActivityManager.RunningAppProcessInfo> getRunningProcessInfo(String packageName) {
        List<ActivityManager.RunningAppProcessInfo> out = new ArrayList<>();
        Context ctx = MundoEngine.getHostContext();
        if (ctx == null) return out;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getRunningAppProcesses() != null) {
                for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                    if (info.processName != null && (packageName == null || info.processName.contains(packageName))) {
                        out.add(info);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
