// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import com.flyerizer.core.Quad;
import com.flyerizer.core.Raster;
import com.flyerizer.core.Undo;

/**
 * The photo with its four corners, side curves and dots, under a finger.  One finger drags a corner or a dot, or pans when it
 * starts on empty space.  A purple dot bends its side.  An orange dot slides its side straight across (the top and bottom sides
 * up and down, the left and right sides sideways), and the two corners at its ends go with it.  Two fingers pinch and pan.  One
 * corner is always selected, and a tap on another corner selects it.  A double tap on empty space fits the photo.  While a corner
 * or dot is dragged a loupe floats above the finger, because the finger covers exactly what you are trying to see: it shows the
 * real pixels around the handle.
 */
final class PhotoTouchView extends View {
    interface Listener {
        void onEdited();

        void onSelected(int corner);
    }

    static final int PURPLE = 0xFFA020F0, ORANGE = 0xFFFF8C00;          // dots that bend a side, and dots that slide a side
    private static final int RED = 0xFFFF2020, BLUE = 0xFF1E6BFF, GOLD = 0xFFFFC800, CYAN = 0xFF00E5FF;
    private static final int LOUPE_HALF = 20;                          // the loupe shows 41 by 41 photo pixels

    Listener listener;
    private Quad quad = new Quad();
    private Undo undo = new Undo();
    private Raster raster;
    private Bitmap display;
    private int shrink = 1;
    private boolean sidesMove, dotsMove = true;
    private int selected = 0;

