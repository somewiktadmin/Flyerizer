// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.flyerizer.core.Warp;

/**
 * The settings, kept in SharedPreferences.
 *
 * <p>Where results go is "Pictures/Flyerizer" or a folder the user picked once.  There is deliberately no "beside the original"
 * choice and no request for All files access (MANAGE_EXTERNAL_STORAGE): the author ruled that out on 2026-11-11, because
 * Flyerizer is meant to reach the Play Store.  Do not add it.
 */
final class Prefs {
    static final String SAVE_PICTURES = "pictures", SAVE_FOLDER = "folder";
    static final String FILL_REFLECT = "reflect", FILL_REPLICATE = "replicate", FILL_BLACK = "black";

    private Prefs() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("flyerizer", Context.MODE_PRIVATE);
    }

    static String fillName(Context c) { return p(c).getString("fill", FILL_REFLECT); }
    static void setFillName(Context c, String v) { p(c).edit().putString("fill", v).apply(); }

    static Warp.Fill fill(Context c) {
        switch (fillName(c)) {
            case FILL_REPLICATE: return Warp.Fill.REPLICATE;
            case FILL_BLACK: return Warp.Fill.BLACK;
            default: return Warp.Fill.REFLECT;
        }
    }

    static int jpegQuality(Context c) { return p(c).getInt("quality", 95); }
    static void setJpegQuality(Context c, int v) { p(c).edit().putInt("quality", Math.max(50, Math.min(100, v))).apply(); }

    /** Whether an orange dot slides its side along the lines of the sides next to it, so they keep their angles.  On unless turned off. */
    static boolean slideAlongSlope(Context c) { return p(c).getBoolean("slideSlope", true); }
    static void setSlideAlongSlope(Context c, boolean v) { p(c).edit().putBoolean("slideSlope", v).apply(); }

    /** The size of the loupe that follows a dragged corner, in dp. */
    static int loupeDp(Context c) { return p(c).getInt("loupeDp", 120); }

    static String saveMode(Context c) { return p(c).getString("saveMode", SAVE_PICTURES); }

    /** The folder the user picked, or null.  Falls back to Pictures/Flyerizer when none has been picked. */
    static Uri saveFolder(Context c) {
        String s = p(c).getString("saveFolder", null);
        return s == null ? null : Uri.parse(s);
    }

    static void setSaveFolder(Context c, Uri uri) {
        p(c).edit().putString("saveMode", SAVE_FOLDER).putString("saveFolder", uri.toString()).apply();
    }

    static void setSavePictures(Context c) { p(c).edit().putString("saveMode", SAVE_PICTURES).apply(); }

    //  the coffee reminder: 50, 500, 1000, then every 1000 saves, switched off by an honor-system checkbox
    static boolean coffeePaid(Context c) { return p(c).getBoolean("coffeePaid", false); }
    static void setCoffeePaid(Context c, boolean v) { p(c).edit().putBoolean("coffeePaid", v).apply(); }

    /** Counts one more successful save and returns the new count. */
    static int countSave(Context c) {
        int n = p(c).getInt("saves", 0) + 1;
        p(c).edit().putInt("saves", n).apply();
        return n;
    }
}
