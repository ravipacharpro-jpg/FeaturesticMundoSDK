package net_62v.external;

import com.featurestic.mundo.core.MundoEngine;

import java.io.File;

public final class MetaApplicationInstaller {
    private MetaApplicationInstaller() {}

    /**
     * Zero-Copy Host-Linked registration: returns 0 on success, -1 on failure.
     */
    public static int cloneApp(String packageName, String userId) {
        int uid = 0;
        try { uid = Integer.parseInt(userId); } catch (Throwable ignored) {}
        return MundoEngine.cloneOrLinkHostApp(packageName, uid) ? 0 : -1;
    }

    public static int cloneApp(String packageName) {
        return cloneApp(packageName, "0");
    }

    public static int installAppByPath(String apkPath) {
        return MundoEngine.registerApkPath(apkPath, 0) ? 0 : -1;
    }

    public static int installAppByPathAndMove(String apkPath) {
        int res = installAppByPath(apkPath);
        if (res == 0 && apkPath != null) {
            try {
                new File(apkPath).delete();
            } catch (Throwable ignored) {}
        }
        return res;
    }

    public static int installAppsByPath(String[] apkPaths) {
        if (apkPaths == null || apkPaths.length == 0) return -1;
        for (String p : apkPaths) {
            if (installAppByPath(p) != 0) return -1;
        }
        return 0;
    }

    public static int installAppsByPathAndMove(String[] apkPaths) {
        if (apkPaths == null || apkPaths.length == 0) return -1;
        for (String p : apkPaths) {
            if (installAppByPathAndMove(p) != 0) return -1;
        }
        return 0;
    }
}
