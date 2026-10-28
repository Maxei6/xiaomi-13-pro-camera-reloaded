import com.hermes.xmcam.oemderived.compat.MonotoneSpline;

public class MonotoneSplineTest {
    static int pass = 0, total = 0;

    static void check(String name, boolean ok) {
        total++;
        if (ok) pass++;
        System.out.println((ok ? "PASS " : "FAIL ") + name);
    }

    static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    public static void main(String[] args) {
        float[] x = {0f, 1f, 2f, 4f};
        float[] y = {0f, 1f, 4f, 5f};
        MonotoneSpline s = MonotoneSpline.createMonotoneCubicSpline(x, y);
        check("knot values exact", near(s.interpolate(0f), 0f) && near(s.interpolate(1f), 1f)
                && near(s.interpolate(2f), 4f) && near(s.interpolate(4f), 5f));
        check("clamps outside range", near(s.interpolate(-3f), 0f) && near(s.interpolate(9f), 5f));
        boolean mono = true;
        float prev = s.interpolate(0f);
        for (float v = 0f; v <= 4f; v += 0.05f) {
            float c = s.interpolate(v);
            if (c + 1e-5f < prev) mono = false;
            prev = c;
        }
        check("monotone non-decreasing", mono);
        check("NaN passthrough", Float.isNaN(s.interpolate(Float.NaN)));
        boolean threw = false;
        try { MonotoneSpline.createMonotoneCubicSpline(new float[]{0f, 0f}, new float[]{0f, 1f}); }
        catch (IllegalArgumentException e) { threw = true; }
        check("non-increasing X rejected", threw);
        threw = false;
        try { MonotoneSpline.createMonotoneCubicSpline(new float[]{0f, 1f, 2f}, new float[]{0f, 2f, 1f}); }
        catch (IllegalArgumentException e) { threw = true; }
        check("non-monotone Y rejected", threw);
        MonotoneSpline lin = MonotoneSpline.createMonotoneCubicSpline(new float[]{0f, 2f}, new float[]{0f, 4f});
        check("two points linear", near(lin.interpolate(1f), 2f));
        System.out.println("RESULT: " + pass + "/" + total + " passed");
        if (pass != total) System.exit(1);
    }
}
