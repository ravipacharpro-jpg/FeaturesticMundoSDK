package com.featurestic.mundo.manifest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.featurestic.mundo.auth.GoogleSignInTokenHelper;
import com.featurestic.mundo.auth.MicroGGoogleAuthEngine;

import java.util.List;

/**
 * In-app MicroG-compatible Google Sign-In Activity.
 * Shows saved Google accounts for 1-tap sign-in or opens Google EmbeddedSetup
 * (MinuteMaid) WebView to acquire an oauth_token, exchanges it for a master
 * token and genuine Google RSA-signed ID token + serverAuthCode via
 * MicroGGoogleAuthEngine.
 */
public class MundoGoogleAuthActivity extends Activity {
    private static final String TAG = "MundoGoogleAuth";

    public static final String EXTRA_TARGET_PKG = "mundo_google_target_pkg";
    public static final String EXTRA_SERVER_CLIENT_ID = "mundo_google_server_client_id";
    public static final String EXTRA_SCOPES = "mundo_google_scopes";

    private WebView mWebView;
    private ProgressBar mProgress;
    private TextView mStatusText;
    private LinearLayout mWebContainer;

    private String mTargetPkg;
    private String mServerClientId;
    private String mScopes;
    private volatile boolean mExchanging = false;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Intent in = getIntent();
        mTargetPkg = in != null ? in.getStringExtra(EXTRA_TARGET_PKG) : null;
        if (mTargetPkg == null || mTargetPkg.isEmpty()) mTargetPkg = "com.pubg.imobile";
        mServerClientId = in != null ? in.getStringExtra(EXTRA_SERVER_CLIENT_ID) : null;
        mScopes = in != null ? in.getStringExtra(EXTRA_SCOPES) : null;

        buildUi();

        List<MicroGGoogleAuthEngine.AccountRecord> saved = MicroGGoogleAuthEngine.getSavedAccounts(this);
        if (saved != null && !saved.isEmpty()) {
            showSavedAccountPicker(saved);
        } else {
            startEmbeddedSetupWebView();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(24, 16, 24, 16);
        topBar.setBackgroundColor(Color.parseColor("#1E1E1E"));

        TextView title = new TextView(this);
        title.setText("Google Sign-In");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(title, tp);

        Button closeBtn = new Button(this);
        closeBtn.setText("Close");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setBackgroundColor(Color.parseColor("#333333"));
        closeBtn.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        topBar.addView(closeBtn);
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(View.GONE);
        root.addView(mProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 8));