    private float scale = 1, offX, offY;
    private boolean fitted = true;
    private final float density;
    private final int slop;
    private final Paint bmp = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), loupePaint = new Paint();
    private final Path path = new Path();
    private final ScaleGestureDetector scaler;

    private enum Kind { NONE, CORNER, BEND, BLANK }

    private Kind kind = Kind.NONE;
    private int index;
    private float downX, downY, lastX, lastY, grabDx, grabDy, lastCx, lastCy;
    private boolean moved, gesture, haveCentroid;
    private long lastTapTime;
    private final double[][] sideStart = new double[2][2];           // the two corners of a side being slid, as they were at the touch
    private Bitmap loupe;
    private final int[] loupePx = new int[(2 * LOUPE_HALF + 1) * (2 * LOUPE_HALF + 1)];

    PhotoTouchView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * density);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * density);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAbout(d.getFocusX(), d.getFocusY(), d.getScaleFactor());
                return true;
            }
        });
    }

    void setPhoto(Raster r, Quad q, Undo u) {
        raster = r;
        quad = q;
        undo = u;
        selected = 0;
        shrink = Math.max(1, (int) Math.ceil(Math.max(r.w, r.h) / 3000.0));
        if (display != null) display.recycle();
        display = BitmapIO.toBitmap(shrink > 1 ? r.shrink(shrink) : r);
        fit();
    }

    void setState(int selectedCorner, boolean sides, boolean dots) {
        selected = selectedCorner;
        sidesMove = sides;
        dotsMove = dots;
        invalidate();
    }

    void fit() {
        fitted = true;
        if (raster == null || getWidth() == 0) return;
        float m = 12 * density;
        scale = Math.min((getWidth() - 2 * m) / raster.w, (getHeight() - 2 * m) / raster.h);
        offX = (getWidth() - raster.w * scale) / 2;
        offY = (getHeight() - raster.h * scale) / 2;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (fitted) fit();
    }

    private float sx(double x) { return (float) ((x + 0.5) * scale + offX); }
    private float sy(double y) { return (float) ((y + 0.5) * scale + offY); }
    private double ix(float x) { return (x - offX) / scale - 0.5; }
    private double iy(float y) { return (y - offY) / scale - 0.5; }

    private void zoomAbout(float fx, float fy, float factor) {
        float ns = Math.max(0.02f, Math.min(64f, scale * factor));
        offX = fx - (fx - offX) * ns / scale;
        offY = fy - (fy - offY) * ns / scale;
        scale = ns;
        fitted = false;
        invalidate();
    }

    // ----- drawing -----

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF202020);
        if (display == null) return;
        Matrix m = new Matrix();
        m.postScale(scale * shrink, scale * shrink);
        m.postTranslate(offX, offY);
        bmp.setFilterBitmap(scale * shrink < 2);
        canvas.drawBitmap(display, m, bmp);
        line.setColor(GOLD);
        for (int s = 0; s < 4; s++) {
            double[][] p = quad.sidePoints(s, 48);
            path.reset();
            for (int i = 0; i < p.length; i++) {
                if (i == 0) path.moveTo(sx(p[i][0]), sy(p[i][1]));
                else path.lineTo(sx(p[i][0]), sy(p[i][1]));
            }
            canvas.drawPath(path, line);
        }
        for (int s = 0; s < 4; s++) {
            double[] d = quad.bendDot(s);
            handle(canvas, sx(d[0]), sy(d[1]), 8 * density, dotsMove ? ORANGE : PURPLE, false);
        }
        int anchor = (sidesMove && selected >= 0) ? (selected + 2) % 4 : -1;
        for (int i = 0; i < 4; i++) {
            float x = sx(quad.c[i][0]), y = sy(quad.c[i][1]);
            if (i == selected) {
                ring.setColor(CYAN);
                canvas.drawCircle(x, y, 16 * density, ring);
            }
            boolean square = sidesMove && i != anchor;              // blue squares move together, the red dot stays put
            handle(canvas, x, y, 11 * density, square ? BLUE : RED, square);
        }
        if ((kind == Kind.CORNER || kind == Kind.BEND) && moved) drawLoupe(canvas);
    }

    private void handle(Canvas canvas, float x, float y, float r, int color, boolean square) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        ring.setColor(0xFFFFFFFF);
        ring.setStrokeWidth(2 * density);
        if (square) {
            canvas.drawRect(x - r, y - r, x + r, y + r, fill);
            canvas.drawRect(x - r, y - r, x + r, y + r, ring);
        } else {
            canvas.drawCircle(x, y, r, fill);
            canvas.drawCircle(x, y, r, ring);
        }
        ring.setStrokeWidth(3 * density);
    }

    private void drawLoupe(Canvas canvas) {
        double hx, hy;
        if (kind == Kind.CORNER) {
            hx = quad.c[index][0];
            hy = quad.c[index][1];
        } else {
            double[] d = quad.bendDot(index);
            hx = d[0];
            hy = d[1];
        }
        int n = 2 * LOUPE_HALF + 1, cx = (int) Math.rint(hx), cy = (int) Math.rint(hy);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int px = cx - LOUPE_HALF + i, py = cy - LOUPE_HALF + j;
                boolean in = px >= 0 && py >= 0 && px < raster.w && py < raster.h;
                loupePx[j * n + i] = in ? 0xFF000000 | raster.rgb[py * raster.w + px] : 0xFF000000;
            }
        }
        if (loupe == null) loupe = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        loupe.setPixels(loupePx, 0, n, 0, 0, n, n);
        float size = Prefs.loupeDp(getContext()) * density, r = size / 2;
        float lx = Math.max(r, Math.min(getWidth() - r, lastX));
        float ly = lastY - 110 * density;
        if (ly < r) ly = lastY + 110 * density;                     // near the top edge, below the finger instead
        canvas.save();
        path.reset();
        path.addCircle(lx, ly, r, Path.Direction.CW);
        canvas.clipPath(path);
        loupePaint.setFilterBitmap(false);
        canvas.drawBitmap(loupe, new Rect(0, 0, n, n), new RectF(lx - r, ly - r, lx + r, ly + r), loupePaint);
        canvas.restore();
        ring.setColor(0xFFFFFFFF);
        canvas.drawCircle(lx, ly, r, ring);
        line.setColor(RED);
        canvas.drawLine(lx - r, ly, lx + r, ly, line);
        canvas.drawLine(lx, ly - r, lx, ly + r, line);
        line.setColor(GOLD);
        ring.setStrokeWidth(3 * density);
    }

    // ----- touch -----

    private int hitCorner(float x, float y) {
        int best = -1;
        float bd = (30 * density) * (30 * density);
        for (int i = 0; i < 4; i++) {
            float dx = sx(quad.c[i][0]) - x, dy = sy(quad.c[i][1]) - y, d = dx * dx + dy * dy;
            if (d <= bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private int hitBend(float x, float y) {
        float lim = (24 * density) * (24 * density);
        for (int s = 0; s < 4; s++) {
            double[] d = quad.bendDot(s);
            float dx = sx(d[0]) - x, dy = sy(d[1]) - y;
            if (dx * dx + dy * dy <= lim) return s;
        }
        return -1;
    }

    /** Slides a side straight across by (dx, dy) photo pixels from where it was at the touch: only the up-down or left-right part counts. */
    private void slideSide(int side, double dx, double dy) {
        boolean vertical = side == Quad.TOP || side == Quad.BOTTOM;
        for (int k = 0; k < 2; k++) {
            int corner = Quad.SIDE_ENDS[side][k];
            quad.c[corner][0] = sideStart[k][0] + (vertical ? 0 : Math.rint(dx));
            quad.c[corner][1] = sideStart[k][1] + (vertical ? Math.rint(dy) : 0);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (raster == null) return true;
        scaler.onTouchEvent(e);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = lastX = x;
                downY = lastY = y;
                moved = false;
                gesture = false;
                haveCentroid = false;
                int c = hitCorner(x, y), b = hitBend(x, y);
                if (c >= 0) {
                    selected = c;
                    kind = Kind.CORNER;
                    index = c;
                    grabDx = sx(quad.c[c][0]) - x;
                    grabDy = sy(quad.c[c][1]) - y;
                    if (listener != null) listener.onSelected(c);
                } else if (b >= 0) {
                    kind = Kind.BEND;
                    index = b;
                    double[] d = quad.bendDot(b);
                    grabDx = sx(d[0]) - x;
                    grabDy = sy(d[1]) - y;
                    for (int k = 0; k < 2; k++) {
                        sideStart[k][0] = quad.c[Quad.SIDE_ENDS[b][k]][0];
                        sideStart[k][1] = quad.c[Quad.SIDE_ENDS[b][k]][1];
                    }
                } else {
                    kind = Kind.BLANK;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                gesture = true;                                     // a second finger: pinch and pan, and no edit
                kind = Kind.NONE;
                haveCentroid = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2) {
                    float cx = 0, cy = 0;
                    for (int i = 0; i < e.getPointerCount(); i++) {
                        cx += e.getX(i);
                        cy += e.getY(i);
                    }
                    cx /= e.getPointerCount();
                    cy /= e.getPointerCount();
                    if (haveCentroid) {
                        offX += cx - lastCx;
                        offY += cy - lastCy;
                        fitted = false;
                    }
                    lastCx = cx;
                    lastCy = cy;
                    haveCentroid = true;
                    invalidate();
                    return true;
                }
                if (gesture) return true;                           // one finger left after a pinch: wait for the lift
                lastX = x;
                lastY = y;
                if (!moved && Math.abs(x - downX) < slop && Math.abs(y - downY) < slop) return true;
                if (kind == Kind.BLANK) {
                    offX += x - downX;
                    offY += y - downY;
                    downX = x;
                    downY = y;
                    moved = true;
                    fitted = false;
                } else if (kind == Kind.CORNER || kind == Kind.BEND) {
                    if (!moved) {
                        moved = true;
                        undo.begin(quad, null, kind == Kind.CORNER ? "corner" + (sidesMove ? " and sides" : "") : dotsMove ? "side" : "bend");
                    }
                    if (kind == Kind.CORNER) quad.place(index, Math.rint(ix(x + grabDx)), Math.rint(iy(y + grabDy)), sidesMove);
                    else if (dotsMove) slideSide(index, (x - downX) / scale, (y - downY) / scale);
                    else quad.bendFromPoint(index, ix(x + grabDx), iy(y + grabDy));
                    if (listener != null) listener.onEdited();
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!gesture && !moved && kind == Kind.BLANK) {
                    long now = e.getEventTime();
                    if (now - lastTapTime < 300) fit();             // a double tap on empty space fits the photo
                    lastTapTime = now;
                }
                kind = Kind.NONE;
                moved = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                kind = Kind.NONE;
                moved = false;
                gesture = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
