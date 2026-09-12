package fr.fonote;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/** Scrolling with a refresh when the gesture starts at the top of the page. */
final class Pull extends ScrollView {
    /**
     * The room the finger opens is handed to whoever refreshes, not closed first: the refresh
     * row takes exactly the height let go, and the page does not move a pixel at the moment
     * of release. Answering false declines the hand-off — the page then closes by itself.
     */
    interface Handover { boolean take(float opened); }

    /** How far the finger must open to arm the refresh. */
    private final float reach, slop;
    /**
     * Past the stop, the page still follows but less and less, over half a stop more. A hard
     * stop leaves the finger running over a dead page, and it is that silence which reads as a
     * snag.
     */
    private static final float GIVE = .5f;
    private Handover action;
    private java.util.function.BooleanSupplier available = () -> true;
    private float start, offset;
    private final GestureAxis gesture;
    private boolean eligible, pulling, armed, canRefresh, settling;

    Pull(Context context) {
        super(context);
        gesture = new GestureAxis(context);
        reach = 64 * context.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    void onPull(Handover action, java.util.function.BooleanSupplier available) {
        this.action = action;
        this.available = available;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        gesture.observe(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            View page = getChildAt(0);
            if (page != null) page.animate().cancel();
            settling = false;
            offset = page == null ? 0 : page.getTranslationY();
            pulling = false; armed = false;
            start = event.getY();
            canRefresh = available.getAsBoolean();
            eligible = action != null && getScrollY() == 0;
        }
        if (event.getPointerCount() > 1) { eligible = false; release(true); }
        if (gesture.horizontal() || event.getY() - start < -slop) eligible = false;
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                || event.getActionMasked() == MotionEvent.ACTION_UP) {
            eligible = false;
            if (pulling || getChildAt(0) != null && getChildAt(0).getTranslationY() > 0) release(true);
        }
        return handled;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (!gesture.vertical()) return false;
            if (eligible && event.getY() - start > slop) {
                seize(event);
                onTouchEvent(event);
                return true;
            }
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (!gesture.vertical()) return true;
            if (eligible && event.getY() - start > slop) seize(event);
        }
        if (!pulling) return super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            View page = getChildAt(0);
            float drawn = drawn(offset + (event.getY() - start - slop) * .5f);
            if (page != null) { page.animate().cancel(); settling = false; page.setTranslationY(drawn); }
            boolean reached = canRefresh && available.getAsBoolean() && drawn >= reach;
            if (reached && !armed) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            armed = reached;
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            View page = getChildAt(0);
            float opened = page == null ? 0 : page.getTranslationY();
            boolean taken = armed && action != null && available.getAsBoolean() && action.take(opened);
            release(!taken);
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) release(true);
        return true;
    }

    /**
     * Takes the gesture from the scroll, which had started it. Without this dismissal, it keeps
     * its last point and its velocity tracker open: the next gesture then starts again from
     * that point, with a jump the size of the touch slop, and the page leaves before the finger.
     */
    private void seize(MotionEvent event) {
        if (pulling) return;
        pulling = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        MotionEvent farewell = MotionEvent.obtain(event);
        farewell.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(farewell);
        farewell.recycle();
    }

    /** What the page shows for a finger's travel: half of it up to the stop, then the resistance. */
    private float drawn(float travel) {
        float limit = canRefresh ? reach : reach * .5f, give = limit * GIVE;
        if (travel <= 0) return 0;
        if (travel <= limit) return travel;
        return limit + give * (1 - (float) Math.exp(-(travel - limit) / give));
    }

    private void release(boolean animate) {
        pulling = false; armed = false;
        View page = getChildAt(0);
        if (page == null) return;
        if (animate && page.getTranslationY() > 0) {
            // Restarting a return already under way would give it back its initial speed, mid-flight.
            if (settling) return;
            settling = true;
            page.animate().cancel();
            page.animate().translationY(0).setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> settling = false).start();
        } else {
            page.animate().cancel();
            settling = false;
            page.setTranslationY(0);
        }
    }

    @Override protected void onDetachedFromWindow() {
        eligible = false; release(false);
        super.onDetachedFromWindow();
    }
}
