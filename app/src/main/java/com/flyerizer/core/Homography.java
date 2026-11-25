// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.core;

/** A perspective mapping between two quadrilaterals, as a 3 by 3 matrix, row by row. */
public final class Homography {
    public final double[] m;

    private Homography(double[] m) {
        this.m = m;
    }

    /** The mapping that sends each of the four source points to the matching destination point. */
    public static Homography fromQuads(double[][] src, double[][] dst) {
        double[][] a = new double[8][9];
        for (int k = 0; k < 4; k++) {
            double x = src[k][0], y = src[k][1], u = dst[k][0], v = dst[k][1];
            a[2 * k] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            a[2 * k + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        for (int col = 0; col < 8; col++) {                 // Gaussian elimination with the largest pivot
            int piv = col;
            for (int r = col + 1; r < 8; r++) if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
            if (Math.abs(a[piv][col]) < 1e-12) throw new ArithmeticException("the four corners do not make a shape");
            double[] t = a[col];
            a[col] = a[piv];
            a[piv] = t;
            for (int r = 0; r < 8; r++) {
                if (r == col) continue;
                double f = a[r][col] / a[col][col];
                if (f != 0) for (int c = col; c < 9; c++) a[r][c] -= f * a[col][c];
            }
        }
        double[] m = new double[9];
        for (int i = 0; i < 8; i++) m[i] = a[i][8] / a[i][i];
        m[8] = 1;
        return new Homography(m);
    }

    /** The point (x, y) after the mapping. */
    public double[] apply(double x, double y) {
        double den = m[6] * x + m[7] * y + m[8];
        return new double[]{(m[0] * x + m[1] * y + m[2]) / den, (m[3] * x + m[4] * y + m[5]) / den};
    }

    public Homography inverse() {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-18) throw new ArithmeticException("the four corners do not make a shape");
        double[] o = {
            A, -(b * i - c * h), b * f - c * e,
            B, a * i - c * g, -(a * f - c * d),
            C, -(a * h - b * g), a * e - b * d};
        for (int k = 0; k < 9; k++) o[k] /= det;
        return new Homography(o);
    }
}
