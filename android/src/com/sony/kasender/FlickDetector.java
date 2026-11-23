package com.sony.kasender;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

/**
 * Detects a deliberate wrist flick: a fast snap rotation (gyroscope) together with a hard
 * push (linear acceleration), both inside a short window. Normal handling — picking the phone
 * up, turning it to look, walking — stays far below these numbers.
 * Every strong movement is logged under tag "SKSFLICK" so the thresholds can be tuned.
 */
public class FlickDetector implements SensorEventListener {
    public interface Listener { void onFlick(float gyroPeak, float accPeak); }

    // thresholds (tuned on the Flip 7)
    public static float GYRO_MIN = 7.5f;    // rad/s  (~430°/s)
    public static float ACC_MIN = 13f;      // m/s² without gravity
    private static final long WINDOW_MS = 260;
    private static final long COOLDOWN_MS = 2500;
    private static final long WARMUP_MS = 700;

    private final SensorManager sm;
    private final Listener listener;
    private boolean running = false;
    private long startedAt = 0, lastFlick = 0, lastLog = 0;
    private float gyroPeak = 0, accPeak = 0;
    private long gyroPeakAt = 0, accPeakAt = 0;

    public FlickDetector(Context c, Listener l) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        listener = l;
    }

    public synchronized void start() {
        if (running || sm == null) return;
        Sensor g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor a = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (g == null || a == null) return;
        sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME);
        sm.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME);
        running = true;
        startedAt = System.currentTimeMillis();
        gyroPeak = accPeak = 0;
        Log.i("SKSFLICK", "armed");
    }

    public synchronized void stop() {
        if (!running) return;
        sm.unregisterListener(this);
        running = false;
        Log.i("SKSFLICK", "disarmed");
    }

    public boolean isRunning() { return running; }

    @Override public void onSensorChanged(SensorEvent e) {
        long now = System.currentTimeMillis();
        float x = e.values[0], y = e.values[1], z = e.values[2];
        float mag = (float) Math.sqrt(x * x + y * y + z * z);
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (now - gyroPeakAt > WINDOW_MS || mag > gyroPeak) { gyroPeak = mag; gyroPeakAt = now; }
        } else {
            if (now - accPeakAt > WINDOW_MS || mag > accPeak) { accPeak = mag; accPeakAt = now; }
        }
        boolean fresh = now - gyroPeakAt <= WINDOW_MS && now - accPeakAt <= WINDOW_MS;
        if (fresh && (gyroPeak > 3.5f || accPeak > 7f) && now - lastLog > 150) {
            lastLog = now;
            Log.i("SKSFLICK", String.format(java.util.Locale.US, "motion gyro=%.1f acc=%.1f", gyroPeak, accPeak));
        }
        if (now - startedAt < WARMUP_MS || now - lastFlick < COOLDOWN_MS) return;
        if (fresh && gyroPeak >= GYRO_MIN && accPeak >= ACC_MIN) {
            lastFlick = now;
            Log.i("SKSFLICK", String.format(java.util.Locale.US, "FLICK gyro=%.1f acc=%.1f", gyroPeak, accPeak));
            float gp = gyroPeak, ap = accPeak;
            gyroPeak = accPeak = 0;
            listener.onFlick(gp, ap);
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}
}
