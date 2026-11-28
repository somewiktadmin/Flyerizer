// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.core;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * The straightening itself.  The four corners define a perspective mapping, and the bends are blended in as a Coons-style
 * patch: a column's vertical position comes from the top and bottom curves, a row's horizontal position from the left and
 * right curves, all in the perspective-corrected frame.  The result is exactly the area the four corners enclose ("crop to
 * lines"), never scaled: corners at 0 and 99 give 100 pixels.
 */
public final class Warp {
    /** What fills the area a corner pulled past the photo's edge leaves empty. */
    public enum Fill { REFLECT, REPLICATE, BLACK }

    private Warp() {
    }

    /** {width, height} in pixels.  Both end pixels of each side count, so the photo's own corners give the photo's own size. */
    public static int[] outputSize(double[][] c) {
        double top = dist(c[0], c[1]), bottom = dist(c[3], c[2]);
        double left = dist(c[0], c[3]), right = dist(c[1], c[2]);
        int w = (int) Math.rint((top + bottom) / 2) + 1;
        int h = (int) Math.rint((left + right) / 2) + 1;
        return new int[]{Math.max(2, w), Math.max(2, h)};
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /** Sorted pairs, for looking up one coordinate from the other. */
    private static final class Curve {
        final double[] k, v;

        Curve(double[][] pts, boolean byX) {
            double[][] p = pts.clone();
            final int a = byX ? 0 : 1, b = byX ? 1 : 0;
            Arrays.sort(p, Comparator.comparingDouble(q -> q[a]));
            k = new double[p.length];
            v = new double[p.length];
            for (int i = 0; i < p.length; i++) {
                k[i] = p[i][a];
                v[i] = p[i][b];
            }
        }

        /** Linear interpolation that holds the end values outside the curve, like numpy's interp. */
        double at(double x) {
            int n = k.length;
            if (x <= k[0]) return v[0];
            if (x >= k[n - 1]) return v[n - 1];
            int lo = 0, hi = n - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (k[mid] <= x) lo = mid;
                else hi = mid;
            }
            double dx = k[hi] - k[lo];
            return dx == 0 ? v[lo] : v[lo] + (x - k[lo]) / dx * (v[hi] - v[lo]);
        }
    }

    /** The straightened picture, w by h as outputSize() says.  Throws ArithmeticException for corners that make no shape. */
    public static Raster render(Raster src, Quad q, Fill fill, boolean cubic) {
        int[] size = outputSize(q.c);
        final int w = size[0], h = size[1];
        double[][] dst = {{0, 0}, {w - 1, 0}, {w - 1, h - 1}, {0, h - 1}};
        Homography hm = Homography.fromQuads(q.c, dst);
        final double[] hi = hm.inverse().m;
        Curve[] curve = new Curve[4];
        for (int s = 0; s < 4; s++) {
            double[][] pts = q.sidePoints(s, 400);
            for (int i = 0; i < pts.length; i++) pts[i] = hm.apply(pts[i][0], pts[i][1]);
            curve[s] = new Curve(pts, s == Quad.TOP || s == Quad.BOTTOM);
        }
        final double[] yT = new double[w], yB = new double[w], xL = new double[h], xR = new double[h];
        for (int cx = 0; cx < w; cx++) {
            yT[cx] = curve[Quad.TOP].at(cx);
            yB[cx] = curve[Quad.BOTTOM].at(cx);
        }
        for (int ry = 0; ry < h; ry++) {
            xL[ry] = curve[Quad.LEFT].at(ry);
            xR[ry] = curve[Quad.RIGHT].at(ry);
        }
        final int[] out = new int[w * h];
        IntStream.range(0, h).parallel().forEach(ry -> {
            double v = ry / (double) (h - 1);
            for (int cx = 0; cx < w; cx++) {
                double u = cx / (double) (w - 1);
                double xr = xL[ry] + u * (xR[ry] - xL[ry]);
                double yr = yT[cx] + v * (yB[cx] - yT[cx]);
                double den = hi[6] * xr + hi[7] * yr + hi[8];
                double mx = (hi[0] * xr + hi[1] * yr + hi[2]) / den;
                double my = (hi[3] * xr + hi[4] * yr + hi[5]) / den;
                if (!(Math.abs(mx) < 1e8) || !(Math.abs(my) < 1e8)) {       // beyond the horizon of the mapping
                    mx = -1e6;
                    my = -1e6;
                }
                out[ry * w + cx] = cubic ? sampleCubic(src, mx, my, fill) : sampleLinear(src, mx, my, fill);
            }
        });
        return new Raster(w, h, out);
    }

    /** A quicker look at the result: the photo shrunk to fit maxPx, bilinear, then the same finish as a real save. */
    public static Raster preview(Raster src, Quad q, int rot, boolean mirror, int maxPx) {
        int f = Math.max(1, (int) Math.ceil(Math.max(src.w, src.h) / (double) maxPx));
        Raster small = src.shrink(f);
        return render(small, q.scaled(1.0 / f), Fill.REFLECT, false).finish(rot, mirror);
    }

    //  sampling, with the same border rules as OpenCV's remap

    private static int index(int i, int n, Fill fill) {
        if (i >= 0 && i < n) return i;
        switch (fill) {
            case REPLICATE:
                return i < 0 ? 0 : n - 1;
            case BLACK:
                return -1;
            default:
                if (n == 1) return 0;
                int p = 2 * (n - 1);
                i = ((i % p) + p) % p;
                return i < n ? i : p - i;
        }
    }

    private static int pixel(Raster s, int x, int y, Fill fill) {
        int ix = index(x, s.w, fill), iy = index(y, s.h, fill);
        return (ix < 0 || iy < 0) ? 0 : s.rgb[iy * s.w + ix];
    }

    private static int pack(double r, double g, double b) {
        return clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    private static int clamp(double v) {
        int i = (int) Math.floor(v + 0.5);
        return i < 0 ? 0 : Math.min(i, 255);
    }

    private static int sampleLinear(Raster s, double mx, double my, Fill fill) {
        int x0 = (int) Math.floor(mx), y0 = (int) Math.floor(my);
        double fx = mx - x0, fy = my - y0;
        int p00 = pixel(s, x0, y0, fill), p10 = pixel(s, x0 + 1, y0, fill);
        int p01 = pixel(s, x0, y0 + 1, fill), p11 = pixel(s, x0 + 1, y0 + 1, fill);
        double[] ch = new double[3];
        for (int k = 0; k < 3; k++) {
            int sh = 16 - 8 * k;
            double a = ((p00 >> sh) & 255) * (1 - fx) + ((p10 >> sh) & 255) * fx;
            double b = ((p01 >> sh) & 255) * (1 - fx) + ((p11 >> sh) & 255) * fx;
            ch[k] = a * (1 - fy) + b * fy;
        }
        return pack(ch[0], ch[1], ch[2]);
    }

    private static final double A = -0.75;                   // OpenCV's cubic

    private static void cubicWeights(double t, double[] w) {
        w[0] = ((A * (t + 1) - 5 * A) * (t + 1) + 8 * A) * (t + 1) - 4 * A;
        w[1] = ((A + 2) * t - (A + 3)) * t * t + 1;
        w[2] = ((A + 2) * (1 - t) - (A + 3)) * (1 - t) * (1 - t) + 1;
        w[3] = 1 - w[0] - w[1] - w[2];
    }

    private static int sampleCubic(Raster s, double mx, double my, Fill fill) {
        int x0 = (int) Math.floor(mx), y0 = (int) Math.floor(my);
        double[] wx = new double[4], wy = new double[4];
        cubicWeights(mx - x0, wx);
        cubicWeights(my - y0, wy);
        double r = 0, g = 0, b = 0;
        for (int j = 0; j < 4; j++) {
            double rr = 0, gg = 0, bb = 0;
            for (int i = 0; i < 4; i++) {
                int p = pixel(s, x0 - 1 + i, y0 - 1 + j, fill);
                rr += wx[i] * ((p >> 16) & 255);
                gg += wx[i] * ((p >> 8) & 255);
                bb += wx[i] * (p & 255);
            }
            r += wy[j] * rr;
            g += wy[j] * gg;
            b += wy[j] * bb;
        }
        return pack(r, g, b);
    }
}
