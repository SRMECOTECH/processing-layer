package com.srm.gps.processing.state;

/**
 * Constant-velocity Kalman filter for a vehicle on a local flat plane (metres east / north of an origin).
 *
 *   state x = [east, north, velocityEast, velocityNorth]
 *   predict: x = F x,          P = F P Fᵀ + Q     (Q: white-noise acceleration, sigmaAcceleration m/s²)
 *   update:  K = P Hᵀ (H P Hᵀ + R)⁻¹,  x += K (z - H x),  P = (I - K H) P (Joseph form, stays symmetric)
 *
 * Measurements whose Mahalanobis distance is beyond the 99.9% chi-square bound for 2 degrees of freedom are
 * rejected as outliers (a GPS glitch must not drag the smoothed track); the caller resets the filter after a
 * few rejections in a row, because by then the vehicle really is somewhere else.
 *
 * Immutable: every step returns a new filter, so it can be stored and restored as plain numbers.
 */
public record KalmanFilter2D(double[] x, double[] p) {

    /** 99.9% bound of chi-square with 2 degrees of freedom. */
    public static final double OUTLIER_GATE = 13.82;

    public static KalmanFilter2D start(double east, double north, double measurementSigmaMeters) {
        double r = measurementSigmaMeters * measurementSigmaMeters;
        double[] p = new double[16];
        p[0] = r;
        p[5] = r;
        p[10] = 100;   // velocity unknown: sigma 10 m/s
        p[15] = 100;
        return new KalmanFilter2D(new double[]{east, north, 0, 0}, p);
    }

    public KalmanFilter2D predict(double dtSeconds, double sigmaAcceleration) {
        double dt = Math.max(0, dtSeconds);
        double[] f = identity();
        f[2] = dt;   // east += vEast * dt
        f[7] = dt;   // north += vNorth * dt
        double[] nextX = multiplyVector(f, x);

        double q = sigmaAcceleration * sigmaAcceleration;
        double dt2 = dt * dt;
        double dt3 = dt2 * dt / 2;
        double dt4 = dt2 * dt2 / 4;
        double[] processNoise = new double[16];
        processNoise[0] = dt4 * q;
        processNoise[2] = dt3 * q;
        processNoise[8] = dt3 * q;
        processNoise[10] = dt2 * q;
        processNoise[5] = dt4 * q;
        processNoise[7] = dt3 * q;
        processNoise[13] = dt3 * q;
        processNoise[15] = dt2 * q;

        double[] nextP = add(multiply(multiply(f, p), transpose(f)), processNoise);
        return new KalmanFilter2D(nextX, nextP);
    }

    /** Mahalanobis distance² of a measurement from the predicted position. */
    public double mahalanobisSquared(double east, double north, double measurementSigmaMeters) {
        double r = measurementSigmaMeters * measurementSigmaMeters;
        double s00 = p[0] + r;
        double s01 = p[1];
        double s10 = p[4];
        double s11 = p[5] + r;
        double det = s00 * s11 - s01 * s10;
        if (Math.abs(det) < 1e-12) {
            return 0;
        }
        double y0 = east - x[0];
        double y1 = north - x[1];
        double i00 = s11 / det;
        double i01 = -s01 / det;
        double i10 = -s10 / det;
        double i11 = s00 / det;
        return y0 * (i00 * y0 + i01 * y1) + y1 * (i10 * y0 + i11 * y1);
    }

    public KalmanFilter2D update(double east, double north, double measurementSigmaMeters) {
        double r = measurementSigmaMeters * measurementSigmaMeters;
        // S = H P Hᵀ + R  (2x2: the position block of P)
        double s00 = p[0] + r;
        double s01 = p[1];
        double s10 = p[4];
        double s11 = p[5] + r;
        double det = s00 * s11 - s01 * s10;
        if (Math.abs(det) < 1e-12) {
            return this;
        }
        double i00 = s11 / det;
        double i01 = -s01 / det;
        double i10 = -s10 / det;
        double i11 = s00 / det;

        // K = P Hᵀ S⁻¹  (4x2): P Hᵀ is the first two columns of P
        double[] k = new double[8];
        for (int row = 0; row < 4; row++) {
            double ph0 = p[row * 4];
            double ph1 = p[row * 4 + 1];
            k[row * 2] = ph0 * i00 + ph1 * i10;
            k[row * 2 + 1] = ph0 * i01 + ph1 * i11;
        }

        double y0 = east - x[0];
        double y1 = north - x[1];
        double[] nextX = new double[4];
        for (int row = 0; row < 4; row++) {
            nextX[row] = x[row] + k[row * 2] * y0 + k[row * 2 + 1] * y1;
        }

        // Joseph form: P = (I - K H) P (I - K H)ᵀ + K R Kᵀ
        double[] ikh = identity();
        for (int row = 0; row < 4; row++) {
            ikh[row * 4] -= k[row * 2];
            ikh[row * 4 + 1] -= k[row * 2 + 1];
        }
        double[] krk = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                krk[row * 4 + col] = r * (k[row * 2] * k[col * 2] + k[row * 2 + 1] * k[col * 2 + 1]);
            }
        }
        double[] nextP = add(multiply(multiply(ikh, p), transpose(ikh)), krk);
        return new KalmanFilter2D(nextX, nextP);
    }

    public double east() {
        return x[0];
    }

    public double north() {
        return x[1];
    }

    /** Speed of the filtered track in km/h. */
    public double speedKmph() {
        return Math.hypot(x[2], x[3]) * 3.6;
    }

    private static double[] identity() {
        double[] m = new double[16];
        m[0] = 1;
        m[5] = 1;
        m[10] = 1;
        m[15] = 1;
        return m;
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] out = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                double sum = 0;
                for (int i = 0; i < 4; i++) {
                    sum += a[row * 4 + i] * b[i * 4 + col];
                }
                out[row * 4 + col] = sum;
            }
        }
        return out;
    }

    private static double[] multiplyVector(double[] m, double[] v) {
        double[] out = new double[4];
        for (int row = 0; row < 4; row++) {
            out[row] = m[row * 4] * v[0] + m[row * 4 + 1] * v[1] + m[row * 4 + 2] * v[2] + m[row * 4 + 3] * v[3];
        }
        return out;
    }

    private static double[] transpose(double[] m) {
        double[] out = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out[col * 4 + row] = m[row * 4 + col];
            }
        }
        return out;
    }

    private static double[] add(double[] a, double[] b) {
        double[] out = new double[16];
        for (int i = 0; i < 16; i++) {
            out[i] = a[i] + b[i];
        }
        return out;
    }
}
