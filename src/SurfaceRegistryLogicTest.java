package com.hermes.xmcam.oemderived.compat;

/**
 * Host test for SurfaceRegistryLogic (stage21 v5). No Android runtime: keys are plain identity objects.
 * Run: java -ea -cp <classes> com.hermes.xmcam.oemderived.compat.SurfaceRegistryLogicTest
 */
public final class SurfaceRegistryLogicTest {
    private static int passed;
    private static int total;

    private static void check(String name, boolean ok, String detail) {
        total++;
        if (ok) {
            passed++;
        }
        System.out.println((ok ? "PASS " : "FAIL ") + name + " " + detail);
    }

    public static void main(String[] args) {
        // 1. ImageReader surface: size/format recorded at creation, hit=true
        SurfaceRegistryLogic r = new SurfaceRegistryLogic();
        Object reader1 = new Object();
        r.putReader(reader1, 1440, 1080, 0x23);
        SurfaceRegistryLogic.Resolved a = r.resolve(reader1);
        check("reader hit", a.hit && a.width == 1440 && a.height == 1080 && a.format == 0x23 && !a.hwEncoder,
                "w=" + a.width + " h=" + a.height + " fmt=" + a.format + " hit=" + a.hit);

        // 2. texture linked BEFORE its default size is set: size resolves after setDefaultBufferSize
        Object tex = new Object();
        Object surfTex = new Object();
        r.linkTexture(surfTex, tex);
        SurfaceRegistryLogic.Resolved before = r.resolve(surfTex);
        check("texture miss before size", !before.hit, "hit=" + before.hit);
        r.setTextureSize(tex, 1080, 2400);
        SurfaceRegistryLogic.Resolved b = r.resolve(surfTex);
        check("texture size after set", b.hit && b.width == 1080 && b.height == 2400
                && b.format == SurfaceRegistryLogic.FMT_IMPLEMENTATION_DEFINED,
                "w=" + b.width + " h=" + b.height + " fmt=" + b.format);

        // 3. two Surfaces on one texture share the texture size; a later resize propagates
        Object surfTex2 = new Object();
        r.linkTexture(surfTex2, tex);
        r.setTextureSize(tex, 720, 1280);
        SurfaceRegistryLogicTest.checkSize(r, surfTex, 720, 1280, "resize propagates to first surface");
        SurfaceRegistryLogicTest.checkSize(r, surfTex2, 720, 1280, "second surface shares texture size");

        // 4. texture size set BEFORE the Surface is linked is also found
        Object tex2 = new Object();
        r.setTextureSize(tex2, 640, 480);
        Object surfTex3 = new Object();
        r.linkTexture(surfTex3, tex2);
        SurfaceRegistryLogicTest.checkSize(r, surfTex3, 640, 480, "size set before link");

        // 5. encoder surface: hwEncoder=true, hit=true
        Object enc = new Object();
        r.putEncoder(enc);
        SurfaceRegistryLogic.Resolved e = r.resolve(enc);
        check("encoder hw flag", e.hit && e.hwEncoder, "hit=" + e.hit + " hw=" + e.hwEncoder);

        // 6. reader surface is not a hw encoder
        check("reader not hw", !r.resolve(reader1).hwEncoder, "hw=" + r.resolve(reader1).hwEncoder);

        // 7. unknown surface: miss, hw=false, fallback size = last known size (640x480 from step 4)
        Object unknown = new Object();
        SurfaceRegistryLogic.Resolved u = r.resolve(unknown);
        check("unknown is a miss", !u.hit && !u.hwEncoder, "hit=" + u.hit + " hw=" + u.hwEncoder);
        check("unknown falls back to last known size", u.width == 640 && u.height == 480,
                "w=" + u.width + " h=" + u.height);

        // 8. fresh registry: fallback default 1440x1080 when nothing was ever recorded
        SurfaceRegistryLogic empty = new SurfaceRegistryLogic();
        SurfaceRegistryLogic.Resolved d = empty.resolve(new Object());
        check("empty registry default 1440x1080", !d.hit && d.width == 1440 && d.height == 1080,
                "w=" + d.width + " h=" + d.height);

        // 9. a texture that was never sized is a miss, not a zero size
        Object tex3 = new Object();
        Object surfTex4 = new Object();
        r.linkTexture(surfTex4, tex3);
        SurfaceRegistryLogic.Resolved z = r.resolve(surfTex4);
        check("unsized texture is a miss with non-zero size", !z.hit && z.width > 0 && z.height > 0,
                "hit=" + z.hit + " w=" + z.width + " h=" + z.height);

        System.out.println("RESULT: " + passed + "/" + total + " passed");
        if (passed != total) {
            System.exit(1);
        }
    }

    private static void checkSize(SurfaceRegistryLogic r, Object s, int w, int h, String name) {
        SurfaceRegistryLogic.Resolved x = r.resolve(s);
        check(name, x.hit && x.width == w && x.height == h, "w=" + x.width + " h=" + x.height + " hit=" + x.hit);
    }
}
