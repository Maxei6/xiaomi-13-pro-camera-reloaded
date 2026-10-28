package com.hermes.xmcam.oemderived.compat;

/**
 * App-owned monotone cubic (Fritsch-Carlson) interpolation. The OEM zoom UI called the hidden
 * android.util.Spline.createMonotoneCubicSpline, which Android 16 no longer links (NoSuchMethodError
 * on the first preview frame). Same call shape as the OEM code uses: static create(float[],float[])
 * and interpolate(float). Pure Java, no Android dependency.
 */
public final class MonotoneSpline {

    private final float[] mX;
    private final float[] mY;
    private final float[] mM;

    private MonotoneSpline(float[] x, float[] y, float[] m) {
        mX = x;
        mY = y;
        mM = m;
    }

    /** Same name and signature as android.util.Spline#createMonotoneCubicSpline for 1:1 smali rewrite. */
    public static MonotoneSpline createMonotoneCubicSpline(float[] x, float[] y) {
        if (x == null || y == null || x.length != y.length || x.length < 2) {
            throw new IllegalArgumentException("There must be at least two control points and the arrays must be of equal length.");
        }
        final int n = x.length;
        float[] d = new float[n - 1];
        float[] m = new float[n];
        for (int i = 0; i < n - 1; i++) {
            float h = x[i + 1] - x[i];
            if (h <= 0f) {
                throw new IllegalArgumentException("The control points must all have strictly increasing X values.");
            }
            d[i] = (y[i + 1] - y[i]) / h;
        }
        m[0] = d[0];
        for (int i = 1; i < n - 1; i++) {
            m[i] = (d[i - 1] + d[i]) * 0.5f;
        }
        m[n - 1] = d[n - 2];
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0f) {
                m[i] = 0f;
                m[i + 1] = 0f;
            } else {
                float a = m[i] / d[i];
                float b = m[i + 1] / d[i];
                if (a < 0f || b < 0f) {
                    throw new IllegalArgumentException("The control points must have monotonic Y values.");
                }
                float h = (float) Math.hypot(a, b);
                if (h > 3f) {
                    float t = 3f / h;
                    m[i] *= t;
                    m[i + 1] *= t;
                }
            }
        }
        return new MonotoneSpline(x, y, m);
    }

    public float interpolate(float x) {
        final int n = mX.length;
        if (Float.isNaN(x)) {
            return x;
        }
        if (x <= mX[0]) {
            return mY[0];
        }
        if (x >= mX[n - 1]) {
            return mY[n - 1];
        }
        int i = 0;
        while (x >= mX[i + 1]) {
            i += 1;
            if (x == mX[i]) {
                return mY[i];
            }
        }
        float h = mX[i + 1] - mX[i];
        float t = (x - mX[i]) / h;
        return (mY[i] * (1 + 2 * t) + h * mM[i] * t) * (1 - t) * (1 - t)
                + (mY[i + 1] * (3 - 2 * t) + h * mM[i + 1] * (t - 1)) * t * t;
    }
}
