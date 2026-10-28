package com.hermes.xmcam.oemderived.compat;

import java.util.WeakHashMap;

/**
 * Pure-Java core of the Surface registry (no android.* types, so it is host-testable).
 *
 * Replaces the hidden android.hardware.camera2.utils.SurfaceUtils queries (denied by the phone runtime)
 * with facts recorded at creation time from public APIs only:
 *   - ImageReader surfaces: size and format from ImageReader.getWidth/getHeight/getImageFormat;
 *   - SurfaceTexture surfaces: size from SurfaceTexture.setDefaultBufferSize (recorded per texture),
 *     the Surface being linked to its texture at Surface(SurfaceTexture) creation;
 *   - MediaCodec input surfaces: flagged as hardware video encoder surfaces, size unknown.
 * Keys are compared by identity (WeakHashMap over objects that do not override equals/hashCode).
 */
public final class SurfaceRegistryLogic {

    /** HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED: format Camera2 uses for SurfaceTexture/preview outputs. */
    public static final int FMT_IMPLEMENTATION_DEFINED = 0x22;

    static final int KIND_READER = 1;
    static final int KIND_TEXTURE = 2;
    static final int KIND_ENCODER = 3;

    /** Result of a lookup. hit=false means the surface is not in the registry. */
    public static final class Resolved {
        public final boolean hit;
        public final int width;
        public final int height;
        public final int format;
        public final boolean hwEncoder;

        Resolved(boolean hit, int width, int height, int format, boolean hwEncoder) {
            this.hit = hit;
            this.width = width;
            this.height = height;
            this.format = format;
            this.hwEncoder = hwEncoder;
        }
    }

    private static final class Entry {
        final int kind;
        final int width;
        final int height;
        final int format;
        final Object texture; // only for KIND_TEXTURE

        Entry(int kind, int width, int height, int format, Object texture) {
            this.kind = kind;
            this.width = width;
            this.height = height;
            this.format = format;
            this.texture = texture;
        }
    }

    private final WeakHashMap<Object, Entry> surfaces = new WeakHashMap<Object, Entry>();
    private final WeakHashMap<Object, int[]> textureSizes = new WeakHashMap<Object, int[]>();
    private int lastW = 0;
    private int lastH = 0;

    /** Records an ImageReader output surface with its public size and format. */
    public synchronized void putReader(Object surface, int width, int height, int format) {
        surfaces.put(surface, new Entry(KIND_READER, width, height, format, null));
        if (width > 0 && height > 0) {
            lastW = width;
            lastH = height;
        }
    }

    /** Records a MediaCodec input surface (hardware video encoder, size not known here). */
    public synchronized void putEncoder(Object surface) {
        surfaces.put(surface, new Entry(KIND_ENCODER, 0, 0, FMT_IMPLEMENTATION_DEFINED, null));
    }

    /** Links a Surface created from a SurfaceTexture to that texture; the size is read from the texture record. */
    public synchronized void linkTexture(Object surface, Object texture) {
        surfaces.put(surface, new Entry(KIND_TEXTURE, 0, 0, FMT_IMPLEMENTATION_DEFINED, texture));
    }

    /** Records the default buffer size set on a SurfaceTexture (applies to every Surface linked to it). */
    public synchronized void setTextureSize(Object texture, int width, int height) {
        textureSizes.put(texture, new int[] {width, height});
        if (width > 0 && height > 0) {
            lastW = width;
            lastH = height;
        }
    }

    /** Looks up a surface. A miss returns hit=false with the last known size as a safe default. */
    public synchronized Resolved resolve(Object surface) {
        Entry e = surfaces.get(surface);
        if (e == null) {
            return new Resolved(false, lastOrDefaultW(), lastOrDefaultH(), FMT_IMPLEMENTATION_DEFINED, false);
        }
        switch (e.kind) {
            case KIND_READER:
                return new Resolved(true, e.width, e.height, e.format, false);
            case KIND_ENCODER:
                return new Resolved(true, lastOrDefaultW(), lastOrDefaultH(), e.format, true);
            case KIND_TEXTURE:
                int[] sz = textureSizes.get(e.texture);
                if (sz == null || sz[0] <= 0 || sz[1] <= 0) {
                    return new Resolved(false, lastOrDefaultW(), lastOrDefaultH(), e.format, false);
                }
                return new Resolved(true, sz[0], sz[1], e.format, false);
            default:
                return new Resolved(false, lastOrDefaultW(), lastOrDefaultH(), FMT_IMPLEMENTATION_DEFINED, false);
        }
    }

    /** Fallback used on a miss: last size seen, or the phone's logged preview size 1440x1080. */
    synchronized int lastOrDefaultW() {
        return lastW > 0 ? lastW : 1440;
    }

    synchronized int lastOrDefaultH() {
        return lastH > 0 ? lastH : 1080;
    }
}
