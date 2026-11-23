package com.sony.kasender;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Gallery flick": while a photo or video is open in a gallery app, a wrist flick presses that
 * app's Share button and picks "Flick to PC" — so the original file flies to the PC.
 * Motion sensors run ONLY while a supported gallery is on screen.
 */
public class FlickService extends AccessibilityService {
    static final String TAG = "SKSFLICK";
    /** MainActivity checks this: a share that arrives before this time was started by a flick → send at once. */
    public static volatile long autoUntil = 0;

    private static final Set<String> VIEWERS = new HashSet<>(Arrays.asList(
            "com.sec.android.gallery3d",            // Samsung Gallery
            "com.google.android.apps.photos",        // Google Photos
            "com.sec.android.app.myfiles"));         // Samsung My Files
    private static final Set<String> SHEETS = new HashSet<>(Arrays.asList(
            "com.android.intentresolver", "android", "com.samsung.android.intentresolver"));
    private static final String[] TARGET_LABELS = {"Flick to PC", "Sony Ka Sender"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private FlickDetector detector;
    private String fgPackage = "";
    private long awaitingSheetUntil = 0;
    private int scrollTries = 0;
    private boolean openedMore = false;
    private View pad;
    private WindowManager wm;
    private final Runnable padCheck = this::updatePad;

    @Override protected void onServiceConnected() {
        detector = new FlickDetector(this, (g, a) -> h.post(this::onFlick));
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Log.i(TAG, "service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (VIEWERS.contains(pkg) || pkg.equals(fgPackage) || e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            h.removeCallbacks(padCheck); h.postDelayed(padCheck, 250);
        }
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (SHEETS.contains(pkg) && System.currentTimeMillis() < awaitingSheetUntil) {
                scrollTries = 0; openedMore = false;
                h.postDelayed(this::pickTarget, 180);
                return;
            }
            if (pkg.equals("com.android.systemui") || pkg.contains("inputmethod") || pkg.contains("honeyboard")) return;
            if (pkg.equals(getPackageName())) return;   // our own Send tab
            fgPackage = pkg;
        }
    }

    private void onFlick() { sendOpenPhoto(true); }

