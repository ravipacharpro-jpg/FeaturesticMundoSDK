package com.featurestic.mundo.manifest;

import android.app.Activity;
import android.os.Bundle;

import com.featurestic.mundo.core.MundoStreamproofGuard;

public class MundoStubActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent() != null && getIntent().getBooleanExtra("mundo_preload_only", false)) {
            finish();
        }
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
