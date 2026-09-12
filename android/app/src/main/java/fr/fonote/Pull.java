package fr.fonote;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/** Défilement avec actualisation lorsque le geste commence en haut de la page. */
final class Pull extends ScrollView {
    /**
     * La place que le doigt ouvre est rendue à qui actualise, et non refermée d'abord : la ligne
     * d'actualisation prend exactement la hauteur lâchée, et la page ne bouge pas d'un pixel à
     * l'instant du relâchement. Répondre faux, c'est refuser la main — la page se referme alors
     * d'elle-même.
     */
    interface Handover { boolean take(float opened); }

    /** Ce que le doigt doit ouvrir pour armer l'actualisation. */
    private final float reach, slop;
    /**
     * Au-delà de la butée, la page suit encore mais de moins en moins, sur une demi-butée de
     * plus. Un arrêt net laisse le doigt courir sur une page morte, et c'est ce silence-là qu'on
     * lit comme un accroc.
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
     * Prendre le geste au défilement, qui l'avait commencé. Sans le congé que voici, il garde son
     * dernier point et son vélocimètre ouverts : le geste suivant repart alors de ce point-là,
     * d'un bond de la tolérance tactile, et la page part avant le doigt.
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

    /** Ce que la page montre pour une course de doigt : moitié jusqu'à la butée, puis la résistance. */
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
            // Relancer un retour déjà en route lui rendrait sa vitesse de départ, en plein vol.
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
