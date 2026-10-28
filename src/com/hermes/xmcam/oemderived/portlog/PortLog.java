package com.hermes.xmcam.oemderived.portlog;

import android.app.Application;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Log;
import android.util.SparseIntArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * stage21 v2 user-visible process log (no ADB needed).
 *
 * What it records: this process only. A header (app version, device model, fingerprint, SDK,
 * public camera ids seen through CameraManager), this process's own logcat lines
 * ("logcat --pid=<mypid>", readable by an ordinary app for its own process), explicit camera
 * open/device error events with CameraAccessException reason codes and stack traces, the role map
 * before/after the LENS_FACING fallback, dialog shown, and uncaught exceptions.
 *
 * Where: MediaStore.Downloads (API 29+), file "Download/XMCam-port-log.txt". If that name already
 * exists, this session uses "XMCam-port-log-<yyyyMMdd-HHmmss>.txt". No storage permission needed.
 * The whole file is rewritten at each flush (bounded size, header kept, oldest lines dropped).
 *
 * Privacy: no personal data is collected on purpose. Logcat lines whose text mentions location,
 * GPS, sensors, Wi-Fi, Bluetooth, telephony or contacts are dropped before they reach the file.
 *
 * Test-only hook: AutoTest may set simulateOpenFailure(true) when the launch extra
 * xmcam_autotest_fail_open is set. That makes the next camera open throw a CameraAccessException
 * before the real openCamera call. Never set in a normal launch.
 */
public final class PortLog {
    static final String TAG = "XMCAM_PORTLOG";
    static final String BUILD_ID = "stage21-v4-SESSIONREG-BUILD_ONLY_UNTESTED";
    static final String BASE_NAME = "XMCam-port-log.txt";
    static final int MAX_CHARS = 900 * 1024;
    static final long FLUSH_DELAY_MS = 1500L;
    static final long PERIODIC_MS = 5000L;

    private static final Object LOCK = new Object();
    private static final Object WRITE_LOCK = new Object();
    private static final StringBuilder HEADER = new StringBuilder();
    private static final StringBuilder BODY = new StringBuilder();
    private static boolean installed;
    private static volatile boolean simulateOpenFailure;
    private static volatile boolean simulateAlways;
    private static ContentResolver resolver;
    private static volatile Uri fileUri;
    private static String fileName = BASE_NAME;
    private static HandlerThread worker;
    private static Handler handler;
    private static boolean flushScheduled;
    private static java.lang.Process logcatProcess;

    private static final String[] DROP_WORDS = {
            "location", "gps", "geocod", "sensor", "wifi", "bluetooth", "telephony", "imei",
            "contact", "sms", "accelerometer", "gyroscope"
    };

    private PortLog() {
    }

