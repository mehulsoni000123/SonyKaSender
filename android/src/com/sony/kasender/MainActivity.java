package com.sony.kasender;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.util.Size;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sony Ka Sender — the phone side.
 * The UI lives in assets/index.html (WebView). This class handles files, network and haptics.
 */
public class MainActivity extends Activity {
    private static final int REQ_MEDIA = 11, REQ_FILES = 12;
    private static final int PC_PORT = 7777, DISCOVERY_PORT = 7778;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, Uri> items = new ConcurrentHashMap<>();
    private final Map<String, String> texts = new ConcurrentHashMap<>();
    private final Map<String, String> thumbs = new ConcurrentHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private int nameSeq = 0;
    private boolean pageReady = false;
    private Intent pendingShare = null;
    private float density = 1f;
    private int insetTop = 0, insetBottom = 0;
    private FlickDetector flick;
    private boolean flickWanted = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        density = getResources().getDisplayMetrics().density;

        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        web.setBackgroundColor(0xFF16113A);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "SKS");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pageReady = true;
                pushInsets();
                if (pendingShare != null) { Intent i = pendingShare; pendingShare = null; handleShare(i); }
            }
        });
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetTop = i.top; insetBottom = i.bottom;
            } else {
                insetTop = insets.getSystemWindowInsetTop(); insetBottom = insets.getSystemWindowInsetBottom();
            }
            pushInsets();
            return insets;
        });
        flick = new FlickDetector(this, (g, a) -> js("onWristFlick"));
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        handleShare(getIntent());
    }

    @Override protected void onResume() { super.onResume(); if (flickWanted) flick.start(); if (pageReady) js("onResumeApp", FlickService.isOn(this) ? "true" : "false"); }
    @Override protected void onPause() { flick.stop(); super.onPause(); }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleShare(intent); }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (!pageReady) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.SKSCB && SKSCB.onBack ? SKSCB.onBack() : false", r -> {
            if (!"true".equals(r)) moveTaskToBack(true);
        });
    }

    private void pushInsets() {
        if (!pageReady) return;
        js("onInsets", Math.round(insetTop / density) + "," + Math.round(insetBottom / density));
    }

    // ---------- share sheet ----------
    private void handleShare(Intent in) {
        if (in == null) return;
        String act = in.getAction();
        if (!Intent.ACTION_SEND.equals(act) && !Intent.ACTION_SEND_MULTIPLE.equals(act)) return;
        if (!pageReady) { pendingShare = in; return; }
        final List<Uri> uris = new ArrayList<>();
        String text = null;
        if (Intent.ACTION_SEND.equals(act)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u); else text = in.getStringExtra(Intent.EXTRA_TEXT);
        } else {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty() && in.getClipData() != null) {
            ClipData cd = in.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) { Uri u = cd.getItemAt(i).getUri(); if (u != null) uris.add(u); }
        }
        final String sharedText = text;
        final boolean auto = System.currentTimeMillis() < FlickService.autoUntil;
        FlickService.autoUntil = 0;
        // consume, so rotating / returning doesn't re-add
        in.setAction(Intent.ACTION_MAIN);
        pool.execute(() -> {
            JSONArray arr = new JSONArray();
            if (sharedText != null && !sharedText.trim().isEmpty()) arr.put(describeText(sharedText));
            for (Uri u : uris) { JSONObject o = describe(u, in.getType()); if (o != null) arr.put(o); }
            js("onItems", arr.toString(), "true", auto ? "true" : "false");
        });
    }

    // ---------- pickers ----------
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) { js("onItems", "[]", "false"); return; }
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) uris.add(data.getData());
        pool.execute(() -> {
            JSONArray arr = new JSONArray();
            for (Uri u : uris) { JSONObject o = describe(u); if (o != null) arr.put(o); }
            js("onItems", arr.toString(), "false");
        });
    }

    private JSONObject describeText(String t) {
        try {
            String id = UUID.randomUUID().toString().substring(0, 12);
            texts.put(id, t);
            JSONObject o = new JSONObject();
            boolean link = t.matches("(?s).*https?://\\S+.*");
            o.put("id", id); o.put("kind", link ? "link" : "note"); o.put("name", link ? "Link" : "Note");
            o.put("text", t.length() > 600 ? t.substring(0, 600) : t);
            return o;
        } catch (Exception e) { return null; }
    }

    private JSONObject describe(Uri uri) { return describe(uri, null); }
    private JSONObject describe(Uri uri, String fallbackMime) {
        try {
            ContentResolver cr = getContentResolver();
            String name = null; long size = -1;
            try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            } catch (Exception ignored) {}
            String mime = null;
            try { mime = cr.getType(uri); } catch (Exception ignored) {}
            if (mime == null && fallbackMime != null && !fallbackMime.contains("*")) mime = fallbackMime;
            if (mime == null) mime = "application/octet-stream";
            if (name == null) name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "file";
            String kind = mime.startsWith("image/") ? "image" : mime.startsWith("video/") ? "video" : "doc";
            name = friendlyName(uri, name, mime, kind);
            String id = UUID.randomUUID().toString().substring(0, 12);
            items.put(id, uri);
            names.put(id, name);
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name); o.put("mime", mime); o.put("size", size); o.put("kind", kind);
            if (!kind.equals("doc")) {
                String th = thumb(uri, kind);
                if (th != null) { thumbs.put(id, th); o.put("thumb", th); }
            }
            return o;
        } catch (Exception e) { return null; }
    }

    /** Photo-picker files come back named like "39395.jpg"; rename them by the date they were taken. */
    private String friendlyName(Uri uri, String name, String mime, String kind) {
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (!base.matches("[0-9]+")) return name;
        String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (ext == null) ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "bin";
        long when = 0;
        try (Cursor c = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DATE_TAKEN}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) when = c.getLong(0);
        } catch (Exception ignored) {}
        if (when <= 0) when = System.currentTimeMillis();
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(new java.util.Date(when));
        String label = kind.equals("video") ? "Video" : kind.equals("image") ? "Photo" : "File";
        synchronized (this) { nameSeq++; }
        return label + " " + stamp + (nameSeq > 1 ? " (" + nameSeq + ")" : "") + "." + ext;
    }

    private String thumb(Uri uri, String kind) {
        Bitmap bm = null;
        try { bm = getContentResolver().loadThumbnail(uri, new Size(720, 720), null); } catch (Exception ignored) {}
        if (bm == null && kind.equals("image")) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options op = new BitmapFactory.Options(); op.inSampleSize = 4;
                bm = BitmapFactory.decodeStream(in, null, op);
            } catch (Exception ignored) {}
        }
        if (bm == null && kind.equals("video")) {
            try { MediaMetadataRetriever r = new MediaMetadataRetriever(); r.setDataSource(this, uri); bm = r.getFrameAtTime(500000); r.release(); } catch (Exception ignored) {}
        }
        if (bm == null) return null;
        int max = Math.max(bm.getWidth(), bm.getHeight());
        if (max > 720) { float k = 720f / max; bm = Bitmap.createScaledBitmap(bm, Math.round(bm.getWidth() * k), Math.round(bm.getHeight() * k), true); }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 78, bo);
        return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    // ---------- network ----------
    private String httpGet(String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
        try (InputStream in = c.getInputStream()) { return new String(readAll(in), StandardCharsets.UTF_8); }
        finally { c.disconnect(); }
    }
    private String httpPostJson(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(15000); c.setDoOutput(true); c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = c.getOutputStream()) { o.write(b); }
        try (InputStream in = c.getInputStream()) { return new String(readAll(in), StandardCharsets.UTF_8); }
        finally { c.disconnect(); }
    }
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private void discoverNow(String extraHosts) {
        android.util.Log.i("SKS", "discover start extra=" + extraHosts);
        Map<String, JSONObject> found = new LinkedHashMap<>();
        // 1) USB cable (adb reverse) or a saved address
        List<String> hosts = new ArrayList<>();
        hosts.add("127.0.0.1");
        if (extraHosts != null) for (String h : extraHosts.split(",")) if (!h.trim().isEmpty()) hosts.add(h.trim());
        for (String h : hosts) {
            try {
                JSONObject info = new JSONObject(httpGet("http://" + h + ":" + PC_PORT + "/api/info", h.startsWith("100.") ? 1500 : 700));
                if ("SonyKaSender".equals(info.optString("app"))) {
                    JSONObject d = new JSONObject();
                    d.put("name", info.optString("name", "PC")); d.put("host", h); d.put("port", info.optInt("port", PC_PORT));
                    d.put("via", h.equals("127.0.0.1") ? "USB" : h.startsWith("100.") ? "Tailscale" : "Wi-Fi");
                    if (info.has("ips")) d.put("ips", info.getJSONArray("ips"));
                    found.put(d.getString("name") + "|" + d.getString("via"), d);
                }
            } catch (Exception ex) { android.util.Log.i("SKS", "probe " + h + " failed: " + ex); }
        }
        // 2) Wi-Fi broadcast
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        try {
            if (wm != null) { lock = wm.createMulticastLock("sks"); lock.setReferenceCounted(false); lock.acquire(); }
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setBroadcast(true); sock.setSoTimeout(350);
                byte[] q = "SKS?".getBytes(StandardCharsets.UTF_8);
                long end = System.currentTimeMillis() + 1800;
                int round = 0;
                while (System.currentTimeMillis() < end) {
                    if (round++ % 3 == 0) {
                        sock.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
                        String bc = subnetBroadcast(wm);
                        if (bc != null) sock.send(new DatagramPacket(q, q.length, InetAddress.getByName(bc), DISCOVERY_PORT));
                    }
                    try {
                        byte[] buf = new byte[1024]; DatagramPacket p = new DatagramPacket(buf, buf.length);
                        sock.receive(p);
                        JSONObject r = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                        if ("SonyKaSender".equals(r.optString("app"))) {
                            JSONObject d = new JSONObject();
                            d.put("name", r.optString("name", "PC")); d.put("host", p.getAddress().getHostAddress()); d.put("port", r.optInt("port", PC_PORT)); d.put("via", "Wi-Fi");
                            if (r.has("ips")) d.put("ips", r.getJSONArray("ips"));
                            found.put(d.getString("name") + "|Wi-Fi", d);
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
            }
        } catch (Exception ex) { android.util.Log.i("SKS", "udp failed: " + ex);
        } finally { if (lock != null) try { lock.release(); } catch (Exception ignored) {} }
        JSONArray arr = new JSONArray();
        for (JSONObject d : found.values()) arr.put(d);
        android.util.Log.i("SKS", "discover result " + arr);
        js("onDevices", arr.toString());
    }

    @SuppressWarnings("deprecation")
    private String subnetBroadcast(WifiManager wm) {
        try {
            if (wm == null || wm.getDhcpInfo() == null) return null;
            int ip = wm.getDhcpInfo().ipAddress, mask = wm.getDhcpInfo().netmask;
            if (ip == 0 || mask == 0) return null;
            int bc = (ip & mask) | ~mask;
            return (bc & 0xff) + "." + ((bc >> 8) & 0xff) + "." + ((bc >> 16) & 0xff) + "." + ((bc >> 24) & 0xff);
        } catch (Exception e) { return null; }
    }

    private volatile String lastGoodHost = null;
    /** Try each known address of the PC (home Wi-Fi, Tailscale, USB) and use the first that answers. */
    private String pickHost(String hosts, int port) {
        List<String> list = new ArrayList<>();
        if (lastGoodHost != null && hosts.contains(lastGoodHost)) list.add(lastGoodHost);
        for (String h : hosts.split(",")) if (!h.trim().isEmpty() && !list.contains(h.trim())) list.add(h.trim());
        for (String h : list) {
            try {
                JSONObject info = new JSONObject(httpGet("http://" + h + ":" + port + "/api/info", h.startsWith("100.") ? 2000 : 800));
                if ("SonyKaSender".equals(info.optString("app"))) { lastGoodHost = h; return h; }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void sendItem(String id, String hosts, int port, String from) {
        String host = pickHost(hosts, port);
        if (host == null) { js("onSent", q(id), "false", q("PC not reachable — same Wi-Fi or Tailscale on?")); return; }
        String base = "http://" + host + ":" + port;
        try {
            if (texts.containsKey(id)) {
                JSONObject b = new JSONObject(); b.put("id", id); b.put("text", texts.get(id)); b.put("from", from);
                httpPostJson(base + "/api/text", b.toString());
                js("onProgress", q(id), "1");
                js("onSent", q(id), "true", q(""));
                return;
            }
            Uri uri = items.get(id);
            if (uri == null) throw new Exception("File is no longer available");
            ContentResolver cr = getContentResolver();
            String name = "file", mime = cr.getType(uri); long size = -1;
            try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            }
            if (names.containsKey(id)) name = names.get(id);
            if (mime == null) mime = "application/octet-stream";
            // announce first so the PC starts the landing animation right away
            JSONObject a = new JSONObject();
            a.put("id", id); a.put("name", name); a.put("mime", mime); a.put("size", size); a.put("from", from);
            if (thumbs.containsKey(id)) a.put("thumb", thumbs.get(id));
            httpPostJson(base + "/api/announce", a.toString());

            String url = base + "/api/upload?id=" + URLEncoder.encode(id, "UTF-8") + "&name=" + URLEncoder.encode(name, "UTF-8") + "&mime=" + URLEncoder.encode(mime, "UTF-8") + "&size=" + size;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(120000); c.setDoOutput(true); c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/octet-stream");
            if (size > 0) c.setFixedLengthStreamingMode(size); else c.setChunkedStreamingMode(64 * 1024);
            long sent = 0, lastPush = 0;
            try (InputStream in = cr.openInputStream(uri); OutputStream out = c.getOutputStream()) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n); sent += n;
                    long now = System.currentTimeMillis();
                    if (size > 0 && now - lastPush > 100) { lastPush = now; js("onProgress", q(id), String.valueOf((double) sent / size)); }
                }
            }
            int code = c.getResponseCode();
            c.disconnect();
            if (code != 200) throw new Exception("PC answered " + code);
            js("onProgress", q(id), "1");
            js("onSent", q(id), "true", q(""));
        } catch (Exception e) {
            js("onSent", q(id), "false", q(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    // ---------- JS plumbing ----------
    private static String q(String s) { return JSONObject.quote(s); }
    private void js(String fn, String... args) {
        if (web == null) return;
        StringBuilder sb = new StringBuilder("window.SKSCB && SKSCB.").append(fn).append(" && SKSCB.").append(fn).append("(");
        for (int i = 0; i < args.length; i++) { if (i > 0) sb.append(','); sb.append(args[i]); }
        sb.append(")");
        final String code = sb.toString();
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    class Bridge {
        @JavascriptInterface public void pickMedia() {
            ui.post(() -> {
                try {
                    Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                    i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(50, MediaStore.getPickImagesMaxLimit()));
                    startActivityForResult(i, REQ_MEDIA);
                } catch (Throwable t) {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*"); i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, REQ_MEDIA);
                }
            });
        }
        @JavascriptInterface public void pickFiles() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_FILES);
            });
        }
        @JavascriptInterface public void addText(String t) {
            pool.execute(() -> { JSONArray a = new JSONArray(); JSONObject o = describeText(t); if (o != null) a.put(o); js("onItems", a.toString(), "false"); });
        }
        @JavascriptInterface public void discover(String extraHosts) { pool.execute(() -> discoverNow(extraHosts)); }
        @JavascriptInterface public void send(String id, String host, int port, String from) { pool.execute(() -> sendItem(id, host, port, from)); }
        @JavascriptInterface public void forget(String id) { names.remove(id); items.remove(id); texts.remove(id); thumbs.remove(id); }
        @JavascriptInterface public String deviceName() {
            String n = null;
            try { n = Settings.Global.getString(getContentResolver(), "device_name"); } catch (Exception ignored) {}
            return n != null ? n : Build.MODEL;
        }
        @JavascriptInterface public void haptic(String kind) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            VibrationEffect e;
            switch (kind) {
                case "flick": e = VibrationEffect.createWaveform(new long[]{0, 18, 40, 30}, new int[]{0, 120, 0, 255}, -1); break;
                case "success": e = VibrationEffect.createWaveform(new long[]{0, 25, 70, 45}, new int[]{0, 160, 0, 255}, -1); break;
                case "error": e = VibrationEffect.createWaveform(new long[]{0, 40, 60, 40}, new int[]{0, 255, 0, 255}, -1); break;
                default: e = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            }
            v.vibrate(e);
        }
        @JavascriptInterface public void armFlick(boolean on) { ui.post(() -> { flickWanted = on; if (on) flick.start(); else flick.stop(); }); }
        @JavascriptInterface public boolean galleryFlickOn() { return FlickService.isOn(MainActivity.this); }
        @JavascriptInterface public void openAccessibility() {
            ui.post(() -> {
                try {
                    Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                    i.putExtra(Intent.EXTRA_COMPONENT_NAME, new android.content.ComponentName(MainActivity.this, FlickService.class).flattenToString());
                    startActivity(i);
                } catch (Exception e) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            });
        }
        @JavascriptInterface public void close() { ui.post(() -> moveTaskToBack(true)); }
        @JavascriptInterface public void toast(String m) { ui.post(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show()); }
    }
}
