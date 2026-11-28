// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
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
 * starts on empty space.  A purple dot bends its side.  An orange dot slides its side across (the top and bottom sides up and
 * down, the left and right sides sideways), and the two corners at its ends go with it, along the lines of the sides next to
 * them when that Setting is on.  Two fingers pinch and pan.  One
 * corner is always selected, and a tap on another corner selects it.  A double tap on empty space fits the photo.  A square
 * window of the real pixels around the selected corner is always on screen in the opposite corner of the photo area, because the
 * finger covers exactly what you are trying to see.
 */
final class PhotoTouchView extends View {
    interface Listener {
        void onEdited();

        void onSelected(int corner);
    }

    static final int PURPLE = 0xFFA020F0, ORANGE = 0xFFFF8C00;          // dots that bend a side, and dots that slide a side
    private static final int RED = 0xFFFF2020, BLUE = 0xFF1E6BFF, GOLD = 0xFFFFC800, CYAN = 0xFF00E5FF;
    private static final float PREVIEW_INSET_DP = 50;                  // the corner preview's distance from the edges

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
            ring = new Paint(Paint.ANTI_ALIAS_FLAG), previewPaint = new Paint();
    private final Path path = new Path();
    private final ScaleGestureDetector scaler;

    private enum Kind { NONE, CORNER, BEND, BLANK }

    private Kind kind = Kind.NONE;
    private int index;
    private float downX, downY, lastX, lastY, grabDx, grabDy, lastCx, lastCy;
    private boolean moved, gesture, haveCentroid, slopeSlide;
    private long lastTapTime;
    private final double[][] sideStart = new double[2][2];           // the two corners of a side being slid, as they were at the touch
    private Bitmap previewBmp;
    private int[] previewPx;

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

    //  drawing

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
        drawCornerPreview(canvas);
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

    /**
     * A square window of the real photo pixels around the selected corner, with a crosshair on that corner.  It is always on
     * screen, in the photo area's opposite corner (TL shows it at the bottom right, TR at the bottom left, BL at the top right,
     * BR at the top left), because the finger covers exactly what you are trying to see.  Touches pass through it.  Its size (a
     * percent of the width) and its zoom are Settings.
     */
    private void drawCornerPreview(Canvas canvas) {
        int percent = Prefs.previewPercent(getContext());
        float inset = PREVIEW_INSET_DP * density;
        float side = Math.min(getWidth() * percent / 100f, Math.min(getWidth(), getHeight()) - 2 * inset);
        if (raster == null || selected < 0 || percent <= 0 || side < 24 * density) return;
        float zoom = Prefs.previewZoom(getContext()) / 100f;
        int n = (int) Math.ceil(side / zoom);                       // photo pixels across the window
        if (n % 2 == 0) n++;                                        // an odd number, so one pixel sits in the middle
        n = Math.max(3, n);
        if (previewBmp == null || previewBmp.getWidth() != n) {
            previewBmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            previewPx = new int[n * n];
        }
        int half = n / 2, cx = (int) Math.rint(quad.c[selected][0]), cy = (int) Math.rint(quad.c[selected][1]);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int px = cx - half + i, py = cy - half + j;
                boolean in = px >= 0 && py >= 0 && px < raster.w && py < raster.h;
                previewPx[j * n + i] = in ? 0xFF000000 | raster.rgb[py * raster.w + px] : 0xFF000000;
            }
        }
        previewBmp.setPixels(previewPx, 0, n, 0, 0, n, n);
        boolean right = selected == 0 || selected == 3, bottom = selected == 0 || selected == 1;   // TL and BL: right.  TL and TR: bottom.
        float left = right ? getWidth() - inset - side : inset, top = bottom ? getHeight() - inset - side : inset;
        float mx = left + side / 2, my = top + side / 2, d = n * zoom;
        canvas.save();
        canvas.clipRect(left, top, left + side, top + side);
        canvas.drawColor(0xFF000000);
        previewPaint.setFilterBitmap(false);
        canvas.drawBitmap(previewBmp, null, new RectF(mx - d / 2, my - d / 2, mx + d / 2, my + d / 2), previewPaint);
        canvas.restore();
        ring.setColor(0xFFFFFFFF);
        ring.setStrokeWidth(2 * density);
        canvas.drawRect(left, top, left + side, top + side, ring);
        ring.setStrokeWidth(3 * density);
        line.setColor(RED);
        line.setStrokeWidth(density);
        canvas.drawLine(left, my, left + side, my, line);
        canvas.drawLine(mx, top, mx, top + side, line);
        line.setColor(GOLD);
        line.setStrokeWidth(2 * density);
    }

    //  touch

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

    /**
     * Slides a side across by (dx, dy) photo pixels from where it was at the touch: only the up-down part for the top and bottom
     * sides, only the left-right part for the left and right sides.  With the slope Setting on, each of its two corners also
     * travels along the line of the side next to it, so those sides keep their angles.  A neighbor that runs nearly along the
     * slide (steeper than 4 to 1) cannot be followed, and that corner just goes straight across.
     */
    private void slideSide(int side, double dx, double dy) {
        boolean vertical = side == Quad.TOP || side == Quad.BOTTOM;     // true: the side moves up and down
        double move = Math.rint(vertical ? dy : dx);
        for (int k = 0; k < 2; k++) {
            int corner = Quad.SIDE_ENDS[side][k];
            double x0 = sideStart[k][0], y0 = sideStart[k][1], along = 0;   // along: how far the corner goes the other way
            if (slopeSlide) {
                double[] n = quad.c[vertical ? Quad.DX_PARTNER[corner] : Quad.DY_PARTNER[corner]];
                double mx = vertical ? n[1] - y0 : n[0] - x0;           // the neighbor's distance in the direction of the slide
                double ox = vertical ? n[0] - x0 : n[1] - y0;           // and across it
                if (Math.abs(mx) >= 1 && Math.abs(ox / mx) <= 4) along = Math.rint(move * ox / mx);
            }
            quad.c[corner][0] = vertical ? x0 + along : x0 + move;
            quad.c[corner][1] = vertical ? y0 + move : y0 + along;
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
                    slopeSlide = Prefs.slideAlongSlope(getContext());
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
