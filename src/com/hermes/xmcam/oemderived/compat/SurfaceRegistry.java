package com.hermes.xmcam.oemderived.compat;

import android.graphics.SurfaceTexture;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import com.hermes.xmcam.oemderived.portlog.PortLog;

/**
 * Public-API replacement for the hidden android.hardware.camera2.utils.SurfaceUtils (denied at runtime).
 * The OEM smali calls are rewritten to these static methods with the same signatures (stage21 v5 patch).
 * Creation sites record facts from public APIs (see SurfaceRegistryLogic); queries never call hidden code.
 * A query for a surface that was not recorded logs "SURFACEREG miss" and returns a safe default.
 */
public final class SurfaceRegistry {

    private static final String TAG = "mcam.surfacereg";
    private static final int MAX_DETAIL_LINES = 40;
    private static final SurfaceRegistryLogic LOGIC = new SurfaceRegistryLogic();
    private static int regLines = 0;
    private static int missCount = 0;

    private SurfaceRegistry() {
    }

    /** ImageReader.getSurface() replacement: same result, and records the reader size and format. */
    public static Surface surfaceOf(ImageReader reader) {
        Surface s = reader.getSurface();
        if (s != null) {
            int w = reader.getWidth();
            int h = reader.getHeight();
            int f = reader.getImageFormat();
            LOGIC.putReader(s, w, h, f);
            reg("reader " + w + "x" + h + " fmt=0x" + Integer.toHexString(f));
        }
        return s;
    }

    /** MediaCodec.createInputSurface() replacement: same result, flagged as hardware video encoder. */
    public static Surface inputSurfaceOf(MediaCodec codec) {
        Surface s = codec.createInputSurface();
        if (s != null) {
            LOGIC.putEncoder(s);
            reg("encoder input surface");
        }
        return s;
    }

    /** Called right after new Surface(SurfaceTexture): links the Surface to its texture. */
    public static void linkTexture(Surface surface, SurfaceTexture texture) {
        if (surface != null && texture != null) {
            LOGIC.linkTexture(surface, texture);
        }
    }

    /** SurfaceTexture.setDefaultBufferSize(II)V replacement: same effect, and records the size. */
    public static void setDefaultBufferSize(SurfaceTexture texture, int width, int height) {
        texture.setDefaultBufferSize(width, height);
        LOGIC.setTextureSize(texture, width, height);
        reg("texture default " + width + "x" + height);
    }

    /** SurfaceUtils.getSurfaceSize replacement (same signature). */
    public static Size getSurfaceSize(Surface surface) {
        SurfaceRegistryLogic.Resolved r = LOGIC.resolve(surface);
        if (!r.hit) {
            miss("size", surface);
        }
        return new Size(r.width, r.height);
    }

    /** SurfaceUtils.getSurfaceFormat replacement (same signature). */
    public static int getSurfaceFormat(Surface surface) {
        SurfaceRegistryLogic.Resolved r = LOGIC.resolve(surface);
        if (!r.hit) {
            miss("format", surface);
        }
        return r.format;
    }

    /** SurfaceUtils.isSurfaceForHwVideoEncoder replacement (same signature). Unknown surfaces return false. */
    public static boolean isSurfaceForHwVideoEncoder(Surface surface) {
        SurfaceRegistryLogic.Resolved r = LOGIC.resolve(surface);
        if (!r.hit) {
            miss("hwenc", surface);
            return false;
        }
        return r.hwEncoder;
    }

    private static synchronized void reg(String what) {
        if (regLines < MAX_DETAIL_LINES) {
            regLines++;
            String msg = "SURFACEREG reg " + what;
            PortLog.event(msg);
            Log.i(TAG, msg);
        }
    }

    private static synchronized void miss(String op, Object surface) {
        missCount++;
        if (missCount <= MAX_DETAIL_LINES || missCount % 50 == 0) {
            String msg = "SURFACEREG miss op=" + op + " n=" + missCount
                    + " surface=" + Integer.toHexString(System.identityHashCode(surface));
            PortLog.event(msg);
            Log.w(TAG, msg);
        }
    }
}
