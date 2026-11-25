// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import com.flyerizer.core.Quad;
import com.flyerizer.core.Raster;
import com.flyerizer.core.Undo;
import com.flyerizer.core.Warp;

import java.util.ArrayList;

/**
 * One photo at a time.  The photo arrives from the Share sheet, "Open with", or the Open button.  The screen is the photo with its
 * handles, a strip of numbers for the selected corner (always there when a corner is selected: phones often lack numbers, and
 * they are always needed), an optional pad of arrows, and a bar of the four buttons used constantly.  Everything else is under More.
 * The activity holds all the state, and the view just shows it and reports edits.
 */
public final class MainActivity extends Activity implements PhotoTouchView.Listener {
    private static final int REQ_OPEN = 1;
    private static final String[] NAMES = {"TL", "TR", "BR", "BL"};
    private static final int[] KEY_NUMBER = {1, 2, 4, 3};
    private static final String[] LONG = {"Top left", "Top right", "Bottom right", "Bottom left"};
    private static final int RED = 0xFFFF2020, BLUE = 0xFF1E6BFF;

    private PhotoTouchView view;
    private Button openBtn, saveBtn, sidesBtn, moreBtn, padBtn, stepBtn;
    private LinearLayout strip;
    private GridLayout pad;
    private TextView title, sizes, status;
    private EditText xEdit, yEdit;

    private Raster src;
    private String srcBase = "photo";
    private final Quad quad = new Quad();
    private final Undo undo = new Undo();
    private int rot, selected = -1, step = 1;
    private boolean mirror, sidesMove, padShown;
    private Uri lastSaved;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        sidesMove = Prefs.sidesAtStart(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFEEEEEE);
        root.setFitsSystemWindows(true);

