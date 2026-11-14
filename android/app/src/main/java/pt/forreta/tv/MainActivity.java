package pt.forreta.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
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
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

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

            /** Local bridge: https://ftv.local/?url=... is fetched here, on the TV, with its own internet (and VPN). */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!LOCAL_HOST.equals(u.getHost())) return null;
                try {
                    return localBridge(req, u);
                } catch (Exception e) {
                    return textResponse(502, "erro: " + e.getMessage());
                }
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
        checkForUpdate();
    }

    /* ---------------- self-update: looks for a newer APK on GitHub and offers to install it ---------------- */
    private static final String RELEASES = "https://api.github.com/repos/Rodron85/tv/releases/latest";
    private static final String ACTION_INSTALLED = "pt.forreta.tv.INSTALL_STATUS";

    private int myVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; }
        catch (Exception e) { return 0; }
    }

    private void checkForUpdate() {
        final SharedPreferences prefs = getSharedPreferences("ftv", MODE_PRIVATE);
        long last = prefs.getLong("lastUpdateCheck", 0);
        if (System.currentTimeMillis() - last < 3 * 3600 * 1000L) return;     // at most every 3 hours
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                InputStream in = c.getInputStream(); byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                in.close();
                prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
                JSONObject rel = new JSONObject(buf.toString("UTF-8"));
                String tag = rel.optString("tag_name", "");              // apk-<number>
                int latest = Integer.parseInt(tag.replaceAll("[^0-9]", "").isEmpty() ? "0" : tag.replaceAll("[^0-9]", ""));
                if (latest <= myVersion()) return;
                String apk = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break; }
                }
                if (apk == null) return;
                final String url = apk;
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Forreta TV")
                        .setMessage("Há uma versão nova da Forreta TV. Instalar agora?")
                        .setPositiveButton("Instalar", (d, w) -> downloadAndInstall(url))
                        .setNegativeButton("Mais tarde", null)
                        .show());
            } catch (Exception ignored) {}
        }).start();
    }

    private void downloadAndInstall(String url) {
        final AlertDialog wait = new AlertDialog.Builder(this).setMessage("A descarregar a versão nova…").setCancelable(false).show();
        new Thread(() -> {
            try {
                // follow GitHub's redirect to the file
                String current = url; HttpURLConnection c = null;
                for (int i = 0; i < 6; i++) {
                    c = (HttpURLConnection) new URL(current).openConnection();
                    c.setInstanceFollowRedirects(false);
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) { current = new URL(new URL(current), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
                    break;
                }
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int id = pi.createSession(params);
                PackageInstaller.Session session = pi.openSession(id);
                try (OutputStream out = session.openWrite("forreta", 0, -1); InputStream in = c.getInputStream()) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                    session.fsync(out);
                }
                Intent done = new Intent(this, MainActivity.class).setAction(ACTION_INSTALLED);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pending = PendingIntent.getActivity(this, 7, done, flags);
                session.commit(pending.getIntentSender());
                session.close();
                runOnUiThread(wait::dismiss);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    wait.dismiss();
                    new AlertDialog.Builder(this).setMessage("Não consegui instalar a versão nova (" + e.getMessage() + ").").setPositiveButton("OK", null).show();
                });
            }
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && ACTION_INSTALLED.equals(intent.getAction())) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);   // the system "Install?" screen
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(confirm); } catch (Exception ignored) {}
                }
            }
        }
    }

    private static final String LOCAL_HOST = "ftv.local";
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static Map<String, String> corsHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Access-Control-Allow-Headers", "*");
        h.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        h.put("Access-Control-Expose-Headers", "*");
        return h;
    }

    private static WebResourceResponse textResponse(int code, String text) {
        return new WebResourceResponse("text/plain", "utf-8", code, code < 400 ? "OK" : "Error",
                corsHeaders(), new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private WebResourceResponse localBridge(WebResourceRequest req, Uri u) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return textResponse(200, "");
        String target = u.getQueryParameter("url");
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://")))
            return textResponse(200, "Forreta TV ponte OK (TV)");
        String ref = u.getQueryParameter("ref");
        String ua = u.getQueryParameter("ua");
        if (ua == null || ua.isEmpty()) ua = DEFAULT_UA;
        String range = req.getRequestHeaders() != null ? req.getRequestHeaders().get("Range") : null;

        // follow redirects by hand (also http <-> https)
        HttpURLConnection c = null;
        String current = target;
        for (int i = 0; i < 6; i++) {
            c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", ua);
            if (ref != null && !ref.isEmpty()) {
                c.setRequestProperty("Referer", ref);
                Uri r = Uri.parse(ref);
                if (r.getScheme() != null && r.getHost() != null) c.setRequestProperty("Origin", r.getScheme() + "://" + r.getHost());
            }
            if (range != null) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            break;
        }
        int code = c.getResponseCode();
        String ct = c.getContentType() != null ? c.getContentType() : "application/octet-stream";
        String path = Uri.parse(current).getPath() != null ? Uri.parse(current).getPath() : "";
        boolean playlist = ct.toLowerCase().contains("mpegurl") || path.toLowerCase().endsWith(".m3u8") || path.toLowerCase().endsWith(".m3u");
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) in = new ByteArrayInputStream(new byte[0]);
        int status = (code >= 300 && code < 400) ? 502 : code;
        Map<String, String> headers = corsHeaders();

        if (playlist && code < 400) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
            String text = buf.toString("UTF-8");
            if (text.trim().startsWith("#EXTM3U")) {
                StringBuilder out = new StringBuilder();
                URL base = new URL(current);
                for (String line : text.split("\\r?\\n")) {
                    String t = line.trim();
                    if (t.isEmpty()) { out.append(line).append('\n'); continue; }
                    if (t.startsWith("#")) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
                        StringBuffer sb = new StringBuffer();
                        while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("URI=\"" + wrap(base, m.group(1), ref, u.getQueryParameter("ua")) + "\""));
                        m.appendTail(sb);
                        out.append(sb).append('\n');
                    } else {
                        out.append(wrap(base, t, ref, u.getQueryParameter("ua"))).append('\n');
                    }
                }
                headers.put("Cache-Control", "no-cache");
                return new WebResourceResponse("application/vnd.apple.mpegurl", "utf-8", 200, "OK", headers,
                        new ByteArrayInputStream(out.toString().getBytes(StandardCharsets.UTF_8)));
            }
            in = new ByteArrayInputStream(buf.toByteArray());
        }
        for (String k : new String[]{"Content-Range", "Accept-Ranges", "Content-Length"}) {
            String v = c.getHeaderField(k);
            if (v != null) headers.put(k, v);
        }
        String mime = ct.split(";")[0].trim();
        return new WebResourceResponse(mime, null, status, status < 400 ? "OK" : "Error", headers, in);
    }

    private static String wrap(URL base, String link, String ref, String ua) throws Exception {
        String abs = new URL(base, link).toString();
        StringBuilder p = new StringBuilder("https://" + LOCAL_HOST + "/?url=").append(URLEncoder.encode(abs, "UTF-8"));
        if (ref != null && !ref.isEmpty()) p.append("&ref=").append(URLEncoder.encode(ref, "UTF-8"));
        if (ua != null && !ua.isEmpty()) p.append("&ua=").append(URLEncoder.encode(ua, "UTF-8"));
        return p.toString();
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
