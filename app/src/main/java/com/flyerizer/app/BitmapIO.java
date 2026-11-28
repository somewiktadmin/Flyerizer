// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import com.flyerizer.core.Raster;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Pictures in and out, the part that is different on a phone.  A photo comes in upright (its EXIF rotation is applied, and the
 * result carries no orientation tag, only the right pixels).  A result goes out through the media store into Pictures/Flyerizer,
 * or into a folder the user picked once with the system folder picker.  Never beside the original, and never with All files
 * access: see {@link Prefs}.
 */
final class BitmapIO {
    /** Beyond this many pixels a photo is read at half size, and so on, so a 100 megapixel photo cannot exhaust memory. */
    static final long MAX_PIXELS = 48_000_000L;

    private BitmapIO() {
    }

    static final class Loaded {
        final Raster raster;
        final int sample;           // 1 for full size, 2 if it was halved, and so on
        final String baseName;

        Loaded(Raster raster, int sample, String baseName) {
            this.raster = raster;
            this.sample = sample;
            this.baseName = baseName;
        }
    }

    static final class Saved {
        final Uri uri;
        final String name;

        Saved(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    static Loaded load(Context c, Uri uri) throws IOException {
        ContentResolver cr = c.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open " + uri);
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("cannot identify image file");
        int sample = 1;
        while ((long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > MAX_PIXELS) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm;
        try (InputStream in = cr.openInputStream(uri)) {
            bm = BitmapFactory.decodeStream(in, null, o);
        }
        if (bm == null) throw new IOException("cannot identify image file");
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        bm.recycle();
        for (int i = 0; i < px.length; i++) {                           // transparency goes over white
            int p = px[i], a = p >>> 24;
            if (a < 255) {
                int r = (((p >> 16) & 255) * a + 255 * (255 - a)) / 255;
                int g = (((p >> 8) & 255) * a + 255 * (255 - a)) / 255;
                int b = ((p & 255) * a + 255 * (255 - a)) / 255;
                px[i] = r << 16 | g << 8 | b;
            } else {
                px[i] = p & 0xFFFFFF;
            }
        }
        Raster r = applyOrientation(new Raster(w, h, px), exifOrientation(cr, uri));
        return new Loaded(r, sample, baseName(c, uri));
    }

    private static int exifOrientation(ContentResolver cr, Uri uri) {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return ExifInterface.ORIENTATION_NORMAL;
            return new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** Makes the pixels upright.  Clockwise quarter turns are counter-clockwise ones in Raster, hence the 3 for 90 clockwise. */
    static Raster applyOrientation(Raster r, int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: return r.mirror();
            case ExifInterface.ORIENTATION_ROTATE_180: return r.rotateCcw(2);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: return r.rotateCcw(2).mirror();
            case ExifInterface.ORIENTATION_TRANSPOSE: return r.rotateCcw(3).mirror();
            case ExifInterface.ORIENTATION_ROTATE_90: return r.rotateCcw(3);
            case ExifInterface.ORIENTATION_TRANSVERSE: return r.rotateCcw(1).mirror();
            case ExifInterface.ORIENTATION_ROTATE_270: return r.rotateCcw(1);
            default: return r;
        }
    }

    /** The file name without its extension, for naming the result.  "photo" when the name is unknown. */
    static String baseName(Context c, Uri uri) {
        String name = null;
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (RuntimeException ignored) {
            // a provider that will not say: use the path below
        }
        if (name == null && uri.getLastPathSegment() != null) name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) return "photo";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** A bitmap of the pixels, filled a row at a time so a big picture is not copied whole. */
    static Bitmap toBitmap(Raster r) {
        Bitmap bm = Bitmap.createBitmap(r.w, r.h, Bitmap.Config.ARGB_8888);
        int[] row = new int[r.w];
        for (int y = 0; y < r.h; y++) {
            for (int x = 0; x < r.w; x++) row[x] = 0xFF000000 | r.rgb[y * r.w + x];
            bm.setPixels(row, 0, r.w, 0, y, r.w, 1);
        }
        return bm;
    }

    //  saving

    /** Writes the result as name~1.jpg, name~2.jpg and so on up to ~99, a hard limit, and returns where it went. */
    static Saved saveJpeg(Context c, Raster r, String base, int quality) throws IOException {
        ContentResolver cr = c.getContentResolver();
        Uri folder = Prefs.SAVE_FOLDER.equals(Prefs.saveMode(c)) ? Prefs.saveFolder(c) : null;
        Set<String> taken = folder != null ? namesInFolder(cr, folder) : namesInPictures(cr, base);
        String name = null;
        for (int n = 1; n <= 99; n++) {
            String cand = base + "~" + n + ".jpg";
            if (!taken.contains(cand)) {
                name = cand;
                break;
            }
        }
        if (name == null) throw new IOException("The names " + base + "~1 to ~99 are all taken.");
        Bitmap bm = toBitmap(r);
        try {
            if (folder != null) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder));
                Uri doc = DocumentsContract.createDocument(cr, parent, "image/jpeg", name);
                if (doc == null) throw new IOException("Could not create " + name + " in the chosen folder.");
                try (OutputStream os = cr.openOutputStream(doc)) {
                    if (os == null || !bm.compress(Bitmap.CompressFormat.JPEG, quality, os)) throw new IOException("Could not write " + name);
                }
                return new Saved(doc, name);
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Flyerizer");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Could not create " + name + " in Pictures.");
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null || !bm.compress(Bitmap.CompressFormat.JPEG, quality, os)) throw new IOException("Could not write " + name);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return new Saved(uri, name);
        } finally {
            bm.recycle();
        }
    }

    private static Set<String> namesInPictures(ContentResolver cr, String base) {
        Set<String> names = new HashSet<>();
        String[] proj = {MediaStore.Images.Media.DISPLAY_NAME};
        String sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
        try (Cursor cur = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel,
                new String[]{"Pictures/Flyerizer%", base + "~%"}, null)) {
            while (cur != null && cur.moveToNext()) names.add(cur.getString(0));
        } catch (RuntimeException ignored) {
            // an empty set means ~1 is tried, and the media store renames a clash by itself
        }
        return names;
    }

    private static Set<String> namesInFolder(ContentResolver cr, Uri tree) {
        Set<String> names = new HashSet<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cur = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (cur != null && cur.moveToNext()) names.add(cur.getString(0));
        } catch (RuntimeException ignored) {
            // see above
        }
        return names;
    }
}