        FrameLayout center = new FrameLayout(this);
        view = new PhotoTouchView(this);
        view.listener = this;
        center.addView(view, new FrameLayout.LayoutParams(-1, -1));
        openBtn = button("Open photo", v -> startOpen());
        center.addView(openBtn, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1f));

        strip = new LinearLayout(this);                                 // the numbers of the selected corner
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(8), dp(2), dp(8), dp(2));
        title = new TextView(this);
        title.setTextSize(16);
        title.setMinWidth(dp(56));
        strip.addView(title);
        xEdit = number("X");
        yEdit = number("Y");
        strip.addView(label("X"));
        strip.addView(xEdit);
        strip.addView(label("Y"));
        strip.addView(yEdit);
        padBtn = button("Arrows", v -> {
            padShown = !padShown;
            refreshAll();
        });
        strip.addView(padBtn);
        strip.setVisibility(View.GONE);
        root.addView(strip, new LinearLayout.LayoutParams(-1, -2));

        pad = new GridLayout(this);                                     // eight arrows around the step button
        pad.setColumnCount(3);
        String[] arrows = {"\u2196", "\u2191", "\u2197", "\u2190", null, "\u2192", "\u2199", "\u2193", "\u2198"};
        for (int n = 0; n < 9; n++) {
            final int dx = n % 3 - 1, dy = n / 3 - 1;
            Button b;
            if (n == 4) {
                stepBtn = button("\u00D71", v -> {
                    step = step == 1 ? 10 : step == 10 ? 100 : 1;
                    stepBtn.setText("\u00D7" + step);
                });
                b = stepBtn;
            } else {
                b = button(arrows[n], null);
                b.setOnTouchListener((v, e) -> hold(dx, dy, e));
            }
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(72);
            lp.height = dp(44);
            pad.addView(b, lp);
        }
        pad.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(pad, plp);

        LinearLayout bar = new LinearLayout(this);                      // what is used constantly
        bar.setOrientation(LinearLayout.HORIZONTAL);
        saveBtn = button("Save", v -> saveNew());
        sidesBtn = button("\u25CF", v -> toggleSides());
        sidesBtn.setTextSize(20);
        Button rotateBtn = button("Rotate", v -> {
            if (src == null) return;
            rot = (rot + 90) % 360;
            refreshAll();
        });
        moreBtn = button("More", v -> showMore());
        for (Button b : new Button[]{saveBtn, sidesBtn, rotateBtn, moreBtn}) bar.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        sizes = new TextView(this);
        sizes.setPadding(dp(10), 0, dp(10), 0);
        status = new TextView(this);
        status.setPadding(dp(10), 0, dp(10), dp(6));
        root.addView(sizes);
        root.addView(status);
        setContentView(root);
        refreshAll();
        handleIntent(getIntent());
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setPadding(dp(8), 0, dp(4), 0);
        return v;
    }

    private EditText number(String hint) {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setSingleLine(true);
        e.setMinEms(4);
        e.setOnEditorActionListener((v, action, ev) -> {
            applyNumbers();
            e.clearFocus();
            return false;
        });
        e.setOnFocusChangeListener((v, has) -> {
            if (!has) applyNumbers();
        });
        return e;
    }

    // ----- getting a photo -----

    @Override protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        handleIntent(in);
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent in) {
        if (in == null || in.getAction() == null) return;
        Uri uri = null;
        String a = in.getAction();
        if (Intent.ACTION_SEND.equals(a)) {
            uri = in.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null && !list.isEmpty()) {
                uri = list.get(0);                                      // one photo at a time
                if (list.size() > 1) say("Several photos were shared.  Opened the first.");
            }
        } else if (Intent.ACTION_VIEW.equals(a)) {
            uri = in.getData();
        }
        if (uri != null) openUri(uri);
    }

    private void startOpen() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_OPEN);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_OPEN && result == RESULT_OK && data != null && data.getData() != null) openUri(data.getData());
    }

    private void openUri(final Uri uri) {
        say("Opening...");
        new Thread(() -> {
            try {
                final BitmapIO.Loaded l = BitmapIO.load(this, uri);
                runOnUiThread(() -> {
                    src = l.raster;
                    srcBase = l.baseName;
                    quad.set(Quad.whole(src.w, src.h));                 // starts at the photo's own corners
                    rot = 0;
                    mirror = false;
                    selected = -1;
                    padShown = false;
                    undo.clear();
                    view.setPhoto(src, quad, undo);
                    openBtn.setVisibility(View.GONE);
                    say(l.sample > 1 ? "Opened at 1/" + l.sample + " size: the photo is very large." : "Opened " + srcBase);
                    refreshAll();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> say("Could not open: " + (t instanceof OutOfMemoryError ? "not enough memory" : t.getMessage())));
            }
        }).start();
    }

    // ----- editing -----

    @Override public void onEdited() {
        refreshAll();
    }

    @Override public void onSelected(int corner) {
        selected = corner;
        undo.endRun();
        refreshAll();
    }

    private boolean hold(final int dx, final int dy, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                nudge(dx, dy);
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        nudge(dx, dy);
                        handler.postDelayed(this, 70);
                    }
                }, 400);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacksAndMessages(null);
                return true;
            default:
                return true;
        }
    }

    private void nudge(int dx, int dy) {
        if (src == null || selected < 0) return;
        undo.begin(quad, "nudge" + selected + sidesMove, LONG[selected] + " corner" + (sidesMove ? " and sides" : ""));
        quad.move(selected, dx * step, dy * step, sidesMove);
        refreshAll();
    }

    private void applyNumbers() {
        if (src == null || selected < 0) return;
        try {
            int x = Integer.parseInt(xEdit.getText().toString().trim()), y = Integer.parseInt(yEdit.getText().toString().trim());
            if (Math.rint(quad.c[selected][0]) == x && Math.rint(quad.c[selected][1]) == y) return;
            undo.begin(quad, "nudge" + selected + sidesMove, LONG[selected] + " corner" + (sidesMove ? " and sides" : ""));
            quad.place(selected, x, y, sidesMove);
            refreshAll();
        } catch (NumberFormatException ignored) {
            refreshAll();                                               // put the real numbers back
        }
    }

    private void toggleSides() {
        sidesMove = !sidesMove;
        say(sidesMove ? "Sides move: a corner takes the two beside it along." : "Corner moves: only the corner you move.");
        refreshAll();
    }

    private void zero() {
        if (src == null) return;
        undo.begin(quad, null, "Zero");
        quad.set(Quad.whole(src.w, src.h));
        refreshAll();
    }

    /** For now the same as Zero, plus rotation and mirror cleared.  The corner finder will come later. */
    private void reset() {
        if (src == null) return;
        undo.beginFull(quad, rot, mirror, "Reset");
        quad.set(Quad.whole(src.w, src.h));
        rot = 0;
        mirror = false;
        selected = -1;
        refreshAll();
    }

    private void undoLast() {
        Undo.Result r = undo.undoInto(quad, rot, mirror);
        if (r == null) {
            say("Nothing to undo.");
            return;
        }
        if (r.rot != null) {
            rot = r.rot;
            mirror = r.mirror;
        }
        say("Undid: " + r.label + ".");
        refreshAll();
    }

    private void showMore() {
        PopupMenu pm = new PopupMenu(this, moreBtn);
        pm.getMenu().add(0, 1, 0, "Zero");
        pm.getMenu().add(0, 2, 1, "Reset");
        pm.getMenu().add(0, 3, 2, "Mirror").setCheckable(true).setChecked(mirror);
        pm.getMenu().add(0, 4, 3, "Share last result");
        pm.getMenu().add(0, 5, 4, "Open another photo");
        pm.getMenu().add(0, 6, 5, "Undo");
        pm.getMenu().add(0, 7, 6, "Settings");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: zero(); break;
                case 2: reset(); break;
                case 3:
                    if (src != null) {
                        mirror = !mirror;
                        refreshAll();
                    }
                    break;
                case 4: shareLast(); break;
                case 5: startOpen(); break;
                case 6: undoLast(); break;
                case 7: startActivity(new Intent(this, SettingsActivity.class)); break;
                default: return false;
            }
            return true;
        });
        pm.show();
    }

    // ----- saving -----

    private void saveNew() {
        if (src == null) {
            say("Open a photo first.");
            return;
        }
        final Quad q = new Quad(quad);
        final int r = rot;
        final boolean m = mirror;
        final Raster s = src;
        final String base = srcBase;
        final int quality = Prefs.jpegQuality(this);
        final Warp.Fill fill = Prefs.fill(this);
        saveBtn.setEnabled(false);
        say("Saving...");
        new Thread(() -> {
            try {
                final Raster out = Warp.render(s, q, fill, true).finish(r, m);
                final BitmapIO.Saved sv = BitmapIO.saveJpeg(this, out, base, quality);
                runOnUiThread(() -> {
                    saveBtn.setEnabled(true);
                    lastSaved = sv.uri;
                    say("Saved " + sv.name + "  (" + out.w + "x" + out.h + ")");
                    CoffeeNag.afterSave(this);
                });
            } catch (final Throwable t) {
                runOnUiThread(() -> {
                    saveBtn.setEnabled(true);
                    say("Could not save: " + (t instanceof OutOfMemoryError ? "not enough memory"
                            : t instanceof ArithmeticException ? "those four corners do not make a shape" : t.getMessage()));
                });
            }
        }).start();
    }

    private void shareLast() {
        if (lastSaved == null) {
            say("Nothing saved yet.");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/jpeg");
        i.putExtra(Intent.EXTRA_STREAM, lastSaved);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share result"));
    }

    // ----- showing -----

    private void say(String s) {
        status.setText(s);
    }

    private void refreshAll() {
        view.setState(selected, sidesMove);
        strip.setVisibility(selected >= 0 ? View.VISIBLE : View.GONE);
        pad.setVisibility(selected >= 0 && padShown ? View.VISIBLE : View.GONE);
        padBtn.setText(padShown ? "Arrows \u25B4" : "Arrows \u25BE");
        sidesBtn.setText(sidesMove ? "\u25A0" : "\u25CF");
        sidesBtn.setTextColor(sidesMove ? BLUE : RED);
        if (selected >= 0) {
            title.setText(NAMES[selected] + " " + KEY_NUMBER[selected]);
            if (!xEdit.hasFocus()) xEdit.setText(String.valueOf((int) Math.rint(quad.c[selected][0])));
            if (!yEdit.hasFocus()) yEdit.setText(String.valueOf((int) Math.rint(quad.c[selected][1])));
        }
        if (src != null) {
            String saving;
            try {
                int[] sz = Warp.outputSize(quad.c);
                int w = sz[0], h = sz[1];
                if (rot == 90 || rot == 270) {
                    int t = w;
                    w = h;
                    h = t;
                }
                saving = Raster.even(w) + "x" + Raster.even(h);
            } catch (RuntimeException ex) {
                saving = "?";
            }
            sizes.setText("Started: " + src.w + "x" + src.h + "   Saving: " + saving);
        }
        view.invalidate();
    }

    @SuppressWarnings("deprecation")                                   // the system Back button; fine through Android 14
    @Override public void onBackPressed() {
        if (selected >= 0) {                                            // Back lets go of a corner before it leaves the app
            selected = -1;
            refreshAll();
        } else {
            super.onBackPressed();
        }
    }
}
