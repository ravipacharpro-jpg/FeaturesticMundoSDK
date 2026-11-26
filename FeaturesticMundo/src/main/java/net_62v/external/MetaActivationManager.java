package net_62v.external;

import android.content.Context;
import android.provider.Settings;

import com.featurestic.mundo.core.MundoEngine;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class MetaActivationManager {
    private MetaActivationManager() {}

    public static void changeRemoteServerUrl(String url) {
        MundoEngine.setRemoteServerUrl(url);
    }

    public static boolean getActivationStatus() {
        return MundoEngine.isActivated();
    }

    public static String getActivationMessage() {
        return MundoEngine.getActivationMessage();
    }

    public static void activateSdk(String licenseKey) {
        if (licenseKey == null || licenseKey.trim().isEmpty()) {
            MundoEngine.setActivationResult(false, "License key is empty", "", "", "com.pubg.imobile", "");
            return;
        }
        final String key = licenseKey.trim();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                Context ctx = MundoEngine.getHostContext();
                String pkg = ctx != null ? ctx.getPackageName() : "com.rdx.loader";
                String serial = "MUNDO_HWID";
                if (ctx != null) {
                    String aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                    if (aid != null && !aid.isEmpty()) serial = aid;
                }

                String body = "game=com.pubg.imobile"
                        + "&user_key=" + URLEncoder.encode(key, "UTF-8")
                        + "&package=" + URLEncoder.encode(pkg, "UTF-8")
                        + "&serial=" + URLEncoder.encode(serial, "UTF-8");

                URL url = new URL(MundoEngine.getRemoteServerUrl());
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Accept", "application/json");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                }

                JSONObject json = new JSONObject(sb.toString());
                boolean ok = json.optBoolean("status", false);
                if (!ok) {
                    String msg = json.optString("message", json.optString("reason", "Activation failed"));
                    MundoEngine.setActivationResult(false, msg, "", "", "com.pubg.imobile", "");
                    return;
                }

                JSONObject data = json.optJSONObject("data");
                String pluginUrl = data != null ? data.optString("plugin_url", "") : "";
                String socUrl = data != null ? data.optString("socu64_url", data.optString("soc_url", "")) : "";
                String anogsTarget = data != null ? data.optString("anogs_target_package", "com.pubg.imobile") : "com.pubg.imobile";
                String anogsOffsets = data != null ? data.optString("anogs_ret_offsets", "") : "";
                String msg = json.optString("message", "SDK activated successfully");
                MundoEngine.setActivationResult(true, msg, pluginUrl, socUrl, anogsTarget, anogsOffsets);
            } catch (Throwable t) {
                MundoEngine.setActivationResult(false, t.getMessage() != null ? t.getMessage() : "Activation error", "", "", "com.pubg.imobile", "");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "Mundo-Activate").start();
    }
}