        mStatusText = new TextView(this);
        mStatusText.setTextColor(Color.LTGRAY);
        mStatusText.setTextSize(14);
        mStatusText.setGravity(Gravity.CENTER);
        mStatusText.setPadding(32, 32, 32, 32);
        mStatusText.setVisibility(View.GONE);
        root.addView(mStatusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mWebContainer = new LinearLayout(this);
        mWebContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mWebContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void showSavedAccountPicker(final List<MicroGGoogleAuthEngine.AccountRecord> accounts) {
        String[] labels = new String[accounts.size() + 1];
        for (int i = 0; i < accounts.size(); i++) {
            MicroGGoogleAuthEngine.AccountRecord acc = accounts.get(i);
            labels[i] = (acc.displayName != null && !acc.displayName.isEmpty())
                    ? acc.displayName + " (" + acc.email + ")"
                    : acc.email;
        }
        labels[accounts.size()] = "Add another Google account";

        new AlertDialog.Builder(this)
                .setTitle("Choose a Google Account")
                .setItems(labels, (dialog, which) -> {
                    if (which == accounts.size()) {
                        startEmbeddedSetupWebView();
                    } else {
                        signInWithSavedAccount(accounts.get(which));
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .setOnCancelListener(dialog -> {
                    setResult(RESULT_CANCELED);
                    finish();
                })
                .show();
    }

    private void signInWithSavedAccount(final MicroGGoogleAuthEngine.AccountRecord account) {
        mProgress.setVisibility(View.VISIBLE);
        mStatusText.setText("Signing in as " + account.email + "...");
        mStatusText.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                MicroGGoogleAuthEngine.TokenBundle bundle =
                        MicroGGoogleAuthEngine.performAppSignIn(
                                MundoGoogleAuthActivity.this,
                                account,
                                mTargetPkg,
                                mServerClientId,
                                mScopes);
                mMainHandler.post(() -> deliverTokenBundle(bundle));
            } catch (final Throwable t) {
                Log.e(TAG, "Saved account sign-in failed", t);
                mMainHandler.post(() -> {
                    mProgress.setVisibility(View.GONE);
                    mStatusText.setVisibility(View.GONE);
                    Toast.makeText(MundoGoogleAuthActivity.this,
                            "Session expired, please sign in again", Toast.LENGTH_SHORT).show();
                    MicroGGoogleAuthEngine.removeAccount(MundoGoogleAuthActivity.this, account.email);
                    startEmbeddedSetupWebView();
                });
            }
        }, "Mundo-GoogleSignIn").start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startEmbeddedSetupWebView() {
        mWebContainer.removeAllViews();
        mProgress.setVisibility(View.VISIBLE);
        mStatusText.setVisibility(View.GONE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setCookie(MicroGGoogleAuthEngine.EMBEDDED_SETUP_URL, "oauth_token=; Max-Age=0; Path=/");
        cm.flush();

        mWebView = new WebView(this);
        cm.setAcceptThirdPartyCookies(mWebView, true);

        WebSettings s = mWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        String baseUa = s.getUserAgentString();
        if (baseUa != null && !baseUa.contains("MinuteMaid")) {
            s.setUserAgentString(baseUa + MicroGGoogleAuthEngine.MAGIC_USER_AGENT_SUFFIX);
        }

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mProgress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkForOAuthTokenCookie(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : "";
                if (checkForOAuthTokenCookie(url)) {
                    return true;
                }
                return false;
            }
        });

        mWebContainer.addView(mWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String setupUrl = MicroGGoogleAuthEngine.buildEmbeddedSetupUrl(this);
        Log.i(TAG, "Loading Google EmbeddedSetup: " + setupUrl);
        mWebView.loadUrl(setupUrl);
    }

    private boolean checkForOAuthTokenCookie(String url) {
        if (mExchanging || url == null) return false;
        String oauthToken = extractCookieValue(url, "oauth_token");
        if (oauthToken == null || oauthToken.isEmpty()) {
            oauthToken = extractCookieValue(MicroGGoogleAuthEngine.EMBEDDED_SETUP_URL, "oauth_token");
        }
        if (oauthToken == null || oauthToken.isEmpty()) {
            return false;
        }
        mExchanging = true;
        final String token = oauthToken;
        mProgress.setVisibility(View.VISIBLE);
        mStatusText.setText("Completing Google Sign-In...");
        mStatusText.setVisibility(View.VISIBLE);
        if (mWebView != null) {
            mWebView.setVisibility(View.GONE);
        }

        new Thread(() -> {
            try {
                MicroGGoogleAuthEngine.AccountRecord acc =
                        MicroGGoogleAuthEngine.exchangeOAuthTokenForMasterToken(
                                MundoGoogleAuthActivity.this, token);
                MicroGGoogleAuthEngine.TokenBundle bundle =
                        MicroGGoogleAuthEngine.performAppSignIn(
                                MundoGoogleAuthActivity.this,
                                acc,
                                mTargetPkg,
                                mServerClientId,
                                mScopes);
                mMainHandler.post(() -> deliverTokenBundle(bundle));
            } catch (final Throwable t) {
                Log.e(TAG, "OAuth token exchange failed", t);
                mMainHandler.post(() -> {
                    mExchanging = false;
                    mProgress.setVisibility(View.GONE);
                    mStatusText.setText("Google Sign-In failed: " + t.getMessage());
                    if (mWebView != null) {
                        mWebView.setVisibility(View.VISIBLE);
                    }
                });
            }
        }, "Mundo-GoogleExchange").start();
        return true;
    }

    private static String extractCookieValue(String url, String key) {
        try {
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies == null || cookies.isEmpty()) return null;
            for (String part : cookies.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0 && trimmed.substring(0, eq).trim().equals(key)) {
                    return trimmed.substring(eq + 1).trim();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void deliverTokenBundle(MicroGGoogleAuthEngine.TokenBundle bundle) {
        if (bundle == null || bundle.email == null || bundle.email.isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        Intent result = new Intent();
        result.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_EMAIL, bundle.email);
        result.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_NAME, bundle.displayName);
        result.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_SUB, bundle.googleUserId);
        result.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_ID_TOKEN, bundle.idToken);
        result.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_AUTH_CODE, bundle.serverAuthCode);
        result.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_GIVEN_NAME, bundle.givenName);
        result.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_FAMILY_NAME, bundle.familyName);
        result.putExtra(GoogleSignInTokenHelper.EXTRA_WEB_PHOTO_URL, bundle.photoUrl);

        Log.i(TAG, "Delivered Google TokenBundle for " + bundle.email
                + " idToken=" + (bundle.idToken != null && !bundle.idToken.isEmpty())
                + " authCode=" + (bundle.serverAuthCode != null && !bundle.serverAuthCode.isEmpty()));
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            try {
                mWebView.destroy();
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
