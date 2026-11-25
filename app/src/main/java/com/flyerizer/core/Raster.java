// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.core;

/**
 * A picture as plain pixels: 0xRRGGBB ints, row by row.  There is no AWT in here on purpose, so the whole core
 * (this class, Quad, Homography, Warp and Undo) can move to a phone unchanged.
 */
public final class Raster {
    public final int w, h;
    public final int[] rgb;

    public Raster(int w, int h) {
        this(w, h, new int[w * h]);
    }

    public Raster(int w, int h, int[] rgb) {
        if (rgb.length != w * h) throw new IllegalArgumentException("size does not match the pixels");
        this.w = w;
        this.h = h;
        this.rgb = rgb;
    }

    /** n, or n minus one if it is odd (a size of 1 stays 1). */
    public static int even(int n) {
        return n > 1 ? n - n % 2 : n;
    }

    /** Rotated counter-clockwise by 90 degrees, quarterTurns times. */
    public Raster rotateCcw(int quarterTurns) {
        int k = ((quarterTurns % 4) + 4) % 4;
        if (k == 0) return this;
        if (k == 2) {
            int[] o = new int[rgb.length];
            for (int i = 0; i < rgb.length; i++) o[rgb.length - 1 - i] = rgb[i];
            return new Raster(w, h, o);
        }
        int nw = h, nh = w;
        int[] o = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                // k == 1: new(x, y) = old(row x, column w-1-y).  k == 3: new(x, y) = old(row h-1-x, column y).
                o[y * nw + x] = (k == 1) ? rgb[x * w + (w - 1 - y)] : rgb[(h - 1 - x) * w + y];
            }
        }
        return new Raster(nw, nh, o);
    }

    /** Flipped left to right. */
    public Raster mirror() {
        int[] o = new int[rgb.length];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) o[y * w + x] = rgb[y * w + (w - 1 - x)];
        return new Raster(w, h, o);
    }

    /** An odd width or height loses its last column or row, on the right or at the bottom. */
    public Raster clipEven() {
        int nw = even(w), nh = even(h);
        if (nw == w && nh == h) return this;
        int[] o = new int[nw * nh];
        for (int y = 0; y < nh; y++) System.arraycopy(rgb, y * w, o, y * nw, nw);
        return new Raster(nw, nh, o);
    }

    /** What every result goes through last: rotate counter-clockwise by rot degrees, mirror, then make the sizes even. */
    public Raster finish(int rot, boolean mirror) {
        Raster r = rotateCcw(rot / 90);
        if (mirror) r = r.mirror();
        return r.clipEven();
    }

    /** A smaller copy: every f by f block of pixels averaged into one. */
    public Raster shrink(int f) {
        if (f <= 1) return this;
        int nw = (w + f - 1) / f, nh = (h + f - 1) / f;
        int[] o = new int[nw * nh];
        for (int by = 0; by < nh; by++) {
            for (int bx = 0; bx < nw; bx++) {
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = by * f; y < Math.min(h, by * f + f); y++) {
                    for (int x = bx * f; x < Math.min(w, bx * f + f); x++) {
                        int p = rgb[y * w + x];
                        r += (p >> 16) & 255;
                        g += (p >> 8) & 255;
                        b += p & 255;
                        n++;
                    }
                }
                o[by * nw + bx] = (int) ((r + n / 2) / n) << 16 | (int) ((g + n / 2) / n) << 8 | (int) ((b + n / 2) / n);
            }
        }
        return new Raster(nw, nh, o);
    }
}
