package com.featurestic.mundo.manifest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.featurestic.mundo.core.MundoBinderProxy;
import com.featurestic.mundo.core.MundoStreamproofGuard;
import com.featurestic.mundo.core.MundoVirtualRuntime;

public class MundoStubActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        if (intent.getBooleanExtra("mundo_preload_only", false)) {
            finish();
            return;
        }

        String targetPkg = intent.getStringExtra(MundoBinderProxy.EXTRA_TARGET_PKG);
        String sourceDir = intent.getStringExtra("mundo_source_dir");
        String nativeLibDir = intent.getStringExtra("mundo_native_lib_dir");
        int userId = intent.getIntExtra("mundo_user_id", 0);
        Intent realIntent = intent.getParcelableExtra(MundoBinderProxy.EXTRA_REAL_INTENT);

        if (targetPkg != null && !targetPkg.isEmpty()) {
            MundoVirtualRuntime.bootstrapInStubProcess(
                    this, targetPkg, sourceDir, nativeLibDir, userId, realIntent);
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (MundoStreamproofGuard.isEnabled()) {
            MundoStreamproofGuard.applyNow(this);
        }
    }

    public static class P0 extends MundoStubActivity {}
    public static class P1 extends MundoStubActivity {}
    public static class P2 extends MundoStubActivity {}
    public static class P3 extends MundoStubActivity {}
    public static class P4 extends MundoStubActivity {}
    public static class P5 extends MundoStubActivity {}
    public static class P6 extends MundoStubActivity {}
    public static class P7 extends MundoStubActivity {}
}