    private void sendOpenPhoto(boolean fromFlick) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !VIEWERS.contains(root.getPackageName().toString())) return;
        AccessibilityNodeInfo share = findShare(root);
        if (share == null) {
            buzz(false);
            Toast.makeText(this, "Open a photo first, then flick", Toast.LENGTH_SHORT).show();
            return;
        }
        buzz(true);
        awaitingSheetUntil = System.currentTimeMillis() + 12000;
        autoUntil = System.currentTimeMillis() + 16000;
        click(share);
        Log.i(TAG, (fromFlick ? "flick" : "swipe pad") + " → pressed Share in " + root.getPackageName());
    }

    // ---------- swipe pad: a small "Send" tab on the edge while a photo is open ----------
    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private void updatePad() {
        boolean want = false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            want = root != null && root.getPackageName() != null && VIEWERS.contains(root.getPackageName().toString())
                    && findShare(root) != null && System.currentTimeMillis() > awaitingSheetUntil
                    && getSharedPreferences("sks", MODE_PRIVATE).getBoolean("pad", true);
        } catch (Exception ignored) {}
        // wrist flick listens only while the app on screen is a gallery
        boolean inGallery = false;
        try { AccessibilityNodeInfo r = getRootInActiveWindow(); inGallery = r != null && r.getPackageName() != null && VIEWERS.contains(r.getPackageName().toString()); } catch (Exception ignored) {}
        if (inGallery) detector.start(); else detector.stop();
        if (want && pad == null) showPad(); else if (!want && pad != null) hidePad();
    }

    private void showPad() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE67B5CFF); bg.setCornerRadii(new float[]{dp(18), dp(18), 0, 0, 0, 0, dp(18), dp(18)});
        v.setBackground(bg);
        v.setElevation(dp(8));
        TextView arrow = new TextView(this); arrow.setText("↑"); arrow.setTextColor(0xFFFFFFFF); arrow.setTextSize(22); arrow.setGravity(Gravity.CENTER);
        TextView lbl = new TextView(this); lbl.setText("Send\nto PC"); lbl.setTextColor(0xFFFFFFFF); lbl.setTextSize(10.5f); lbl.setGravity(Gravity.CENTER);
        v.addView(arrow); v.addView(lbl);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(54), dp(118),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.y = dp(60);
        final float[] start = new float[2];
        final long[] t0 = new long[1];
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = ev.getRawY(); t0[0] = System.currentTimeMillis();
                    view.animate().cancel(); view.setScaleX(1.06f); view.setScaleY(1.06f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = Math.min(0, ev.getRawY() - start[0]);
                    view.setTranslationY(dy); view.setAlpha(1f - Math.min(0.6f, -dy / dp(400)));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float d = ev.getRawY() - start[0];
                    long dt = Math.max(1, System.currentTimeMillis() - t0[0]);
                    view.setScaleX(1f); view.setScaleY(1f);
                    if (d < -dp(70) || (d < -dp(30) && d / dt < -0.8f)) {
                        view.animate().translationY(-dp(500)).alpha(0f).setDuration(260).start();
                        sendOpenPhoto(false);
                    } else if (Math.abs(d) < dp(8)) {
                        view.animate().translationY(0).alpha(1f).setDuration(200).start();
                        Toast.makeText(this, "Swipe me up to send this to your PC", Toast.LENGTH_SHORT).show();
                    } else {
                        view.animate().translationY(0).alpha(1f).setDuration(260).start();
                    }
                    return true;
            }
            return false;
        });
        try { wm.addView(v, lp); pad = v; v.setTranslationX(dp(54)); v.animate().translationX(0).setDuration(260).start(); }
        catch (Exception ex) { Log.i(TAG, "pad failed: " + ex); }
    }

    private void hidePad() {
        try { wm.removeView(pad); } catch (Exception ignored) {}
        pad = null;
    }

    /** The visible Share button of the open photo (not the one inside a selection bar of the grid). */
    private AccessibilityNodeInfo findShare(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        // Samsung Gallery viewer has a photo_view; Google Photos has its own viewer — only act inside a viewer
        boolean viewer = !root.findAccessibilityNodeInfosByViewId("com.sec.android.gallery3d:id/photo_view").isEmpty()
                || !root.findAccessibilityNodeInfosByViewId("com.sec.android.gallery3d:id/viewer_layout").isEmpty()
                || !"com.sec.android.gallery3d".contentEquals(root.getPackageName());
        if (!viewer) return null;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            CharSequence d = n.getContentDescription(), t = n.getText();
            if (n.isVisibleToUser() && (("Share".contentEquals(d == null ? "" : d)) || ("Share".contentEquals(t == null ? "" : t)))) return n;
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return null;
    }

    private void pickTarget() {
        if (System.currentTimeMillis() > awaitingSheetUntil) return;
        AccessibilityNodeInfo sheet = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null && r.getPackageName() != null && SHEETS.contains(r.getPackageName().toString())) { sheet = r; break; }
        }
        if (sheet == null) sheet = getRootInActiveWindow();
        if (sheet == null) { h.postDelayed(this::pickTarget, 200); return; }
        for (String label : TARGET_LABELS) {
            List<AccessibilityNodeInfo> hits = sheet.findAccessibilityNodeInfosByText(label);
            for (AccessibilityNodeInfo n : hits) {
                if (n.isVisibleToUser() && click(n)) {
                    awaitingSheetUntil = 0;
                    Log.i(TAG, "picked " + label);
                    return;
                }
            }
        }
        // not on screen yet: scroll the app row; at its end open "More" and scroll the full app list
        if (scrollTries++ < 22) {
            boolean moved = false;
            if (openedMore) {
                AccessibilityNodeInfo sc = findScrollable(sheet);
                moved = sc != null && sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
            if (!openedMore) {
                for (AccessibilityNodeInfo n : sheet.findAccessibilityNodeInfosByText("More")) {
                    if (n.isVisibleToUser() && "More".contentEquals(n.getText() == null ? "" : n.getText()) && click(n)) { openedMore = true; Log.i(TAG, "opened More"); break; }
                }
            }
            h.postDelayed(this::pickTarget, moved ? 160 : 350);
        } else {
            awaitingSheetUntil = 0;
            autoUntil = 0;
            Toast.makeText(this, "Couldn't find \"Flick to PC\" — pin it in the Share menu", Toast.LENGTH_LONG).show();
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo best = null;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isScrollable() && n.isVisibleToUser()) {
                CharSequence cls = n.getClassName();
                if (openedMore && cls != null && (cls.toString().endsWith("GridView") || cls.toString().endsWith("ListView"))) return n;  // the full "Apps" grid
                if (!openedMore || best == null) best = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) q.add(n.getChild(i));
        }
        return best;
    }

    private boolean click(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo c = n; c != null; c = c.getParent()) {
            if (c.isClickable()) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private void buzz(boolean ok) {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        v.vibrate(ok ? VibrationEffect.createWaveform(new long[]{0, 18, 40, 30}, new int[]{0, 120, 0, 255}, -1)
                     : VibrationEffect.createWaveform(new long[]{0, 40, 60, 40}, new int[]{0, 255, 0, 255}, -1));
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() { if (detector != null) detector.stop(); if (pad != null) hidePad(); super.onDestroy(); }

    public static boolean isOn(Context c) {
        String s = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/" + FlickService.class.getName());
    }
}
