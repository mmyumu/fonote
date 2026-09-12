package fr.fonote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.json.JSONObject;

/** Device checks against real SQLite, in a separate database so personal notes are untouched. */
public final class OfflineChecks extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Bundle progress = new Bundle();
        progress.putString("class", getClass().getName());
        progress.putString("test", "persistentOfflineStore");
        progress.putInt("numtests", 1); progress.putInt("current", 1);
        sendStatus(1, progress);
        Context context = getTargetContext();
        Store store = null;
        try {
            context.deleteDatabase("offline-checks.sqlite3");
            try (SQLiteDatabase old = context.openOrCreateDatabase("offline-checks.sqlite3", 0, null, null)) {
                old.execSQL("CREATE TABLE operations (id TEXT PRIMARY KEY, payload TEXT NOT NULL, seq INTEGER)");
                old.execSQL("INSERT INTO operations VALUES ('existing', '{\"id\":\"existing\"}', NULL)");
                old.setVersion(1);
            }
            store = new Store(context, "offline-checks.sqlite3");
            require(store.operations(true).length() == 1, "Migration lost a pending note");
            String path = "/v1/football/matches/42";
            String detail = "{\"id\":42,\"homeTeam\":{\"lineup\":[1,2]},\"utcDate\":\"2026-09-07T18:00:00Z\"}";
            store.download(path, detail);
            store.download("/v1/football/competitions", "{\"competitions\":[{\"id\":1}]}");
            store.download("/v1/football/matches?dateFrom=2026-09-07", "{\"matches\":[{\"id\":42},{\"id\":43}]}");
            store.download("/v1/football/matches?dateFrom=2026-09-06", "{\"matches\":[{\"id\":43}]}");
            require(store.fixtures().length() == 2, "Overlapping calendars duplicated or dropped matches");
            require(detail.equals(store.downloaded(path)), "Calendar overwrote the saved lineup");
            require(store.downloaded("/v1/football/matches?dateFrom=2026-09-07") == null, "Redundant calendar copy");
            store.close(); store = new Store(context, "offline-checks.sqlite3");
            require(detail.equals(store.downloaded(path)), "Detail did not survive reopening");
            String complete = new JSONObject(detail).put("lineup_status", "available").toString();
            store.download(path, complete);
            require(complete.equals(store.download(path, "{\"id\":42,\"lineup_status\":\"provider_error\"}")),
                "Provider outage erased a saved lineup");
            detail = complete;
            require(store.downloaded("/v1/football/competitions") != null, "Catalogue did not survive reopening");
            store.add(new JSONObject().put("id", "offline-note").put("match_id", "fd-42"));
            store.close(); store = new Store(context, "offline-checks.sqlite3");
            require(store.operations(true).length() == 2, "Offline note did not survive reopening");
            try { store.download(path, "broken JSON"); throw new AssertionError("Accepted corrupt JSON"); }
            catch (org.json.JSONException expected) { }
            require(detail.equals(store.downloaded(path)), "Corrupt response replaced saved detail");
            try { store.download("/v1/football/matches?dateFrom=x", "{\"matches\":[{\"id\":99},{}]}");
                throw new AssertionError("Accepted malformed fixture"); }
            catch (org.json.JSONException expected) { }
            require(store.fixtures().length() == 2, "Failed write was not rolled back");
            checkNextFixtures();
            checkSearchStaysPut();
            for (String status : new String[]{"TIMED", "SCHEDULED", "IN_PLAY", "PAUSED"})
                require(FixtureSelection.unfinished(new JSONObject().put("status", status)), "Hidden active favorite");
            for (String status : new String[]{"FINISHED", "CANCELLED", "POSTPONED"})
                require(!FixtureSelection.unfinished(new JSONObject().put("status", status)), "Inactive favorite visible");
            JSONObject searchable = new JSONObject().put("homeTeam", new JSONObject().put("name", "Équipe de Lyon"))
                .put("competition", new JSONObject().put("name", "Ligue 1"));
            require(FixtureSelection.matches(searchable, " EQUIPE "), "Accent-insensitive search failed");
            require(FixtureSelection.matches(searchable, "ligue"), "Competition search failed");
            require(!FixtureSelection.matches(searchable, "Monaco"), "Unrelated search result");
            checkPull();
            checkNestedGestures();
            checkRefreshAnimations();
            result.putString("stream", "Offline checks passed: migration, persistence, deduplication, notes, catalogue, rollback, next club fixtures, pull gestures.\n");
            sendStatus(0, progress);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            progress.putString("stack", android.util.Log.getStackTraceString(failure));
            sendStatus(-2, progress);
            result.putString("stream", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (store != null) store.close();
            context.deleteDatabase("offline-checks.sqlite3");
        }
    }
    /**
     * A query typed on one page stays there: submitting it must not hand the caret to a field on
     * the page next door, which a pager would follow. Checked on the annotated card, which is
     * swiped to, and on the calendar, which is a screen of its own whose weeks are swiped.
     */
    private void checkSearchStaysPut() throws Exception {
        Activity activity = startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> {
            try {
                Pager pager = (Pager) held(activity, "browsePager");
                require(pager.pages() == 3, "The home lost a card to swipe to");
                require(pager.page() == 1, "The home no longer opens between its two cards");
                pager.show(0, false);
                submitSearch(findSearch((android.view.View) held(activity, "annotatedCard")), "annotated card");
                require(pager.page() == 0, "Search changed the selected card");
                require(pager.getScrollX() == 0, "Search scrolled away from its card");
            } catch (Throwable error) { failure.set(error); }
        });
        waitForIdleSync();
        runOnMainSync(() -> {
            try {
                java.lang.reflect.Method calendar = MainActivity.class.getDeclaredMethod("calendar", java.time.LocalDate.class);
                calendar.setAccessible(true);
                calendar.invoke(activity, java.time.LocalDate.now());
            } catch (Throwable error) { failure.set(error); }
        });
        waitForIdleSync();
        runOnMainSync(() -> {
            try {
                Pager weeks = (Pager) held(activity, "weekPager");
                require(weeks.pages() == 3, "The calendar lost a week to swipe to");
                submitSearch(findSearch(activity.getWindow().getDecorView()), "calendar");
                require(weeks.page() == 1, "Search turned the week");
                require(weeks.getScrollX() == weeks.getWidth(), "Search scrolled away from the week shown");
            } catch (Throwable error) { failure.set(error); }
        });
        waitForIdleSync();
        if (failure.get() != null) throw new AssertionError("Search navigation regression", failure.get());
    }
    private static Object held(Activity activity, String name) throws Exception {
        java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }
    private static void submitSearch(android.widget.EditText search, String where) {
        search.requestFocusFromTouch(); search.setText("Monaco");
        require(search.hasFocus(), "Could not focus search before submission on the " + where);
        search.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        require(search.hasFocus(), "Search submitted focus away from the " + where);
        search.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
            android.view.KeyEvent.KEYCODE_ENTER));
        search.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
            android.view.KeyEvent.KEYCODE_ENTER));
        require(search.hasFocus(), "Physical Enter submitted focus away from the " + where);
        require(search.getText().toString().equals("Monaco"), "Submission cleared the query");
    }
    private static android.widget.EditText findSearch(android.view.View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText found = findSearch(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void checkNextFixtures() throws Exception {
        org.json.JSONArray fixtures = new org.json.JSONArray();
        fixtures.put(fixture(1, "TIMED", "2026-10-01T18:00:00Z", 10, 20));
        fixtures.put(fixture(2, "FINISHED", "2026-09-08T18:00:00Z", 10, 20));
        fixtures.put(fixture(3, "POSTPONED", "2026-09-09T18:00:00Z", 10, 20));
        fixtures.put(fixture(4, "CANCELLED", "2026-09-10T18:00:00Z", 10, 20));
        fixtures.put(fixture(5, "TIMED", "2026-09-01T18:00:00Z", 10, 20));
        fixtures.put(fixture(6, "SCHEDULED", "2026-09-12T18:00:00Z", 20, 10));
        fixtures.put(fixture(7, "SCHEDULED", "invalid", 10, 20));
        fixtures.put(fixture(8, "IN_PLAY", "2026-09-07T18:00:00Z", 10, 20));
        java.util.Set<String> clubs = new java.util.HashSet<>(java.util.Arrays.asList("10", "20", "30"));
        java.util.Map<String, JSONObject> next = FixtureSelection.next(fixtures, clubs,
            java.time.Instant.parse("2026-09-07T12:00:00Z"));
        require(next.size() == 2, "Invented a next match for an unknown club");
        require(next.get("10").getInt("id") == 6 && next.get("20").getInt("id") == 6,
            "Next match must use kickoff, both sides, and exclude past/cancelled/postponed/live matches");
    }
    private static JSONObject fixture(int id, String status, String date, int home, int away) throws Exception {
        return new JSONObject().put("id", id).put("status", status).put("utcDate", date)
            .put("homeTeam", new JSONObject().put("id", home))
            .put("awayTeam", new JSONObject().put("id", away));
    }
    private void checkPull() throws Exception {
        final Throwable[] failure = {null};
        runOnMainSync(() -> {
            try {
                Pull pull = new Pull(getTargetContext());
                android.widget.FrameLayout host = new android.widget.FrameLayout(getTargetContext());
                host.addView(pull, new android.widget.FrameLayout.LayoutParams(400, 400));
                android.widget.Button content = new android.widget.Button(getTargetContext());
                content.setMinimumHeight(2000);
                pull.addView(content, new android.widget.FrameLayout.LayoutParams(400, 2000));
                int size = android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY);
                host.measure(size, size); host.layout(0, 0, 400, 400);
                int[] calls = {0};
                boolean[] busy = {false};
                pull.onPull(opened -> { calls[0]++; return false; }, () -> !busy[0]);
                float distance = 160 * getTargetContext().getResources().getDisplayMetrics().density;
                gesture(pull, 0, distance, false);
                require(calls[0] == 1, "Pull on a clickable child did not refresh");
                busy[0] = true;
                for (int i = 0; i < 5; i++) gesture(pull, 0, distance, false);
                require(calls[0] == 1, "Pull during refresh queued another refresh");
                require(content.getTranslationY() > 0, "Busy pull has no elastic return");
                // Half the reach while busy, and past it the give Pull allows: half as much again.
                require(content.getTranslationY() <= 48 * contextDensity(), "Busy pull stretches too far");
                busy[0] = false;
                gesture(pull, 0, distance, false);
                require(calls[0] == 2, "Pull stayed disabled after refresh finished");
                gesture(pull, 0, 4, false);
                gesture(pull, 0, distance, true);
                require(calls[0] == 2, "Short or cancelled pull refreshed");
                content.animate().cancel(); content.setTranslationY(0);
                pull.scrollTo(0, 200);
                require(pull.getScrollY() == 200, "Test page cannot scroll");
                gesture(pull, 0, distance, false);
                require(calls[0] == 2, "Scrolling to the top refreshed");
                require(content.getTranslationY() == 0, "Pull left content displaced");
            } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError("Pull gesture", failure[0]);
    }

    /** Le toucher traverse le vrai pager parent avant d’atteindre la liste et son bouton. */
    private void checkNestedGestures() {
        final Throwable[] failure = {null};
        runOnMainSync(() -> {
            try {
                for (boolean clickable : new boolean[]{true, false}) {
                    nestedGesture(false, false, false, clickable);
                    nestedGesture(false, true, false, clickable);
                    nestedGesture(true, false, false, clickable);
                    nestedGesture(true, false, true, clickable);
                }
            } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError("Nested swipe gestures", failure[0]);
    }

    private void nestedGesture(boolean horizontal, boolean busy, boolean cancel, boolean clickable) {
        Context context = getTargetContext();
        Pager pager = new Pager(context);
        int[] refreshes = {0};
        for (int i = 0; i < 3; i++) {
            Pull pull = new Pull(context);
            android.widget.Button content = new android.widget.Button(context);
            content.setClickable(clickable);
            content.setMinimumHeight(2000);
            pull.addView(content, new android.widget.FrameLayout.LayoutParams(-1, 2000));
            pull.onPull(opened -> { refreshes[0]++; return false; }, () -> !busy);
            pager.addPage(pull);
        }
        int width = 1000, height = 1400;
        pager.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
        pager.layout(0, 0, width, height);
        pager.show(1, false);
        float slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        float reach = 170 * context.getResources().getDisplayMetrics().density;
        float[] xs = horizontal ? new float[]{900, 900 - slop * 3, 50, 50}
                                : new float[]{100, 100 + slop * 1.5f, 800, 800};
        float[] ys = horizontal ? new float[]{100, 100 + slop * 1.5f, 100 + reach, 100 + reach}
                                : new float[]{100, 100 + slop * 3, 100 + reach, 100 + reach};
        int[] actions = {0, 2, 2, cancel ? 3 : 1};
        long now = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i < actions.length; i++) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now + i * 80,
                actions[i], xs[i], ys[i], 0);
            pager.dispatchTouchEvent(event); event.recycle();
            if (!horizontal) require(pager.getScrollX() == width, "Vertical pull moved the pager");
        }
        require(pager.page() == (horizontal && !cancel ? 2 : 1), "Wrong page after nested gesture");
        require(refreshes[0] == (!horizontal && !busy && !cancel ? 1 : 0),
            "Nested gesture triggered the wrong refresh count");
    }

    private void checkRefreshAnimations() throws Exception {
        Activity activity = startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> {
            try {
                java.lang.reflect.Field screen = MainActivity.class.getDeclaredField("screen");
                screen.setAccessible(true); screen.set(activity, "animation-check");
                android.view.View homeContent = (android.view.View) held(activity, "homeRoot");
                android.view.View homeLoading = (android.view.View) held(activity, "homeRefresh");
                require(homeContent.getParent() instanceof Pull, "Home content lost its pull gesture");
                android.view.ViewGroup homeLayout = (android.view.ViewGroup) homeLoading.getParent();
                require(homeContent.getParent().getParent() == homeLayout,
                    "Home loading indicator moves with the feed");
                require(homeLayout.getChildAt(0) != homeContent.getParent()
                    && homeLayout.getChildAt(0) != homeLoading, "Home header moves with the feed");
                android.widget.LinearLayout feed = new android.widget.LinearLayout(activity);
                feed.setOrientation(android.widget.LinearLayout.VERTICAL);
                java.lang.reflect.Field refreshing = MainActivity.class.getDeclaredField("refreshing");
                refreshing.setAccessible(true); refreshing.set(activity, true);
                java.lang.reflect.Method create = MainActivity.class.getDeclaredMethod("refreshIndicator", android.widget.LinearLayout.class);
                create.setAccessible(true);
                android.view.View indicator = (android.view.View) create.invoke(activity, feed);
                java.lang.reflect.Field home = MainActivity.class.getDeclaredField("homeRefresh");
                home.setAccessible(true); home.set(activity, indicator);
                Pull pull = new Pull(activity);
                android.widget.Button content = new android.widget.Button(activity);
                content.setMinimumHeight(2000);
                pull.addView(content);
                feed.addView(pull, new android.widget.LinearLayout.LayoutParams(-1, 600));
                activity.setContentView(feed);
                boolean[] busy = {true};
                int[] requests = {0};
                pull.onPull(opened -> { requests[0]++; return false; }, () -> !busy[0]);
                feed.post(() -> {
                    try {
                        float distance = 170 * contextDensity();
                        gesture(pull, 0, distance, false, () -> busy[0] = false);
                        require(requests[0] == 0, "Refresh finishing mid-pull armed another request");
                        require(content.getTranslationY() > 0, "Pull return jumped immediately");
                        refreshing.set(activity, false);
                        // Zero: outside a gesture, no room opened by a finger to take over.
                        java.lang.reflect.Method update = MainActivity.class.getDeclaredMethod("refreshIndicators", int.class);
                        update.setAccessible(true); update.invoke(activity, 0);
                        require(indicator.getVisibility() == android.view.View.VISIBLE, "Indicator disappeared abruptly");
                        feed.postDelayed(() -> {
                            try {
                                require(Math.abs(content.getTranslationY()) < .5f, "Pull did not return to rest");
                                require(indicator.getVisibility() == android.view.View.GONE, "Indicator did not collapse");
                                require(indicator.getLayoutParams().height == 0, "Indicator left a gap");
                            } catch (Throwable error) { failure.set(error); }
                            finally { done.countDown(); }
                        }, 450);
                    } catch (Throwable error) { failure.set(error); done.countDown(); }
                });
            } catch (Throwable error) { failure.set(error); done.countDown(); }
        });
        try {
            require(done.await(3, java.util.concurrent.TimeUnit.SECONDS), "Animations timed out");
            if (failure.get() != null) throw new AssertionError("Refresh animations", failure.get());
        } finally { runOnMainSync(activity::finish); }
    }

    private float contextDensity() { return getTargetContext().getResources().getDisplayMetrics().density; }

    private void gesture(Pull pull, float x, float distance, boolean cancel) {
        gesture(pull, x, distance, cancel, () -> {});
    }

    private void gesture(Pull pull, float x, float distance, boolean cancel, Runnable beforeRelease) {
        long now = android.os.SystemClock.uptimeMillis();
        int[] actions = {0, 2, 2, cancel ? 3 : 1};
        float[] ys = {20, 40, 20 + distance, 20 + distance};
        for (int i = 0; i < actions.length; i++) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now + i * 30,
                actions[i], x + 100, ys[i], 0);
            if (i == actions.length - 1) beforeRelease.run();
            pull.dispatchTouchEvent(event); event.recycle();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
