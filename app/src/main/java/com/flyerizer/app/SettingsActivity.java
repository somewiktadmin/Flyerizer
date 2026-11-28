// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * The settings, on one scrolling screen: the edge fill, JPEG quality, whether orange dots follow the sides next to them, where results are saved,
 * and the coffee reminder.  Changes are saved as they are made.  There is no "beside the original" and no All files access:
 * see {@link Prefs}.
 */
public final class SettingsActivity extends Activity {
    private static final int REQ_FOLDER = 2;
    private static final String[] FILLS = {Prefs.FILL_REFLECT, Prefs.FILL_REPLICATE, Prefs.FILL_BLACK};
    private static final String[] FILL_LABELS = {"Mirror the edge", "Repeat the edge", "Black"};

    private Spinner saveSpinner;
    private TextView folderText;
    private boolean ready;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, int topDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setPadding(0, dp(topDp), 0, dp(4));
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(24));
        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextSize(22);
        col.addView(title);

        col.addView(label("Where an empty corner gets its picture from", 16));
        Spinner fill = new Spinner(this);
        fill.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, FILL_LABELS));
        for (int i = 0; i < FILLS.length; i++) if (FILLS[i].equals(Prefs.fillName(this))) fill.setSelection(i);
        fill.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (ready) Prefs.setFillName(SettingsActivity.this, FILLS[pos]);
            }

            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        col.addView(fill);

        final TextView q = label("JPEG quality " + Prefs.jpegQuality(this), 16);
        col.addView(q);
        SeekBar seek = new SeekBar(this);
        seek.setMax(50);
        seek.setProgress(Prefs.jpegQuality(this) - 50);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                q.setText("JPEG quality " + (p + 50));
                if (user) Prefs.setJpegQuality(SettingsActivity.this, p + 50);
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }

            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(seek);

        CheckBox slope = new CheckBox(this);
        slope.setText("Orange dots slide a side along the sides next to it, keeping their angles");
        slope.setChecked(Prefs.slideAlongSlope(this));
        slope.setOnCheckedChangeListener((c, on) -> Prefs.setSlideAlongSlope(this, on));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(16);
        col.addView(slope, lp);

        col.addView(label("Save results to", 16));
        saveSpinner = new Spinner(this);
        saveSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Pictures/Flyerizer", "A folder I choose..."}));
        saveSpinner.setSelection(Prefs.SAVE_FOLDER.equals(Prefs.saveMode(this)) && Prefs.saveFolder(this) != null ? 1 : 0);
        saveSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!ready) return;
                if (pos == 0) {
                    Prefs.setSavePictures(SettingsActivity.this);
                    showFolder();
                } else {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER);
                }
            }

            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        col.addView(saveSpinner);
        folderText = new TextView(this);
        folderText.setTextSize(13);
        col.addView(folderText);
        showFolder();

        col.addView(label("Flyerizer never asks for access to all your files.  Results go to Pictures, or to the one folder you choose.", 12));

        CheckBox paid = new CheckBox(this);
        paid.setText("I have sent a coffee (turns off the reminders)");
        paid.setChecked(Prefs.coffeePaid(this));
        paid.setOnCheckedChangeListener((c, on) -> Prefs.setCoffeePaid(this, on));
        lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(24);
        col.addView(paid, lp);
        Button coffee = new Button(this);
        coffee.setText("Buy a coffee");
        coffee.setOnClickListener(v -> CoffeeNag.open(this));
        col.addView(coffee);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        sv.setFitsSystemWindows(true);
        setContentView(sv);
        sv.post(() -> ready = true); // the spinners call back once on their own when they are first shown
    }

    private void showFolder() {
        Uri u = Prefs.saveFolder(this);
        boolean on = Prefs.SAVE_FOLDER.equals(Prefs.saveMode(this)) && u != null;
        folderText.setText(on ? Uri.decode(u.getLastPathSegment() == null ? u.toString() : u.getLastPathSegment()) : "");
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_FOLDER) return;
        Uri u = result == RESULT_OK && data != null ? data.getData() : null;
        if (u != null) {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Prefs.setSaveFolder(this, u);
        } else {
            saveSpinner.setSelection(Prefs.SAVE_FOLDER.equals(Prefs.saveMode(this)) && Prefs.saveFolder(this) != null ? 1 : 0);
        }
        showFolder();
    }
}

