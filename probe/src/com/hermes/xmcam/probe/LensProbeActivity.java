package com.hermes.xmcam.probe;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.WindowManager;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lens-switching probe, public SDK only (stage22). For each (camera id, session operating mode) it opens the
 * camera, configures one YUV stream and sweeps CONTROL_ZOOM_RATIO, logging which physical camera the logical
 * camera reports, the applied zoom/crop and how much the picture changed. Results: logcat tag LENSPROBE.
 * No hidden API, no reflection, nothing written to storage.
 *
 * Intent extras (all optional): ids="0,5,6,2"  modes="0,32769,36866"  zooms="1.0,0.6,0.681,2.0,3.2,5.0,1.0"
 */
public class LensProbeActivity extends Activity {
    private static final String T = "LENSPROBE";
    private HandlerThread ht;
    private Handler h;
    private final AtomicReference<float[]> lastSig = new AtomicReference<>();
    private final AtomicInteger frames = new AtomicInteger();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView tv = new TextView(this);
        tv.setText("XMCam lens probe running - do not touch");
        tv.setTextSize(22f);
        setContentView(tv);
        if (checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED) {
            Log.i(T, "NO_CAMERA_PERMISSION");
            requestPermissions(new String[]{"android.permission.CAMERA"}, 9);
            return;
        }
        go();
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            go();
        } else {
            Log.i(T, "PERMISSION_DENIED");
            finish();
        }
    }

    private void go() {
        final String ids = getIntent().getStringExtra("ids");
        final String modes = getIntent().getStringExtra("modes");
        final String zooms = getIntent().getStringExtra("zooms");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runAll(ids == null ? "0,5,6,2" : ids, modes == null ? "0,32769,36866" : modes,
                            zooms == null ? "1.0,0.6,0.681,2.0,3.2,5.0,1.0" : zooms);
                } catch (Throwable t) {
                    Log.e(T, "FATAL_IN_PROBE " + t, t);
                }
                Log.i(T, "DONE");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                });
            }
        }, "lensprobe").start();
    }

    private void runAll(String idCsv, String modeCsv, String zoomCsv) throws Exception {
        ht = new HandlerThread("lensprobe-cam");
        ht.start();
        h = new Handler(ht.getLooper());
        CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        Log.i(T, "START ids=" + idCsv + " modes=" + modeCsv + " zooms=" + zoomCsv);
        String[] zs = zoomCsv.split(",");
        for (String id : idCsv.split(",")) {
            CameraCharacteristics cc;
            try {
                cc = cm.getCameraCharacteristics(id);
            } catch (Throwable t) {
                Log.i(T, "CAM " + id + " CHARS_FAIL " + t);
                continue;
            }
            Log.i(T, "CAM " + id + " zoomRange=" + cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    + " physical=" + cc.getPhysicalCameraIds()
                    + " focal=" + Arrays.toString(cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)));
            try {
                java.util.List<CaptureRequest.Key<?>> sk = cc.getAvailableSessionKeys();
                StringBuilder sb = new StringBuilder();
                if (sk != null) {
                    for (CaptureRequest.Key<?> k : sk) {
                        sb.append(k.getName()).append(' ');
                    }
                }
                Log.i(T, "SESSIONKEYS id=" + id + " n=" + (sk == null ? -1 : sk.size()) + " " + sb);
            } catch (Throwable t) {
                Log.i(T, "SESSIONKEYS_EXC " + t);
            }
            for (String m : modeCsv.split(",")) {
                try {
                    runCase(cm, id, cc, Integer.parseInt(m.trim()), zs);
                } catch (Throwable t) {
                    Log.i(T, "CASE id=" + id + " mode=" + m + " EXC " + t);
                }
                Thread.sleep(800);
            }
        }
        ht.quitSafely();
    }

    private final AtomicReference<Integer> lastAf = new AtomicReference<>();

    private Size pick(CameraCharacteristics cc) {
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = null;
        for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
            if (s.getWidth() * 3 != s.getHeight() * 4 || s.getWidth() > 1600) {
                continue;
            }
            if (best == null || s.getWidth() > best.getWidth()) {
                best = s;
            }
        }
        return best == null ? map.getOutputSizes(ImageFormat.YUV_420_888)[0] : best;
    }

    private void runCase(CameraManager cm, final String id, CameraCharacteristics cc, final int mode, String[] zs)
            throws Exception {
        final String tag = "id=" + id + " mode=0x" + Integer.toHexString(mode);
        final CountDownLatch opened = new CountDownLatch(1);
        final AtomicReference<CameraDevice> dev = new AtomicReference<>();
        cm.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice d) {
                dev.set(d);
                opened.countDown();
            }

            @Override
            public void onDisconnected(CameraDevice d) {
                Log.i(T, tag + " DISCONNECTED");
                d.close();
                opened.countDown();
            }

            @Override
            public void onError(CameraDevice d, int e) {
                Log.i(T, tag + " OPEN_ERROR " + e);
                d.close();
                opened.countDown();
            }
        }, h);
        if (!opened.await(8, TimeUnit.SECONDS) || dev.get() == null) {
            Log.i(T, tag + " OPEN_FAIL");
            return;
        }
        CameraDevice d = dev.get();
        Size sz = pick(cc);
        ImageReader ir = ImageReader.newInstance(sz.getWidth(), sz.getHeight(), ImageFormat.YUV_420_888, 3);
        frames.set(0);
        lastSig.set(null);
        ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                Image im = r.acquireLatestImage();
                if (im == null) {
                    return;
                }
                try {
                    lastSig.set(signature(im));
                    frames.incrementAndGet();
                } finally {
                    im.close();
                }
            }
        }, h);
        final CountDownLatch cfg = new CountDownLatch(1);
        final AtomicReference<CameraCaptureSession> ses = new AtomicReference<>();
        Executor ex = new Executor() {
            @Override
            public void execute(Runnable r) {
                h.post(r);
            }
        };
        try {
            SessionConfiguration sc = new SessionConfiguration(mode,
                    Collections.singletonList(new OutputConfiguration(ir.getSurface())), ex,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            ses.set(s);
                            cfg.countDown();
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            Log.i(T, tag + " CONFIGURE_FAILED");
                            cfg.countDown();
                        }
                    });
            d.createCaptureSession(sc);
        } catch (Throwable t) {
            Log.i(T, tag + " CREATE_EXC " + t);
            d.close();
            ir.close();
            return;
        }
        if (!cfg.await(12, TimeUnit.SECONDS) || ses.get() == null) {
            Log.i(T, tag + " NO_SESSION size=" + sz);
            d.close();
            ir.close();
            return;
        }
        Log.i(T, tag + " SESSION_OK size=" + sz);
        CameraCaptureSession s = ses.get();
        float[] base = null;
        for (String zt : zs) {
            final float z = Float.parseFloat(zt.trim());
            final AtomicReference<TotalCaptureResult> last = new AtomicReference<>();
            try {
                CaptureRequest.Builder rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                rb.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
                rb.addTarget(ir.getSurface());
                s.setRepeatingRequest(rb.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession ss, CaptureRequest rq, TotalCaptureResult r) {
                        last.set(r);
                    }
                }, h);
            } catch (Throwable t) {
                Log.i(T, tag + " z=" + z + " REQ_EXC " + t);
                continue;
            }
            Thread.sleep(2200);
            TotalCaptureResult r = last.get();
            float[] sig = lastSig.get();
            if (base == null && sig != null) {
                base = sig;
            }
            String phys = r == null ? "none" : String.valueOf(r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID));
            Log.i(T, tag + " z=" + z + " applied=" + (r == null ? "none" : r.get(CaptureResult.CONTROL_ZOOM_RATIO))
                    + " physActive=" + phys
                    + " crop=" + (r == null ? "none" : r.get(CaptureResult.SCALER_CROP_REGION))
                    + " focal=" + (r == null ? "none" : r.get(CaptureResult.LENS_FOCAL_LENGTH))
                    + " frames=" + frames.get()
                    + " luma=" + (sig == null ? "none" : String.format("%.1f", mean(sig)))
                    + " diffVsFirst=" + (sig == null || base == null ? "none" : String.format("%.1f", diff(sig, base))));
        }
        if ("1".equals(getIntent().getStringExtra("ae"))) {
            try {
                final CaptureRequest.Builder rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                rb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                String ab = getIntent().getStringExtra("anti");
                if (ab != null) {
                    rb.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, Integer.parseInt(ab));
                }
                rb.addTarget(ir.getSurface());
                final long t0 = System.currentTimeMillis();
                CameraCaptureSession.CaptureCallback cb = new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession ss, CaptureRequest rq, TotalCaptureResult r) {
                        Long e = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
                        Integer st = r.get(CaptureResult.CONTROL_AE_STATE);
                        Long fd = r.get(CaptureResult.SENSOR_FRAME_DURATION);
                        Log.i(T, tag + " AEFRAME t+" + (System.currentTimeMillis() - t0) + " exp_us=" + (e == null ? -1 : e / 1000)
                                + " iso=" + iso + " ae=" + st + " fd_ms=" + (fd == null ? -1 : fd / 1000000)
                                + " luma=" + (lastSig.get() == null ? -1 : (int) (mean(lastSig.get()) * 10)));
                    }
                };
                s.setRepeatingRequest(rb.build(), cb, h);
                Thread.sleep(8000);
                s.stopRepeating();
            } catch (Throwable t) {
                Log.i(T, tag + " AE_EXC " + t);
            }
        }
        if ("1".equals(getIntent().getStringExtra("af"))) {
            try {
                CaptureRequest.Builder rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                rb.addTarget(ir.getSurface());
                final long t0 = System.currentTimeMillis();
                CameraCaptureSession.CaptureCallback cb = new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession ss, CaptureRequest rq, TotalCaptureResult r) {
                        Integer st = r.get(CaptureResult.CONTROL_AF_STATE);
                        if (st != null && !st.equals(lastAf.get())) {
                            lastAf.set(st);
                            Log.i(T, tag + " AFSTATE " + st + " t+" + (System.currentTimeMillis() - t0));
                        }
                    }
                };
                s.setRepeatingRequest(rb.build(), cb, h);
                Thread.sleep(3000);
                Log.i(T, tag + " AF_STEADY_END (3s with no AF trigger)");
                CaptureRequest.Builder tb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                tb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                tb.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                tb.addTarget(ir.getSurface());
                Log.i(T, tag + " AF_TRIGGER_SENT");
                s.capture(tb.build(), cb, h);
                CaptureRequest.Builder ab = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                ab.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                ab.addTarget(ir.getSurface());
                s.setRepeatingRequest(ab.build(), cb, h);
                Thread.sleep(3500);
                Log.i(T, tag + " AF_CANCEL_SENT");
                CaptureRequest.Builder cb2 = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                cb2.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                cb2.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                cb2.addTarget(ir.getSurface());
                s.capture(cb2.build(), cb, h);
                s.setRepeatingRequest(rb.build(), cb, h);
                Thread.sleep(3000);
                Log.i(T, tag + " AF_TEST_END");
            } catch (Throwable t) {
                Log.i(T, tag + " AF_EXC " + t);
            }
        }
        try {
            s.stopRepeating();
        } catch (Throwable ignored) {
        }
        s.close();
        d.close();
        ir.close();
        Thread.sleep(500);
    }

    /** 8x8 grid of block-mean luma, used only to tell whether the picture changed between zoom steps. */
    private static float[] signature(Image im) {
        Image.Plane p = im.getPlanes()[0];
        ByteBuffer b = p.getBuffer();
        int w = im.getWidth();
        int hh = im.getHeight();
        int rs = p.getRowStride();
        float[] out = new float[64];
        for (int gy = 0; gy < 8; gy++) {
            for (int gx = 0; gx < 8; gx++) {
                long sum = 0;
                int n = 0;
                int x0 = gx * w / 8;
                int y0 = gy * hh / 8;
                for (int y = y0; y < y0 + hh / 8; y += 8) {
                    for (int x = x0; x < x0 + w / 8; x += 8) {
                        sum += b.get(y * rs + x) & 0xff;
                        n++;
                    }
                }
                out[gy * 8 + gx] = n == 0 ? 0 : (float) sum / n;
            }
        }
        return out;
    }

    private static float mean(float[] a) {
        float s = 0;
        for (float v : a) {
            s += v;
        }
        return s / a.length;
    }

    private static float diff(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) {
            s += Math.abs(a[i] - b[i]);
        }
        return s / a.length;
    }
}
