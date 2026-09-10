package fr.fonote;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/** Défilement avec actualisation lorsque le geste commence en haut de la page. */
final class Pull extends ScrollView {
    private final float reach, slop;
    private Runnable action;
    private java.util.function.BooleanSupplier available = () -> true;
    private float start, offset;
    private final GestureAxis gesture;
    private boolean eligible, pulling, armed, canRefresh;

    Pull(Context context) {
        super(context);
        gesture = new GestureAxis(context);
        reach = 64 * context.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    void onPull(Runnable action, java.util.function.BooleanSupplier available) {
        this.action = action;
        this.available = available;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        gesture.observe(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            View page = getChildAt(0);
            if (page != null) page.animate().cancel();
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
                pulling = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                onTouchEvent(event);
                return true;
            }
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (!gesture.vertical()) return true;
            if (eligible && event.getY() - start > slop) {
                pulling = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
        }
        if (!pulling) return super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            View page = getChildAt(0);
            float limit = canRefresh ? reach : reach * .5f;
            float drawn = Math.max(0, Math.min(offset + (event.getY() - start - slop) * .5f, limit));
            if (page != null) { page.animate().cancel(); page.setTranslationY(drawn); }
            boolean reached = canRefresh && available.getAsBoolean() && drawn >= reach;
            if (reached && !armed) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            armed = reached;
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            boolean asked = armed;
            release(true);
            if (asked && action != null && available.getAsBoolean()) action.run();
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) release(true);
        return true;
    }

    private void release(boolean animate) {
        pulling = false; armed = false;
        View page = getChildAt(0);
        if (page == null) return;
        page.animate().cancel();
        if (animate && page.getTranslationY() > 0)
            page.animate().translationY(0).setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        else page.setTranslationY(0);
    }

    @Override protected void onDetachedFromWindow() {
        eligible = false; release(false);
        super.onDetachedFromWindow();
    }
}
