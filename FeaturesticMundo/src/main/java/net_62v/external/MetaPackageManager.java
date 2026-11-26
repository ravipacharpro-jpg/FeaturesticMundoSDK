package net_62v.external;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.featurestic.mundo.core.MundoEngine;

import java.util.ArrayList;
import java.util.List;

public final class MetaPackageManager {
    private MetaPackageManager() {}

    public static void createEmptyUser(String packageName, String userId) {
        int uid = 0;
        try { uid = Integer.parseInt(userId); } catch (Throwable ignored) {}
        MundoEngine.cloneOrLinkHostApp(packageName, uid);
    }

    public static void createEmptyUserById(String packageName, int userId) {
        MundoEngine.cloneOrLinkHostApp(packageName, userId);
    }

    public static void cleanPackageDataAsUser(String packageName, String userId) {
        int uid = 0;
        try { uid = Integer.parseInt(userId); } catch (Throwable ignored) {}
        MundoEngine.removeAppUserData(packageName, uid);
    }

    public static void deleteAllAppCache(String packageName) {
        MundoEngine.removeAppUserData(packageName, 0);
    }

    public static void uninstallAppFully(String packageName) {
        MundoEngine.removeAppUserData(packageName, 0);
    }

    public static boolean isInnerAppInstalled(String packageName) {
        if (MundoEngine.isAppInstalled(packageName, 0)) return true;
        Context ctx = MundoEngine.getHostContext();
        if (ctx != null && packageName != null) {
            try {
                ctx.getPackageManager().getPackageInfo(packageName, 0);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static int[] getInstalledUserId(String packageName) {
        return new int[]{0};
    }

    public static List<PackageInfo> getInstalledPackageInfos() {
        Context ctx = MundoEngine.getHostContext();
        if (ctx == null) return new ArrayList<>();
        try {
            return ctx.getPackageManager().getInstalledPackages(0);
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    public static Intent getLaunchIntent(String packageName) {
        Context ctx = MundoEngine.getHostContext();
        if (ctx == null || packageName == null) return null;
        try {
            return ctx.getPackageManager().getLaunchIntentForPackage(packageName);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