    /** Called first thing in CameraAppImpl.onCreate. Idempotent; never throws. */
    public static void install(final Application app) {
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            installed = true;
        }
        try {
            resolver = app.getContentResolver();
            worker = new HandlerThread("XMCamPortLog");
            worker.start();
            handler = new Handler(worker.getLooper());
            synchronized (LOCK) {
                HEADER.append("XMCam port log, build=").append(BUILD_ID).append('\n');
                HEADER.append("process-only log; no personal data by design\n");
                HEADER.append("session start: ").append(stamp(System.currentTimeMillis())).append('\n');
                HEADER.append("pid=").append(Process.myPid()).append('\n');
                HEADER.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(" | fingerprint=").append(Build.FINGERPRINT)
                        .append(" | SDK_INT=").append(Build.VERSION.SDK_INT)
                        .append(" release=").append(Build.VERSION.RELEASE).append('\n');
                HEADER.append("app: ").append(versionLine(app)).append('\n');
            }
            installUncaughtHandler();
            handler.post(new Runnable() {
                @Override
                public void run() {
                    setupFile();
                    appendCameraIds(app);
                    startLogcatReader();
                    flushNow();
                }
            });
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    periodicFlush();
                }
            }, PERIODIC_MS);
        } catch (Throwable t) {
            Log.e(TAG, "PortLog install failed", t);
        }
    }

    private static String versionLine(Application app) {
        try {
            android.content.pm.PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return app.getPackageName() + " versionName=" + pi.versionName + " versionCode=" + pi.getLongVersionCode();
        } catch (Throwable t) {
            return app.getPackageName() + " (version unavailable: " + t + ")";
        }
    }

    private static void installUncaughtHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    error("UNCAUGHT thread=" + t.getName(), e);
                    flushNow();
                } catch (Throwable ignored) {
                    // never mask the original crash
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    private static void setupFile() {
        try {
            String base = BASE_NAME;
            String name = base;
            Cursor c = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{base, "Download%"}, null);
            boolean exists = false;
            if (c != null) {
                try {
                    exists = c.getCount() > 0;
                } finally {
                    c.close();
                }
            }
            if (exists) {
                name = "XMCam-port-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
            Uri u = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) {
                Log.e(TAG, "MediaStore insert returned null for " + name);
                return;
            }
            fileName = name;
            fileUri = u;
            Log.i(TAG, "log file: Download/" + name);
        } catch (Throwable t) {
            Log.e(TAG, "log file setup failed", t);
        }
    }

    private static void appendCameraIds(Application app) {
        try {
            CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = com.hermes.xmcam.oemderived.compat.CameraIdCompat.listIds(cm);
            StringBuilder sb = new StringBuilder("camera ids (public API): ");
            for (String id : ids) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer lf = cc.get(CameraCharacteristics.LENS_FACING);
                sb.append('[').append(id).append(" lensFacing=").append(lf);
                if (Build.VERSION.SDK_INT >= 29) {
                    sb.append(" physical=").append(cc.getPhysicalCameraIds());
                }
                sb.append("] ");
            }
            event(sb.toString().trim());
        } catch (Throwable t) {
            error("camera id listing failed", t);
        }
    }

    private static void startLogcatReader() {
        try {
            String[] cmd = {"logcat", "-v", "threadtime", "--pid=" + Process.myPid()};
            java.lang.Process p = Runtime.getRuntime().exec(cmd);
            logcatProcess = p;
            final BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String s;
                        while ((s = r.readLine()) != null) {
                            if (keepLogcatLine(s)) {
                                append("logcat " + s);
                            }
                        }
                    } catch (Throwable t) {
                        append("logcat reader stopped: " + t);
                    }
                }
            }, "XMCamPortLogcat");
            reader.setDaemon(true);
            reader.start();
        } catch (Throwable t) {
            error("logcat reader not started", t);
        }
    }

    static boolean keepLogcatLine(String s) {
        if (s.contains("XMCAM_PORTLOG")) {
            return false; // already written by event()
        }
        String lower = s.toLowerCase(Locale.ROOT);
        for (String w : DROP_WORDS) {
            if (lower.contains(w)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- public event API

    /** Plain event line (no stack). */
    public static void event(String msg) {
        append(msg);
        Log.i(TAG, msg);
    }

    /** Exception with full stack trace, written at once. */
    public static void error(String where, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("ERROR " + where + ": " + t);
        t.printStackTrace(pw);
        pw.flush();
        append(sw.toString().trim());
        Log.e(TAG, "ERROR " + where, t);
        flushNow();
    }

    /** Camera open attempt failed (called from OpenCameraCallable catch blocks). */
    public static void openFailure(String cid, Throwable t) {
        String detail = "";
        if (t instanceof CameraAccessException) {
            CameraAccessException cae = (CameraAccessException) t;
            detail = " reason=" + cae.getReason() + " (" + reasonName(cae.getReason()) + ")";
        }
        error("OPEN_FAIL cid=" + cid + " exception=" + t.getClass().getName() + detail, t);
    }

    /** Device-level error from CameraDevice.StateCallback.onError (called from CameraStateCallback). */
    public static void deviceError(CameraDevice device, int code) {
        String id = "?";
        try {
            id = device.getId();
        } catch (Throwable ignored) {
            // device may already be closed
        }
        event("DEVICE_ERROR cid=" + id + " code=" + code + " (" + deviceErrorName(code) + ")");
        flushNow();
    }

    /** Dialog shown to the user (called from Util.showErrorScreen). */
    public static void dialog(int errorType) {
        event("DIALOG_SHOWN errorType=" + errorType + " (OEM 'cannot connect' hint; countdown then close)");
        flushNow();
    }

    /** Role map snapshot. Keys are roles, values are camera ids (see Camera2CompatAdapterRole.init). */
    public static void roleMap(String when, SparseIntArray map) {
        StringBuilder sb = new StringBuilder("ROLE_MAP ").append(when).append(" size=");
        if (map == null) {
            sb.append("null");
        } else {
            sb.append(map.size()).append(" {");
            for (int i = 0; i < map.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("role ").append(map.keyAt(i)).append(" -> id ").append(map.valueAt(i));
            }
            sb.append('}');
        }
        event(sb.toString());
    }

    /** Test-only switch, set from AutoTest only when the launch extra asks for it. */
    public static void setSimulateOpenFailure(boolean on) {
        simulateOpenFailure = on;
        event("SIMULATE_OPEN_FAILURE=" + on + " (test-only)");
    }

    /**
     * Called right before CameraManager.openCamera in OpenCameraCallable (inside its try block).
     * Throws only when the test switch is on, so the real open path is unchanged otherwise.
     */
    public static void beforeOpen(String cid) throws CameraAccessException {
        event("OPEN_START cid=" + cid + " simulate=" + (simulateOpenFailure || simulateAlways));
        if (simulateAlways) {
            throw new CameraAccessException(CameraAccessException.CAMERA_IN_USE,
                    "SIMULATED open failure (always) for test (xmcam_autotest_fail_always)");
        }
        if (simulateOpenFailure) {
            simulateOpenFailure = false;
            throw new CameraAccessException(CameraAccessException.CAMERA_IN_USE,
                    "SIMULATED open failure for test (xmcam_autotest_fail_open)");
        }
    }

    /** Test-only: every open fails (drives the OEM error dialog path). Set only by AutoTest extra. */
    public static void setSimulateAlways(boolean on) {
        simulateAlways = on;
        event("SIMULATE_OPEN_FAILURE_ALWAYS=" + on + " (test-only)");
    }

    // ---------------------------------------------------------------- buffer and file

    private static void append(String msg) {
        String line = stamp(System.currentTimeMillis()) + " " + msg + "\n";
        // Events can arrive before Application.onCreate (ContentProvider/static init). Keep them
        // in BODY; the file is only written once setupFile() has a Uri.
        synchronized (LOCK) {
            BODY.append(line);
            trimIfNeeded();
        }
        scheduleFlush();
    }

    /** Device opened OK (called from CameraStateCallback.onOpened). */
    public static void opened(CameraDevice device) {
        String id = "?";
        try {
            id = device.getId();
        } catch (Throwable ignored) {
            // device may already be closed
        }
        event("DEVICE_OPENED cid=" + id);
    }

    /** Device disconnected (called from CameraStateCallback.onDisconnected). */
    public static void disconnected(CameraDevice device) {
        String id = "?";
        try {
            id = device.getId();
        } catch (Throwable ignored) {
            // device may already be closed
        }
        event("DEVICE_DISCONNECTED cid=" + id);
        flushNow();
    }

    /** Drops the oldest body lines so header+body stays within MAX_CHARS. Caller holds LOCK. */
    private static void trimIfNeeded() {
        int excess = HEADER.length() + BODY.length() - MAX_CHARS;
        if (excess <= 0) {
            return;
        }
        int cut = Math.min(BODY.length(), excess + 96);
        int nl = BODY.indexOf("\n", cut);
        BODY.delete(0, nl < 0 ? cut : nl + 1);
        BODY.insert(0, "[... older lines dropped to bound file size ...]\n");
    }

    private static void scheduleFlush() {
        Handler h = handler;
        if (h == null) {
            return;
        }
        synchronized (LOCK) {
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    flushScheduled = false;
                }
                flushNow();
            }
        }, FLUSH_DELAY_MS);
    }

    private static void periodicFlush() {
        flushNow();
        Handler h = handler;
        if (h != null) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    periodicFlush();
                }
            }, PERIODIC_MS);
        }
    }

    /** Rewrites the whole file. Safe from any thread; serialized. */
    static void flushNow() {
        Uri u = fileUri;
        if (u == null || resolver == null) {
            return;
        }
        synchronized (WRITE_LOCK) {
            String text;
            synchronized (LOCK) {
                text = HEADER.toString() + BODY.toString();
            }
            try (OutputStream os = resolver.openOutputStream(u, "wt")) {
                if (os == null) {
                    Log.e(TAG, "openOutputStream returned null");
                    return;
                }
                os.write(text.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (Throwable t) {
                Log.e(TAG, "flush failed", t);
            }
        }
    }

    static String stamp(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(ms));
    }

    static String reasonName(int r) {
        switch (r) {
            case CameraAccessException.CAMERA_DISABLED: return "CAMERA_DISABLED";
            case CameraAccessException.CAMERA_DISCONNECTED: return "CAMERA_DISCONNECTED";
            case CameraAccessException.CAMERA_ERROR: return "CAMERA_ERROR";
            case CameraAccessException.CAMERA_IN_USE: return "CAMERA_IN_USE";
            default: return "unknown";
        }
    }

    static String deviceErrorName(int code) {
        switch (code) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE: return "ERROR_CAMERA_IN_USE";
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE: return "ERROR_MAX_CAMERAS_IN_USE";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED: return "ERROR_CAMERA_DISABLED";
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE: return "ERROR_CAMERA_DEVICE";
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE: return "ERROR_CAMERA_SERVICE";
            default: return "unknown";
        }
    }
}
