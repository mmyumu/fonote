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
            checkSearchStaysOnCard();
            for (String status : new String[]{"TIMED", "SCHEDULED", "IN_PLAY", "PAUSED"})
                require(FixtureSelection.unfinished(new JSONObject().put("status", status)), "Hidden active favorite");
            for (String status : new String[]{"FINISHED", "CANCELLED", "POSTPONED"})
                require(!FixtureSelection.unfinished(new JSONObject().put("status", status)), "Inactive favorite visible");
            JSONObject searchable = new JSONObject().put("homeTeam", new JSONObject().put("name", "Équipe de Lyon"))
                .put("competition", new JSONObject().put("name", "Ligue 1"));
            require(FixtureSelection.matches(searchable, " EQUIPE "), "Accent-insensitive search failed");
            require(FixtureSelection.matches(searchable, "ligue"), "Competition search failed");
            require(!FixtureSelection.matches(searchable, "Monaco"), "Unrelated search result");
            result.putString("stream", "Offline checks passed: migration, persistence, deduplication, notes, catalogue, rollback, next club fixtures.\n");
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
    private void checkSearchStaysOnCard() throws Exception {
        Activity activity = startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> {
            try {
                java.lang.reflect.Field pagerField = MainActivity.class.getDeclaredField("browsePager");
                pagerField.setAccessible(true);
                Pager pager = (Pager) pagerField.get(activity);
                for (int index : new int[]{0, 2}) {
                    pager.show(index, false);
                    java.lang.reflect.Field cardField = MainActivity.class.getDeclaredField(
                        index == 0 ? "annotatedCard" : "calendarCard");
                    cardField.setAccessible(true);
                    android.widget.EditText search = findSearch((android.view.View) cardField.get(activity));
                    search.requestFocusFromTouch(); search.setText("Monaco");
                    require(search.hasFocus(), "Could not focus search before submission on card " + index);
                    search.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
                    require(search.hasFocus(), "Search submitted focus to another card from " + index);
                    search.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,
                        android.view.KeyEvent.KEYCODE_ENTER));
                    search.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,
                        android.view.KeyEvent.KEYCODE_ENTER));
                    require(search.hasFocus(), "Physical Enter submitted focus to another card");
                    require(pager.page() == index, "Search changed the selected card");
                    require(pager.getScrollX() == index * pager.getWidth(), "Search scrolled away from its card");
                    require(search.getText().toString().equals("Monaco"), "Submission cleared the query");
                }
            } catch (Throwable error) { failure.set(error); }
        });
        waitForIdleSync();
        if (failure.get() != null) throw new AssertionError("Search navigation regression", failure.get());
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
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
