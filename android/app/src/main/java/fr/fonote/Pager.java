package fr.fonote;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/**
 * Full-width pages side by side, snapped like cards laid next to one another.
 *
 * <p>Written here rather than pulled in: the client carries no interface dependency, and what a
 * pager adds to a horizontal scroller is a snap and a notion of which page is showing.
 *
 * <p>A page changes on a fifth of a screen's drag, not a half: a card meant to be glanced at
 * should not need a gesture across the whole display. Anything that scrolls sideways inside a
 * page — the action palette, the row of players in a note — claims the gesture for itself by
 * asking its parents not to intercept, so the two never fight over the same finger.
 */
final class Pager extends HorizontalScrollView {
    /** How far a page must be dragged before releasing it means "turn". */
    private static final float TURN = .2f;
    private final LinearLayout track;
    private int page;
    private Runnable watcher;

    Pager(Context context) {
        super(context);
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
        if (turned && watcher != null) watcher.run();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width == oldWidth) return;
        for (int i = 0; i < pages(); i++) track.getChildAt(i).getLayoutParams().width = width;
        track.requestLayout();
        // The scroll position means nothing until the pages have their new width.
        post(() -> scrollTo(page * getWidth(), 0));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) snap();
        return handled;
    }

    /** Where the finger let go decides the page, measured from the one it started on. */
    private void snap() {
        int width = getWidth();
        if (width == 0) return;
        float moved = (getScrollX() - page * (float)width) / width;
        show(page + (moved > TURN ? 1 : moved < -TURN ? -1 : 0), true);
        // Released without turning, the page still has to come back to rest.
        if (getScrollX() != page * width) smoothScrollTo(page * width, 0);
    }
}
