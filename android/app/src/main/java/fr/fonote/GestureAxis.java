package fr.fonote;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/** Choisit un axe après la tolérance tactile, puis le conserve jusqu’au prochain toucher. */
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
            // Attendre sur une diagonale ambiguë évite de choisir sur un tremblement du doigt.
            if (dx > slop && dx > dy * 1.2f) axis = 1;
            else if (dy > slop && dy > dx * 1.2f) axis = 2;
        }
    }

    boolean horizontal() { return axis == 1; }
    boolean vertical() { return axis == 2; }
}
