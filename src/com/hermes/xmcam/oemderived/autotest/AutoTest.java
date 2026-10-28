package com.hermes.xmcam.oemderived.autotest;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/**
 * Stage21 verification trigger (NOT a product feature; remove before any release).
 *
 * The OEM capture path is unchanged: this only sends a short tap to the OEM shutter view
 * (resource "shutter_button", a CameraSnapView) through the public View.dispatchTouchEvent,
 * i.e. the same entry a finger uses. Enabled only by an explicit launch extra, so a normal launch is untouched:
 *
 *   am start -n <pkg>/com.android.camera.Camera --ez xmcam_autotest_shutter true \
 *            --ei xmcam_autotest_delay_ms 8000 --ei xmcam_autotest_count 1
 *
 * DOWN and UP are dispatched in the same message: a Handler-delayed UP was seen arriving ~900 ms late on
 * a cold start, which the snap view read as a long press (cancelled, no capture).
 * Logs under tag XMCAM_AUTOTEST (AUTOTEST_ARMED / AUTOTEST_SHOT / AUTOTEST_SKIP).
 * Public SDK only: Application lifecycle callbacks, Intent extras, MotionEvent/View dispatch.
 */
public final class AutoTest {
    private static final String TAG = "XMCAM_AUTOTEST";
    static final String EXTRA_SHUTTER = "xmcam_autotest_shutter";
    static final String EXTRA_DELAY = "xmcam_autotest_delay_ms";
    static final String EXTRA_COUNT = "xmcam_autotest_count";
    /** Test-only: simulate one failed camera open (for log verification). Never set by a normal launch. */
    static final String EXTRA_FAIL_OPEN = "xmcam_autotest_fail_open";
    /** Test-only: every camera open fails (exercises the OEM error dialog). Never set by a normal launch. */
    static final String EXTRA_FAIL_ALWAYS = "xmcam_autotest_fail_always";
    static final int INTERVAL_MS = 2500;
    static final int MAX_SHOTS = 5;
    static final int TAP_MS = 80;

    private static volatile boolean armed;

    private AutoTest() {}

    public static void install(final Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {
                Intent i = a.getIntent();
                if (i != null && i.getBooleanExtra(EXTRA_FAIL_ALWAYS, false)) {
                    com.hermes.xmcam.oemderived.portlog.PortLog.setSimulateAlways(true);
                }
                if (i != null && i.getBooleanExtra(EXTRA_FAIL_OPEN, false)) {
                    // test-only: the next OpenCameraCallable open throws CameraAccessException (logged, dialog shown)
                    com.hermes.xmcam.oemderived.portlog.PortLog.setSimulateOpenFailure(true);
                }
            }
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) { arm(a); }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    private static void arm(Activity a) {
        if (armed) {
            return;
        }
        Intent i = a.getIntent();
        if (i == null || !i.getBooleanExtra(EXTRA_SHUTTER, false)) {
            return;
        }
        armed = true;
        int delay = Math.max(0, i.getIntExtra(EXTRA_DELAY, 8000));
        int count = Math.min(MAX_SHOTS, Math.max(1, i.getIntExtra(EXTRA_COUNT, 1)));
        Log.i(TAG, "AUTOTEST_ARMED delay_ms=" + delay + " count=" + count + " activity=" + a.getClass().getName());
        new Handler(Looper.getMainLooper()).postDelayed(new Shot(a, count, 1), delay);
    }

    private static final class Shot implements Runnable {
        private final Activity activity;
        private final int total;
        private final int index;

        Shot(Activity activity, int total, int index) {
            this.activity = activity;
            this.total = total;
            this.index = index;
        }

        @Override public void run() {
            if (activity.isFinishing() || activity.isDestroyed()) {
                Log.i(TAG, "AUTOTEST_SKIP activity finishing index=" + index);
                return;
            }
            int id = activity.getResources().getIdentifier("shutter_button", "id", activity.getPackageName());
            View v = id == 0 ? null : activity.findViewById(id);
            if (v == null || v.getWidth() <= 0) {
                Log.i(TAG, "AUTOTEST_SKIP shutter view not laid out index=" + index);
            } else {
                float x = v.getWidth() / 2f;
                float y = v.getHeight() / 2f;
                long t = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
                boolean d = v.dispatchTouchEvent(down);
                down.recycle();
                MotionEvent up = MotionEvent.obtain(t, t + TAP_MS, MotionEvent.ACTION_UP, x, y, 0);
                boolean h = v.dispatchTouchEvent(up);
                up.recycle();
                Log.i(TAG, "AUTOTEST_SHOT index=" + index + "/" + total + " down_handled=" + d
                        + " up_handled=" + h + " enabled=" + v.isEnabled() + " class=" + v.getClass().getName());
            }
            if (index < total) {
                new Handler(Looper.getMainLooper()).postDelayed(new Shot(activity, total, index + 1), INTERVAL_MS);
            }
        }
    }
}
