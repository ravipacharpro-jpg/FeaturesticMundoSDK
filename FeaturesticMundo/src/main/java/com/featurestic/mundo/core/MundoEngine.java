package com.featurestic.mundo.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;

import com.featurestic.mundo.manifest.MundoStubActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MundoEngine {
    private static final String PREF_MUNDO = "featurestic_mundo_registry";
    private static final String PREF_INSTALLED = "installed_packages";

    private static volatile Context sHostContext;
    private static volatile String sExternalRootDir = "";
    private static volatile String sActivePackage = "";
    private static volatile String sRemoteServerUrl = "https://featurestic-box-panel.vercel.app/api/public/connect";
    private static volatile boolean sActivated = false;
    private static volatile String sActivationMsg = "Not activated";
    private static volatile String sPluginUrl = "";
    private static volatile String sSocu64Url = "";
    private static volatile String sAnogsOffsets = "";
    private static volatile String sAnogsTargetPkg = "com.pubg.imobile";

    private static final Map<String, VirtualAppRecord> APP_REGISTRY = new ConcurrentHashMap<>();
    private static final Map<String, InvocationHandler> SERVICE_INTERPRETERS = new ConcurrentHashMap<>();
    private static final List<Object> PROCESS_CALLBACKS = Collections.synchronizedList(new ArrayList<>());

    public static final class VirtualAppRecord {
        public final String packageName;
        public final String sourceDir;
        public final String nativeLibDir;
        public final int userId;
        public final boolean hostLinked;

        public VirtualAppRecord(String packageName, String sourceDir, String nativeLibDir, int userId, boolean hostLinked) {
            this.packageName = packageName;
            this.sourceDir = sourceDir;
            this.nativeLibDir = nativeLibDir;
            this.userId = userId;
            this.hostLinked = hostLinked;
        }
    }

    private MundoEngine() {}

    public static void attachBaseContext(Context base) {
        if (base == null) return;
        sHostContext = base.getApplicationContext() != null ? base.getApplicationContext() : base;
        restoreInstalledApps();
    }

    public static Context getHostContext() {
        return sHostContext;
    }

    public static void addServiceInterpreter(String serviceName, InvocationHandler handler) {
        if (serviceName != null && handler != null) {
            SERVICE_INTERPRETERS.put(serviceName, handler);
        }
    }

    public static Map<String, InvocationHandler> getServiceInterpreters() {
        return new HashMap<>(SERVICE_INTERPRETERS);
    }

    public static void registerCallback(Object cb) {
        if (cb != null && !PROCESS_CALLBACKS.contains(cb)) {
            PROCESS_CALLBACKS.add(cb);
        }
    }

    public static List<Object> getCallbacks() {
        synchronized (PROCESS_CALLBACKS) {
            return new ArrayList<>(PROCESS_CALLBACKS);
        }
    }

    public static void setRemoteServerUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            sRemoteServerUrl = url.trim();
        }
    }

    public static String getRemoteServerUrl() {
        return sRemoteServerUrl;
    }

    public static void setActivationResult(boolean ok, String message, String pluginUrl, String socUrl, String anogsTarget, String anogsOffsets) {
        sActivated = ok;
        sActivationMsg = message != null ? message : "";
        sPluginUrl = pluginUrl != null ? pluginUrl.trim() : "";
        sSocu64Url = socUrl != null ? socUrl.trim() : "";
        sAnogsTargetPkg = (anogsTarget != null && !anogsTarget.trim().isEmpty()) ? anogsTarget.trim() : "com.pubg.imobile";
        // Strict lock: only retain offsets for com.pubg.imobile
        sAnogsOffsets = "com.pubg.imobile".equalsIgnoreCase(sAnogsTargetPkg) && anogsOffsets != null ? anogsOffsets.trim() : "";
    }

    public static boolean isActivated() {
        return sActivated;
    }

    public static String getActivationMessage() {
        return sActivationMsg;
    }

    public static void setExternalRootDirectory(String path) {
        sExternalRootDir = path != null ? path.trim() : "";
    }

    /**
     * Zero-Copy Host-Linked App Registration:
     * Links directly to host APK sourceDir & nativeLibDir without duplicating gigabytes of APK data.
     */
    public static boolean cloneOrLinkHostApp(String packageName, int userId) {
        Context ctx = sHostContext;
        if (ctx == null || packageName == null || packageName.trim().isEmpty()) return false;
        String pkg = packageName.trim();
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            if (pi == null || pi.applicationInfo == null) return false;
            ApplicationInfo ai = pi.applicationInfo;
            VirtualAppRecord rec = new VirtualAppRecord(pkg, ai.publicSourceDir, ai.nativeLibraryDir, userId, true);
            APP_REGISTRY.put(keyFor(pkg, userId), rec);
            persistInstalledApp(pkg, userId);
            ensureSandboxDirs(pkg, userId);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerApkPath(String apkPath, int userId) {
        Context ctx = sHostContext;
        if (ctx == null || apkPath == null || apkPath.trim().isEmpty()) return false;
        File f = new File(apkPath.trim());
        if (!f.exists()) return false;
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null || pi.packageName == null) return false;
            String pkg = pi.packageName;
            VirtualAppRecord rec = new VirtualAppRecord(pkg, f.getAbsolutePath(), "", userId, false);
            APP_REGISTRY.put(keyFor(pkg, userId), rec);
            persistInstalledApp(pkg, userId);
            ensureSandboxDirs(pkg, userId);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isAppInstalled(String packageName, int userId) {
        if (packageName == null) return false;
        return APP_REGISTRY.containsKey(keyFor(packageName.trim(), userId));
    }

    public static void removeAppUserData(String packageName, int userId) {
        if (packageName == null) return;
        File dir = obtainAppDataDir(packageName, userId);
        deleteRecursive(dir);
        ensureSandboxDirs(packageName, userId);
    }

    public static File obtainAppDataDir(String packageName, int userId) {
        Context ctx = sHostContext;
        File root = ctx != null ? new File(ctx.getFilesDir(), "mundo_sandbox") : new File("/data/local/tmp/mundo_sandbox");
        return new File(root, "user_" + userId + "/" + packageName);
    }

    public static File obtainAppExternalStorageDir(String packageName, int userId) {
        if (!sExternalRootDir.isEmpty()) {
            return new File(sExternalRootDir, "user_" + userId + "/" + packageName);
        }
        File ext = Environment.getExternalStorageDirectory();
        return new File(ext, "Android/data/" + packageName);
    }

    public static void preloadNextProcess() {
        Context ctx = sHostContext;
        if (ctx == null) return;
        try {
            Intent warm = new Intent(ctx, MundoStubActivity.P0.class);
            warm.putExtra("mundo_preload_only", true);
            warm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (Throwable ignored) {}
    }

    public static boolean launchVirtualApp(Context context, String packageName, int userId) {
        Context ctx = context != null ? context : sHostContext;
        if (ctx == null || packageName == null || packageName.trim().isEmpty()) return false;
        String pkg = packageName.trim();
        sActivePackage = pkg;

        if (!isAppInstalled(pkg, userId)) {
            cloneOrLinkHostApp(pkg, userId);
        }

        VirtualAppRecord rec = APP_REGISTRY.get(keyFor(pkg, userId));
        if (rec == null) return false;

        // Launch inside isolated :mundo_pN stub process with Binder + ActivityThread + PLT VFS hooks.
        // Remote .so memfd loading and anogs offsets are deferred to the :mundo_pN process.
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent realIntent = pm.getLaunchIntentForPackage(pkg);
            int slot = Math.abs(pkg.hashCode()) % 8;
            Class<?> stubCls = Class.forName("com.featurestic.mundo.manifest.MundoStubActivity$P" + slot);
            Intent stub = new Intent(ctx, stubCls);
            stub.putExtra(MundoBinderProxy.EXTRA_TARGET_PKG, pkg);
            stub.putExtra("mundo_source_dir", rec.sourceDir);
            stub.putExtra("mundo_native_lib_dir", rec.nativeLibDir);
            stub.putExtra("mundo_user_id", userId);
            stub.putExtra("mundo_plugin_url", sPluginUrl);
            stub.putExtra("mundo_anogs_offsets", sAnogsOffsets);
            if (realIntent != null) {
                stub.putExtra(MundoBinderProxy.EXTRA_REAL_INTENT, realIntent);
            }
            stub.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(stub);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
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

    private static String keyFor(String pkg, int userId) {
        return userId + ":" + pkg;
    }

    private static void ensureSandboxDirs(String pkg, int userId) {
        File d = obtainAppDataDir(pkg, userId);
        if (!d.exists()) d.mkdirs();
    }

    private static void persistInstalledApp(String pkg, int userId) {
        if (sHostContext == null) return;
        SharedPreferences sp = sHostContext.getSharedPreferences(PREF_MUNDO, Context.MODE_PRIVATE);
        String cur = sp.getString(PREF_INSTALLED, "");
        String entry = keyFor(pkg, userId);
        if (!cur.contains(entry)) {
            sp.edit().putString(PREF_INSTALLED, cur.isEmpty() ? entry : (cur + "," + entry)).apply();
        }
    }

    private static void restoreInstalledApps() {
        if (sHostContext == null) return;
        SharedPreferences sp = sHostContext.getSharedPreferences(PREF_MUNDO, Context.MODE_PRIVATE);
        String cur = sp.getString(PREF_INSTALLED, "");
        if (cur.isEmpty()) return;
        for (String item : cur.split(",")) {
            String[] parts = item.split(":", 2);
            if (parts.length == 2) {
                try {
                    int uid = Integer.parseInt(parts[0]);
                    cloneOrLinkHostApp(parts[1], uid);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }
}
