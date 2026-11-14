package pt.forreta.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Forreta TV: opens the Forreta TV web app full screen, driven by the TV remote. */
public class MainActivity extends Activity {

    private WebView web;
    private FrameLayout root;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;
    private String appUrl;
    private String appHost;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        appUrl = getString(R.string.app_url);
        appHost = Uri.parse(appUrl).getHost();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemBars();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " ForretaTV-Android");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                // the app itself stays inside; other sites (links) open outside
                if (appHost != null && appHost.equals(u.getHost())) return false;
                if (!req.isForMainFrame()) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int code, String description, String failingUrl) {
                showOffline();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullView != null) { callback.onCustomViewHidden(); return; }
                fullView = view;
                fullCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                hideSystemBars();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(appUrl);
        web.requestFocus();
    }

    private void exitFullscreen() {
        if (fullView == null) return;
        root.removeView(fullView);
        fullView = null;
        web.setVisibility(View.VISIBLE);
        if (fullCallback != null) fullCallback.onCustomViewHidden();
        fullCallback = null;
        web.requestFocus();
    }

    private void showOffline() {
        TextView t = new TextView(this);
        t.setText("Sem ligação à internet.\nVerifica o Wi-Fi e carrega OK para tentar outra vez.");
        t.setTextColor(Color.WHITE);
        t.setTextSize(22);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackgroundColor(Color.BLACK);
        t.setFocusable(true);
        t.setOnClickListener(v -> { root.removeView(t); web.reload(); web.requestFocus(); });
        root.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.requestFocus();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** Remote buttons that the web page cannot see on its own. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    sendKey("PageUp"); return true;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    sendKey("PageDown"); return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    web.evaluateJavascript("(function(){var v=document.getElementById('video');if(v){v.paused?v.play():v.pause();}})()", null);
                    return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void sendKey(String key) {
        web.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'" + key + "',bubbles:true}))", null);
    }

    /** Back button: let the app go back a step (close the player, go to Início); leave the app only from Início. */
    @Override
    public void onBackPressed() {
        if (fullView != null) { exitFullscreen(); return; }
        web.evaluateJavascript("(function(){try{return window.ftvBack?window.ftvBack():false;}catch(e){return false;}})()", result -> {
            if (!"true".equals(result)) finish();
        });
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); hideSystemBars(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onDestroy() { if (web != null) web.destroy(); super.onDestroy(); }
}
