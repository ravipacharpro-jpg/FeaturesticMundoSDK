package net_62v.external;

import com.featurestic.mundo.core.MundoEngine;

import java.io.File;

public final class MetaStorageManager {
    private MetaStorageManager() {}

    public static File obtainAppDataDir(String packageName, String userId) {
        int uid = 0;
        try { uid = Integer.parseInt(userId); } catch (Throwable ignored) {}
        return MundoEngine.obtainAppDataDir(packageName, uid);
    }

    public static File obtainAppDataDir(String packageName, int userId) {
        return MundoEngine.obtainAppDataDir(packageName, userId);
    }

    public static String obtainAppExternalStorageDir(String packageName) {
        return MundoEngine.obtainAppExternalStorageDir(packageName, 0).getAbsolutePath();
    }

    public static void setExternalRootDirectory(String path) {
        MundoEngine.setExternalRootDirectory(path);
    }
}
