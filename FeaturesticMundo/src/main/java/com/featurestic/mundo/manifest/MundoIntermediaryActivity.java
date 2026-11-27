package com.featurestic.mundo.manifest;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import com.featurestic.mundo.core.MundoEngine;

import java.util.List;

public class MundoIntermediaryActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent src = getIntent();
        if (src != null && src.getData() != null) {
            Uri uri = src.getData();
            setResult(RESULT_OK, new Intent().setData(uri));
            String targetPkg = MundoEngine.getActivePackage();
            if (targetPkg != null && !targetPkg.isEmpty()) {
                try {
                    PackageManager pm = getPackageManager();
                    Intent probe = new Intent(Intent.ACTION_VIEW, uri);
                    probe.setPackage(targetPkg);
                    List<ResolveInfo> list = pm.queryIntentActivities(probe, 0);
                    if (list != null && !list.isEmpty() && list.get(0).activityInfo != null) {
                        Intent real = new Intent(Intent.ACTION_VIEW, uri);
                        real.setComponent(new ComponentName(targetPkg, list.get(0).activityInfo.name));
                        real.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        int slot = Math.abs(targetPkg.hashCode()) % 8;
                        Intent stub = new Intent();
                        stub.setClassName(getPackageName(),
                                "com.featurestic.mundo.manifest.MundoStubActivity$P" + slot);
                        stub.putExtra("mundo_real_intent", real);
                        stub.putExtra("mundo_target_pkg", targetPkg);
                        stub.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(stub);
                    }
                } catch (Throwable ignored) {}
            }
        }
        finish();
    }
}
