import com.hermes.xmcam.oemderived.compat.VendorKeyLogic;

import java.util.Arrays;
import java.util.List;

public class VendorKeyLogicTest {
    static int pass = 0, total = 0;

    static void check(String name, boolean ok) {
        total++;
        if (ok) pass++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    public static void main(String[] args) {
        VendorKeyLogic.TagProbe known = new VendorKeyLogic.TagProbe() {
            public boolean isKnown(String n) {
                return n.startsWith("xiaomi.");
            }
        };
        List<String> r = VendorKeyLogic.collect(
                Arrays.asList("android.control.mode", "com.qti.a.b", "xiaomi.x.y"),
                new String[]{"xiaomi.snapshot.imageName", "xiaomi.x.y", "android.jpeg.maxSize", "com.", "vendor.no.such", "plain"},
                known);
        check("android.* never listed", !r.contains("android.control.mode") && !r.contains("android.jpeg.maxSize"));
        check("public vendor name kept", r.contains("com.qti.a.b") && r.contains("xiaomi.x.y"));
        check("probe-resolved candidate added", r.contains("xiaomi.snapshot.imageName"));
        check("unknown candidate dropped", !r.contains("vendor.no.such"));
        check("fragment and dotless dropped", !r.contains("com.") && !r.contains("plain"));
        check("no duplicates", r.size() == 3);
        check("probe exception treated as unknown", VendorKeyLogic.collect(null, new String[]{"a.b"},
                new VendorKeyLogic.TagProbe() {
                    public boolean isKnown(String n) {
                        throw new IllegalStateException();
                    }
                }).isEmpty());
        check("nulls safe", VendorKeyLogic.collect(null, null, null).isEmpty());
        List<String> h = VendorKeyLogic.collect(
                Arrays.asList("com.xiaomi.scaler.availableStreamConfigurations", "xiaomi.scaler.availableHeicStreamConfigurations", "com.xiaomi.ok.key"),
                new String[]{"xiaomi.scaler.supernight.availableLimitStreamConfigurations", "xiaomi.snapshot.imageName"}, known);
        check("hidden-typed stream config tags excluded", h.size() == 2 && h.contains("com.xiaomi.ok.key") && h.contains("xiaomi.snapshot.imageName"));
        check("hidden-typed detector", VendorKeyLogic.isHiddenTypedName("org.codeaurora.qcamera3.quadra_cfa.availableStreamConfigurations")
                && !VendorKeyLogic.isHiddenTypedName("org.quic.camera2.streamconfigs.HDRVideoMode") && !VendorKeyLogic.isHiddenTypedName(null));
        System.out.println("RESULT: " + pass + "/" + total + " passed");
        if (pass != total) System.exit(1);
    }
}
