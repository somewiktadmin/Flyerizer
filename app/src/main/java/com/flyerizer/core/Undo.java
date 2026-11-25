// SPDX-License-Identifier: GPL-3.0-or-later
// This program is free software, under the GNU General Public License, version 3 or any later version, and comes with no warranty.
package com.flyerizer.core;

import java.util.ArrayList;

/**
 * Undo for the corners and sides of the photo on screen: unlimited, one step per drag, per run of nudges on the same
 * corner (a pause of IDLE_MS, or any other corner, starts a new one), or per button.  Call begin() just before an edit.
 */
public final class Undo {
    public static final long IDLE_MS = 3000;

    /** What an undo gave back: the label of the step, and the rotation and mirror when the step was a Reset. */
    public static final class Result {
        public final String label;
        public final Integer rot;
        public final Boolean mirror;

        Result(String label, Integer rot, Boolean mirror) {
            this.label = label;
            this.rot = rot;
            this.mirror = mirror;
        }
    }

    private static final class Step {
        final Quad before;
        final String label;
        final Integer rot;
        final Boolean mirror;

        Step(Quad before, String label, Integer rot, Boolean mirror) {
            this.before = new Quad(before);
            this.label = label;
            this.rot = rot;
            this.mirror = mirror;
        }
    }

    private final ArrayList<Step> steps = new ArrayList<>();
    private Object runKey;
    private long runTime;

    /** A key of null always starts a new step. */
    public void begin(Quad now, Object key, String label) {
        long t = System.currentTimeMillis();
        if (key == null || !key.equals(runKey) || t - runTime > IDLE_MS) steps.add(new Step(now, label, null, null));
        runKey = key;
        runTime = t;
    }

    /** Like begin(), for a button that also clears the rotation and mirror. */
    public void beginFull(Quad now, int rot, boolean mirror, String label) {
        steps.add(new Step(now, label, rot, mirror));
        runKey = null;
        runTime = System.currentTimeMillis();
    }

    /** Ends the current run, so the next edit starts a new step. */
    public void endRun() {
        runKey = null;
    }

    public int size() {
        return steps.size();
    }

    public void clear() {
        steps.clear();
        runKey = null;
    }

    /** Puts the last step that changed something back into now.  Null when there is nothing to undo. */
    public Result undoInto(Quad now, int curRot, boolean curMirror) {
        runKey = null;
        while (!steps.isEmpty()) {
            Step s = steps.remove(steps.size() - 1);
            boolean extrasDiffer = s.rot != null && (s.rot != curRot || s.mirror != curMirror);
            if (!s.before.sameAs(now) || extrasDiffer) {      // a step that changed nothing is skipped
                now.set(s.before);
                return new Result(s.label, s.rot, s.mirror);
            }
        }
        return null;
    }
}
