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
        android.content.SharedPreferences preferences = context.getSharedPreferences("fonote", Context.MODE_PRIVATE);
        boolean hadServer = preferences.contains("url"); String previousServer = preferences.getString("url", "");
        preferences.edit().putString("url", "").commit();
        int outcome = Activity.RESULT_OK;
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
            checkClubFixtures();
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
            checkSpotDrag();
            checkRefreshAnimations();
            checkTacticalEditor();
            result.putString("stream", "Offline checks passed: migration, persistence, deduplication, notes, catalogue, rollback, club fixtures, next and last, pull gestures, spot drag, tactical keyframes, timeline, undo/redo, atomic notes.\n");
            sendStatus(0, progress);
        } catch (Throwable failure) {
            progress.putString("stack", android.util.Log.getStackTraceString(failure));
            sendStatus(-2, progress);
            result.putString("stream", android.util.Log.getStackTraceString(failure));
            outcome = Activity.RESULT_CANCELED;
        } finally {
            if (hadServer) preferences.edit().putString("url", previousServer).commit(); else preferences.edit().remove("url").commit();
            if (store != null) store.close();
            context.deleteDatabase("offline-checks.sqlite3");
        }
        // Last, after the cleanup: finishing hands the process to the system, which kills it, and
        // a cleanup left for afterwards never runs — the app then stays in local mode for good.
        finish(outcome, result);
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
    /** Gestures go through the real pitch and the activity's commands, with an isolated log. */
    private void checkTacticalEditor() throws Exception {
        Activity activity = startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Store isolated = new Store(getTargetContext(), "tactical-checks.sqlite3");
        Store original = (Store)held(activity, "store");
        runOnMainSync(() -> {
            try {
                setHeld(activity, "store", isolated); setHeld(activity, "noteId", "");
                setHeld(activity, "diagram", new Diagram());
                invokeTactic(activity, "openTactic");
                Diagram diagram = (Diagram)held(activity, "diagram");
                diagram.tokens.add(new Diagram.Token("", "home", "A", .2,.3));
                invokeTactic(activity, "boardChanged");
            } catch (Throwable error) { failure.set(error); }
        });
        waitForIdleSync();
        runOnMainSync(() -> {
            try {
                if (failure.get() != null) return;
                BoardView board = (BoardView)held(activity, "board");
                require(board.getWidth()>0 && board.getHeight()>0, "Terrain sans place à l'écran");
                board.select(0); board.setTime(12); invokeTactic(activity, "renderTactic");
                dragBoard(board, .2f,.3f, .6f,.3f, false);
                Diagram diagram = (Diagram)held(activity, "diagram"); Diagram.Token token = diagram.tokens.get(0);
                require(token.track.at(0) != null && token.track.at(12) != null, "Glisser n'a pas créé les clés");
                require(Math.abs(token.track.at(0).x-.2)<.001, "Glisser a déplacé la clé précédente");
                require(token.track.at(12).x>.5, "Le joueur n'a pas bougé");
                board.setTime(6); invokeTactic(activity, "renderTactic");
                android.widget.Button diamond = (android.widget.Button)held(activity, "keyToggle");
                require(diamond.getText().toString().equals("◇"), "Losange actif hors clé");
                double before = diagram.position(token,6)[0]; diamond.performClick();
                require(token.track.at(6) != null && Math.abs(token.track.at(6).x-before)<.001, "Ajout neutre incorrect");
                require(((android.widget.Button)held(activity,"keyToggle")).getText().toString().equals("◆"), "Losange inactif sur clé");
                ((android.widget.Button)held(activity,"keyPrevious")).performClick(); require(board.time()==0, "Clé précédente incorrecte");
                ((android.widget.Button)held(activity,"keyNext")).performClick(); require(board.time()==6, "Clé suivante incorrecte");
                ((android.widget.Button)held(activity,"keyToggle")).performClick(); require(token.track.at(6)==null, "Suppression impossible");
                invokeTactic(activity,"undoTactic");
                diagram = (Diagram)held(activity,"diagram"); require(diagram.tokens.get(0).track.at(6)!=null,"Annulation incorrecte");
                invokeTactic(activity,"redoTactic");
                diagram = (Diagram)held(activity,"diagram"); token = diagram.tokens.get(0);
                require(token.track.at(6)==null,"Rétablissement incorrect");
                String saved = diagram.toJson().toString();
                double[] p = diagram.position(token,6);
                dragBoard(board,(float)p[0],(float)p[1],.8f,.5f,true);
                require(saved.equals(((Diagram)held(activity,"diagram")).toJson().toString()), "Geste annulé enregistré");
                setHeld(activity,"expandedTimeline",true); invokeTactic(activity,"renderTactic");
                require(isolated.operations(true).length()>0,"Séquence non sauvegardée hors connexion");
                // Annotations disappear with the player and come back on undo.
                diagram = (Diagram)held(activity,"diagram"); token = diagram.tokens.get(0); token.playerId = "test-player";
                @SuppressWarnings("unchecked") java.util.Map<String,String> entries = (java.util.Map<String,String>)held(activity,"entries");
                entries.put(token.playerId,"test-action"); invokeTactic(activity,"boardChanged");
                diagram.removeToken(0); invokeTactic(activity,"boardChanged"); require(entries.isEmpty(),"Annotation orpheline");
                invokeTactic(activity,"undoTactic"); require(entries.containsKey("test-player"),"Annotation perdue à l'annulation");
                org.json.JSONArray pending = isolated.operations(true);
                int size = pending.length();
                try {
                    isolated.addBatch(new org.json.JSONArray().put(new JSONObject().put("id","atomic-new"))
                        .put(pending.getJSONObject(0)));
                    throw new AssertionError("Lot en conflit accepté");
                } catch (android.database.sqlite.SQLiteConstraintException expected) { }
                require(isolated.operations(true).length()==size,"Lot partiellement sauvegardé");
            } catch (Throwable error) { failure.set(error); }
        });
        waitForIdleSync();
        runOnMainSync(() -> {
            try {
                if (failure.get() != null) return;
                Diagram diagram = (Diagram)held(activity,"diagram");
                Sequence.Motion motion = new Sequence(diagram).motions().get(0);
                String id = motion.id(); int start = motion.start.time, end = motion.end.time;
                SequenceTimeline timeline = findTimeline((android.view.View)held(activity,"tacticPanel"));
                require(timeline != null && timeline.getWidth()>0,"Chronologie invisible");
                float density = getTargetContext().getResources().getDisplayMetrics().density;
                int extent = Math.min(Track.END, Math.max(50,diagram.duration()+20));
                float width = timeline.getWidth()-95*density;
                float x = 85*density + width*start/extent + 6*density;
                float target = x+width*5/extent;
                gesture(timeline,x,48*density,target,48*density);
                diagram = (Diagram)held(activity,"diagram"); motion = new Sequence(diagram).motion(id);
                require(motion.start.time==start+5 && motion.end.time==end+5,"Le bloc n'a pas déplacé le mouvement entier");
                invokeTactic(activity,"undoTactic");
                BoardView board = (BoardView)held(activity,"board"); board.select(0); board.setTime(12);
                invokeTactic(activity,"renderTactic");
            } catch (Throwable error) { failure.set(error); }
        });
        waitForIdleSync();
        // A tackle drawn by finger onto the carrier: the tackler stops on contact and leaves with the ball.
        runOnMainSync(() -> {
            try {
                if (failure.get() != null) return;
                Diagram diagram = (Diagram)held(activity,"diagram");
                diagram.tokens.add(new Diagram.Token("", "home", "T", .2, .7));
                diagram.tokens.add(new Diagram.Token("", "away", "V", .7, .7));
                new Sequence(diagram).fixBall(0).owner = diagram.tokens.get(diagram.tokens.size()-1).id;
                invokeTactic(activity, "boardChanged");
                BoardView board = (BoardView)held(activity,"board");
                board.setTime(0); board.setStroke(Diagram.TACKLE);
                dragBoard(board, .2f,.7f, .7f,.7f, false);
                diagram = (Diagram)held(activity,"diagram");
                Diagram.Token tackler = diagram.tokens.get(diagram.tokens.size()-2);
                Track.Key hit = tackler.track.keys.get(tackler.track.keys.size()-1);
                require(Diagram.TACKLE.equals(hit.kind), "Tacle non enregistré");
                require(tackler.id.equals(diagram.holder(hit.time)), "Le tacleur n'a pas récupéré le ballon");
                require(hit.x < .69, "Le tacleur recouvre le joueur visé");
                require(hit.time >= 40, "Le tacle va à l'allure d'un ballon");
                require(board.time() == hit.time, "Le curseur n'est pas passé à l'arrivée du tracé");
                // A long stroke keeps its end: it used to be cut off after 32 samples.
                board.setStroke(Diagram.RUN);
                float sx = (float)hit.x;
                trace(board, new float[][]{{sx, .7f}, {sx, .9f}, {.1f, .9f}, {.1f, .5f}});
                diagram = (Diagram)held(activity,"diagram");
                tackler = diagram.tokens.get(diagram.tokens.size()-2);
                java.util.List<double[]> drawn = tackler.track.keys.get(tackler.track.keys.size()-1).path;
                require(drawn.size() <= Diagram.POINTS, "Tracé trop long pour le journal");
                boolean corner = false;
                for (double[] point : drawn) corner |= point[0] < .15 && point[1] > .85;
                require(corner, "Le long tracé a perdu son coin");
                require(drawn.get(drawn.size()-2)[1] < .6, "La fin du long tracé est tirée droite");
                board.setTool(BoardView.MOVE); invokeTactic(activity, "renderTactic");
                android.view.ViewGroup row = (android.view.ViewGroup)((android.view.ViewGroup)held(activity,"tacticPanel")).getChildAt(0);
                row.getChildAt(0).performClick();
            } catch (Throwable error) { failure.set(error); }
        });
        Thread.sleep(400);
        runOnMainSync(() -> {
            try {
                if (failure.get() != null) return;
                BoardView board = (BoardView)held(activity,"board");
                android.view.ViewGroup row = (android.view.ViewGroup)((android.view.ViewGroup)held(activity,"tacticPanel")).getChildAt(0);
                android.widget.SeekBar seek = (android.widget.SeekBar)row.getChildAt(2);
                require(board.moment() > 1, "La lecture n'avance pas");
                require(seek.getProgress() == board.time(), "Le curseur ne suit pas la lecture");
                row.getChildAt(0).performClick();
                require("▶".contentEquals(((android.widget.Button)row.getChildAt(0)).getText()), "La lecture ne s'est pas mise en pause");
                require(board.moment() == board.time(), "La pause reste entre deux dixièmes");
            } catch (Throwable error) { failure.set(error); }
        });
        waitForIdleSync();
        if (failure.get() == null) {
            android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
            if (screenshot != null) try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                    new java.io.File(getTargetContext().getCacheDir(),"tactical-check.png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output); screenshot.recycle();
            }
        }
        runOnMainSync(() -> {
            try { setHeld(activity,"store",original); activity.finish(); }
            catch (Exception error) { failure.set(error); }
        });
        isolated.close(); getTargetContext().deleteDatabase("tactical-checks.sqlite3");
        if (failure.get()!=null) throw new AssertionError("Éditeur tactique",failure.get());
    }
    private static SequenceTimeline findTimeline(android.view.View view) {
        if (view instanceof SequenceTimeline) return (SequenceTimeline)view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) { SequenceTimeline found = findTimeline(group.getChildAt(i)); if (found!=null) return found; }
        }
        return null;
    }
    private static void gesture(android.view.View view,float x,float y,float endX,float endY) {
        long now = android.os.SystemClock.uptimeMillis();
        int[] actions = {android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP};
        for (int i=0;i<actions.length;i++) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now,now+i*40,actions[i],i==0?x:endX,i==0?y:endY,0);
            view.dispatchTouchEvent(event); event.recycle();
        }
    }
    /** A gesture through these points, sampled finely like a real finger. */
    private static void trace(BoardView board, float[][] corners) {
        long now = android.os.SystemClock.uptimeMillis();
        java.util.List<float[]> points = new java.util.ArrayList<>();
        for (int i = 1; i < corners.length; i++)
            for (int step = 0; step < 50; step++) {
                float f = step / 50f;
                points.add(new float[]{corners[i-1][0] + (corners[i][0]-corners[i-1][0])*f,
                                       corners[i-1][1] + (corners[i][1]-corners[i-1][1])*f});
            }
        points.add(corners[corners.length-1]);
        for (int i = 0; i <= points.size(); i++) {
            float[] p = points.get(Math.min(i, points.size()-1));
            int action = i == 0 ? android.view.MotionEvent.ACTION_DOWN
                : i == points.size() ? android.view.MotionEvent.ACTION_UP : android.view.MotionEvent.ACTION_MOVE;
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now+i*8, action,
                p[0]*board.getWidth(), p[1]*board.getHeight(), 0);
            board.dispatchTouchEvent(event); event.recycle();
        }
    }
    private static void setHeld(Activity activity, String name, Object value) throws Exception {
        java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); field.set(activity,value);
    }
    private static void invokeTactic(Activity activity, String name) throws Exception {
        java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(activity);
    }
    private static void dragBoard(BoardView board, float x, float y, float endX, float endY, boolean cancel) {
        long now = android.os.SystemClock.uptimeMillis();
        int[] actions = {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE,
            android.view.MotionEvent.ACTION_MOVE, cancel ? android.view.MotionEvent.ACTION_CANCEL : android.view.MotionEvent.ACTION_UP};
        float[] xs = {x, x+(endX-x)*.15f, endX, endX}, ys = {y,y+(endY-y)*.15f,endY,endY};
        for (int i=0;i<actions.length;i++) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now,now+i*40,actions[i],xs[i]*board.getWidth(),ys[i]*board.getHeight(),0);
            board.dispatchTouchEvent(event); event.recycle();
        }
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

    private static void checkClubFixtures() throws Exception {
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
        // The last match played is a finished one that is behind: the list above also holds a
        // finished fixture dated ahead of now, which is no more played than a scheduled one.
        fixtures.put(fixture(9, "FINISHED", "2026-09-05T18:00:00Z", 10, 20));
        fixtures.put(fixture(10, "FINISHED", "2026-09-06T18:00:00Z", 10, 30));
        java.util.Map<String, JSONObject> played = FixtureSelection.previous(fixtures, clubs,
            java.time.Instant.parse("2026-09-07T12:00:00Z"));
        require(played.size() == 3, "A followed club lost the match it just played");
        require(played.get("10").getInt("id") == 10 && played.get("30").getInt("id") == 10,
            "Last match must be the latest finished one before now, on both sides");
        require(played.get("20").getInt("id") == 9,
            "A club's own last match was lost to a later one it did not play");
        require(FixtureSelection.involves(fixture(11, "TIMED", "2026-10-01T18:00:00Z", 30, 40), clubs),
            "A followed club was not recognised in its own fixture");
        require(!FixtureSelection.involves(fixture(12, "TIMED", "2026-10-01T18:00:00Z", 40, 50), clubs),
            "A fixture without a followed club passed the club filter");
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

    /** The touch goes through the real parent pager before reaching the list and its button. */
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

    /**
     * A spot already given is taken and carried through the real pager: it lands where the finger
     * lifts, the card does not turn under it, a touch that does not move is a tap on its player,
     * and a swipe that starts away from any spot still turns the card.
     */
    private void checkSpotDrag() throws Exception {
        final Throwable[] failure = {null};
        Context context = getTargetContext();
        JSONObject match;
        try (java.io.InputStream input = context.getAssets().open("match.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) bytes.write(buffer, 0, count);
            match = new JSONObject(bytes.toString("UTF-8"));
        }
        String player = match.getJSONArray("players").getJSONObject(9).getString("id");
        runOnMainSync(() -> {
            try {
                String[] tapped = {""}, moved = {""};
                double[][] landed = {null};
                Pager pager = new Pager(context);
                PitchView pitch = null;
                for (int i = 0; i < 3; i++) {
                    Pull pull = new Pull(context);
                    android.view.View content = i == 1
                        ? (pitch = new PitchView(context, match, true, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF0A1E19,
                            0xFF0A1E19, 0, id -> tapped[0] = id, id -> { }))
                        : new android.view.View(context);
                    content.setMinimumHeight(1400);
                    pull.addView(content, new android.widget.FrameLayout.LayoutParams(-1, 1400));
                    pager.addPage(pull);
                }
                pitch.moving((id, at) -> { moved[0] = id; landed[0] = at; });
                java.util.Map<String,double[]> places = new java.util.HashMap<>();
                places.put(player, new double[]{.5, .5});
                pitch.setPlaces(places);
                int width = 1000, height = 1400;
                pager.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY));
                pager.layout(0, 0, width, height);
                pager.show(1, false);
                require(pitch.getWidth() == width && pitch.getHeight() == height, "Pitch not laid out for the drag");
                float slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
                // Sideways first, the way the pager would take it: the spot must win.
                swipe(pager, new float[]{500, 500 + slop * 3, 800, 800}, new float[]{700, 700, 910, 910});
                require(player.equals(moved[0]), "The spot was not carried");
                require(Math.abs(landed[0][0] - .8) < .002 && Math.abs(landed[0][1] - .65) < .002,
                    "The spot did not land under the finger");
                require(pager.page() == 1 && pager.getScrollX() == width, "Dragging a spot turned the card");
                require(tapped[0].isEmpty(), "A drag was taken for a tap");
                moved[0] = "";
                swipe(pager, new float[]{500, 500}, new float[]{700, 700});
                require(player.equals(tapped[0]) && moved[0].isEmpty(), "A tap on a spot did not reach its player");
                swipe(pager, new float[]{900, 900 - slop * 3, 50, 50}, new float[]{60, 60, 60, 60});
                require(moved[0].isEmpty() && pager.page() == 2, "A swipe away from the spots no longer turns the card");
            } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError("Spot drag", failure[0]);
    }
    /** Down, moves, up, at the given points, dispatched from the top as a real finger would be. */
    private static void swipe(android.view.View target, float[] xs, float[] ys) {
        long now = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i < xs.length; i++) {
            int action = i == 0 ? android.view.MotionEvent.ACTION_DOWN
                : i == xs.length - 1 ? android.view.MotionEvent.ACTION_UP : android.view.MotionEvent.ACTION_MOVE;
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now + i * 80, action, xs[i], ys[i], 0);
            target.dispatchTouchEvent(event); event.recycle();
        }
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
