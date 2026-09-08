package fr.fonote;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/**
 * Full-width pages side by side, snapped like cards laid next to one another.
 *
 * <p>Written here rather than pulled in: the client carries no interface dependency, and what a
 * pager adds to a horizontal scroller is a snap and a notion of which page is showing.
 *
 * <p>Two gestures turn a page, and they are asked in that order. A flick turns it whatever the
 * distance, because a card is thrown over as often as it is carried. Carried slowly, it turns at
 * half a screen and not before: a reader drawing a card across to look at it has not asked for
 * the next one, and a card that leaves at a fifth of the way is a card taken out of their hands.
 * Anything that scrolls sideways inside a
 * page — the action palette, the row of players in a note — claims the gesture for itself by
 * asking its parents not to intercept, so the two never fight over the same finger.
 *
 * <p>Which page shows is decided here and nowhere else: a scroller normally slides sideways to
 * reveal whatever descendant asks for it, which would carry the reader off to another card the
 * moment a field there took the caret back.
 */
final class Pager extends HorizontalScrollView {
    /**
     * How far a page must be carried before letting go means "turn": half a screen, the point
     * past which more of the next card shows than of this one. Only a gesture too slow to count
     * as a flick ever reaches this rule.
     */
    private static final float TURN = .5f;
    /** How far a flick has to carry, in dp, before its speed is allowed to speak for it. */
    private static final int NUDGE = 25;
    private final LinearLayout track;
    /** Above this, in pixels a second, a finger leaving the glass was still throwing the card. */
    private final int flick;
    private final float nudge;
    private VelocityTracker speed;
    private int page;
    private Runnable watcher;

    Pager(Context context) {
        super(context);
        flick = ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
        nudge = NUDGE * context.getResources().getDisplayMetrics().density;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        track = new LinearLayout(context);
        track.setOrientation(LinearLayout.HORIZONTAL);
        addView(track, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                  ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void addPage(View view) {
        track.addView(view, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT));
    }

    int pages() { return track.getChildCount(); }
    int page() { return page; }
    /** Told whenever the showing page changes, so the caller can dress its own chrome. */
    void onTurn(Runnable watcher) { this.watcher = watcher; }

    void show(int index, boolean smooth) {
        int target = Math.max(0, Math.min(pages() - 1, index));
        boolean turned = target != page;
        page = target;
        int x = target * getWidth();
        if (smooth) smoothScrollTo(x, 0); else scrollTo(x, 0);
        if (!turned) return;
        // The page left behind keeps the caret otherwise, and the keyboard goes on writing into
        // a search field nobody can see any more.
        clearFocus();
        InputMethodManager keyboard =
            (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        if (watcher != null) watcher.run();
    }

    /**
     * A page never drags the pager to itself. Focus, a moving caret or a keyboard opening all ask
     * the nearest scroller to reveal a rectangle; left alone, a field on the card next door would
     * slide that card into view as if the reader had asked for it.
     */
    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) { return 0; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // A page is exactly a screen wide, and it has to be that wide before the track is
        // measured: a track no wider than the window has nowhere to scroll, and every page but
        // the first is then unreachable.
        int width = MeasureSpec.getSize(widthSpec);
        for (int i = 0; i < pages(); i++) track.getChildAt(i).getLayoutParams().width = width;
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // Where a page rests is only knowable once it has its width, so the page asked for before
        // the first layout — the home card, on the way in — is placed here rather than there.
        if (changed) scrollTo(page * getWidth(), 0);
    }

    /**
     * A screen rebuilt under a moving finger — a match opened, the home card returned to — leaves
     * the gesture without its release, and with it a tracker borrowed from a shared pool and
     * never given back.
     */
    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (speed != null) { speed.recycle(); speed = null; }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        // The tracker is opened on whatever arrives first, not on the press: a button under the
        // finger keeps the press until the drag passes the slop, and the pager then hears the
        // gesture from its first move onwards, never from its start.
        if (speed == null) speed = VelocityTracker.obtain();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) speed.clear();
        speed.addMovement(event);
        boolean handled = super.onTouchEvent(event);
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return handled;
        speed.computeCurrentVelocity(1000);
        float velocity = speed.getXVelocity();
        speed.recycle(); speed = null;
        snap(velocity);
        return handled;
    }

    /**
     * Where the finger let go decides the page, measured from the one it started on — but how
     * fast it was still going is asked first. A card is thrown over as often as it is carried,
     * and a short quick flick that never reached a fifth of the screen used to read as a refusal:
     * the card slid back under a finger that plainly meant to turn it.
     */
    private void snap(float velocity) {
        int width = getWidth();
        if (width == 0) return;
        float moved = (getScrollX() - page * (float)width) / width;
        // A flick leftwards carries the reader forwards: the finger goes one way and the track
        // the other. Speed is only heard once the card has actually left its rest, so a press
        // that trembles stays a press.
        int turn = Math.abs(velocity) > flick && Math.abs(moved) * width > nudge
                 ? (velocity < 0 ? 1 : -1)
                 : (moved > TURN ? 1 : moved < -TURN ? -1 : 0);
        // Released without turning, the page still comes back to rest: show() scrolls to where
        // the page belongs whether or not it changed. Asking a second time here would cost the
        // animation rather than add one — a scroller told to slide twice inside a quarter of a
        // second drops the second slide flat, and the card would arrive without ever having been
        // seen moving.
        show(page + turn, true);
    }
}
