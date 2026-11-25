// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

/** The coffee reminder: after the 50th, 500th and 1000th save, then every 1000, unless the honor-system box in Settings is ticked. */
final class CoffeeNag {
    static final String URL = "https://www.paypal.com/qrcodes/managed/33ecc1cb-c786-4fba-ad4a-17242f92e108?utm_source=consapp_onscreen";

    private CoffeeNag() {
    }

    static void open(Activity a) {
        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(URL)));
    }

    static void afterSave(final Activity a) {
        int n = Prefs.countSave(a);
        boolean due = n == 50 || n == 500 || n == 1000 || (n > 1000 && n % 1000 == 0);
        if (!due || Prefs.coffeePaid(a)) return;
        new AlertDialog.Builder(a)
                .setTitle("Flyerizer")
                .setMessage("If Flyerizer has saved you time, a $5 coffee is very welcome.  "
                        + "This reminder comes now and then, and the box in Settings turns it off for good.")
                .setPositiveButton("Buy a coffee", (d, w) -> open(a))
                .setNeutralButton("I already did", (d, w) -> Prefs.setCoffeePaid(a, true))
                .setNegativeButton("Maybe later", null)
                .show();
    }
}
