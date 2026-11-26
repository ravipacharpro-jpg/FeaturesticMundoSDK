package com.featurestic.mundo.manifest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class MundoIntermediaryActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent src = getIntent();
        if (src != null && src.getData() != null) {
            setResult(RESULT_OK, new Intent().setData(src.getData()));
        }
        finish();
    }
}
