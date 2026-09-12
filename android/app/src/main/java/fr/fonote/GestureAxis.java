package fr.fonote;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/** Picks an axis once past the touch slop, then keeps it until the next touch. */
final class GestureAxis {
    private final float slop;
    private float x, y;
    private int axis;

    GestureAxis(Context context) { slop = ViewConfiguration.get(context).getScaledTouchSlop(); }

    void observe(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            x = event.getX(); y = event.getY(); axis = 0;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && axis == 0) {
            float dx = Math.abs(event.getX() - x), dy = Math.abs(event.getY() - y);
            // Waiting on an ambiguous diagonal avoids choosing on a tremor of the finger.
            if (dx > slop && dx > dy * 1.2f) axis = 1;
            else if (dy > slop && dy > dx * 1.2f) axis = 2;
        }
    }

    boolean horizontal() { return axis == 1; }
    boolean vertical() { return axis == 2; }
}
