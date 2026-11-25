// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.core;

/**
 * The four corners (top left, top right, bottom right, bottom left) and one bend per side, in photo pixel positions.
 * A bend is {t, d}: how far along the side its dot sits (0.5 is the middle) and how far across it, in pixels.
 */
public final class Quad {
    public static final int TOP = 0, RIGHT = 1, BOTTOM = 2, LEFT = 3;
    /** The corner indices at the start and the end of each side. */
    public static final int[][] SIDE_ENDS = {{0, 1}, {1, 2}, {3, 2}, {0, 3}};
    /** The corner that shares a left or right side with corner i, and so follows a sideways move. */
    public static final int[] DX_PARTNER = {3, 2, 1, 0};
    /** The corner that shares a top or bottom side with corner i, and so follows an up or down move. */
    public static final int[] DY_PARTNER = {1, 0, 3, 2};
    public static final double BEND_T_MIN = 0.25, BEND_T_MAX = 0.75;

    public final double[][] c = new double[4][2];
    public final double[][] bend = new double[4][2];

    public Quad() {
        straighten();
    }

    public Quad(Quad o) {
        set(o);
    }

    public void set(Quad o) {
        for (int i = 0; i < 4; i++) {
            c[i][0] = o.c[i][0];
            c[i][1] = o.c[i][1];
            bend[i][0] = o.bend[i][0];
            bend[i][1] = o.bend[i][1];
        }
    }

    public void straighten() {
        for (int s = 0; s < 4; s++) {
            bend[s][0] = 0.5;
            bend[s][1] = 0;
        }
    }

    /** The photo's own corners, sides straight. */
    public static Quad whole(int w, int h) {
        Quad q = new Quad();
        double[][] p = {{0, 0}, {w - 1, 0}, {w - 1, h - 1}, {0, h - 1}};
        for (int i = 0; i < 4; i++) q.c[i] = p[i];
        return q;
    }

    /** A rectangle a little in from the photo's edges (a fraction of each side), sides straight. */
    public static Quad inset(int w, int h, double fraction) {
        double ix = Math.rint(w * fraction), iy = Math.rint(h * fraction);
        Quad q = new Quad();
        double[][] p = {{ix, iy}, {w - 1 - ix, iy}, {w - 1 - ix, h - 1 - iy}, {ix, h - 1 - iy}};
        for (int i = 0; i < 4; i++) q.c[i] = p[i];
        return q;
    }

    public Quad scaled(double k) {
        Quad q = new Quad(this);
        for (int i = 0; i < 4; i++) {
            q.c[i][0] *= k;
            q.c[i][1] *= k;
            q.bend[i][1] *= k;
        }
        return q;
    }

    public boolean sameAs(Quad o) {
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 2; j++)
                if (c[i][j] != o.c[i][j] || bend[i][j] != o.bend[i][j]) return false;
        return true;
    }

    /** {Ax, Ay, ex, ey, nx, ny}: the side's first end, its direction and length, and the unit vector across it. */
    public double[] sideFrame(int side) {
        double[] a = c[SIDE_ENDS[side][0]], b = c[SIDE_ENDS[side][1]];
        double ex = b[0] - a[0], ey = b[1] - a[1];
        double len = Math.hypot(ex, ey);
        double nx = len > 1e-9 ? -ey / len : 0, ny = len > 1e-9 ? ex / len : 1;
        return new double[]{a[0], a[1], ex, ey, nx, ny};
    }

    /** n points along a side: a quadratic Bezier curve from one corner to the other that passes through the bend dot. */
    public double[][] sidePoints(int side, int n) {
        double[] f = sideFrame(side);
        double t = bend[side][0], d = bend[side][1];
        double[][] p = new double[n][2];
        for (int i = 0; i < n; i++) {
            double s = n > 1 ? i / (double) (n - 1) : 0;
            double along = 2 * s * (1 - s) * (2 * t - 0.5) + s * s;      // equals s when the dot is centered
            double across = 4 * d * s * (1 - s);
            p[i][0] = f[0] + along * f[2] + across * f[4];
            p[i][1] = f[1] + along * f[3] + across * f[5];
        }
        return p;
    }

    public double[] bendDot(int side) {
        double[] f = sideFrame(side);
        double t = bend[side][0], d = bend[side][1];
        return new double[]{f[0] + t * f[2] + d * f[4], f[1] + t * f[3] + d * f[5]};
    }

    /** Bend the side so its dot sits at the photo position (px, py). */
    public void bendFromPoint(int side, double px, double py) {
        double[] f = sideFrame(side);
        double ee = f[2] * f[2] + f[3] * f[3];
        double dx = px - f[0], dy = py - f[1];
        double t = ee > 1e-9 ? (dx * f[2] + dy * f[3]) / ee : 0.5;
        bend[side][0] = Math.min(BEND_T_MAX, Math.max(BEND_T_MIN, t));
        bend[side][1] = dx * f[4] + dy * f[5];
    }

    /**
     * Move corner i by (dx, dy).  With sidesMove, the corner that shares its left or right side follows the sideways part
     * and the corner that shares its top or bottom side follows the up or down part, so the opposite corner stays put.
     */
    public void move(int i, double dx, double dy, boolean sidesMove) {
        c[i][0] += dx;
        c[i][1] += dy;
        if (sidesMove) {
            if (dx != 0) c[DX_PARTNER[i]][0] += dx;
            if (dy != 0) c[DY_PARTNER[i]][1] += dy;
        }
    }

    public void place(int i, double x, double y, boolean sidesMove) {
        move(i, x - c[i][0], y - c[i][1], sidesMove);
    }
}
