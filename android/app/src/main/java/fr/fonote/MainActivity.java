package fr.fonote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(16, 27, 32), SURFACE = Color.rgb(20, 34, 31),
        CHIP = Color.rgb(33, 48, 45), ACCENT = Color.rgb(207, 240, 160), ON_ACCENT = Color.rgb(25, 48, 28),
        INK = Color.rgb(229, 238, 231), MUTED = Color.rgb(156, 179, 164),
        /** A stand-in kit colour: the shirt preview belongs to no team in particular. */
        SAMPLE_KIT = Color.rgb(126, 178, 235);
    private Store store;
    private SharedPreferences prefs;
    private JSONObject match;
    private JSONObject demoMatch;
    private final List<JSONObject> demoMatches = new ArrayList<>();
    private LinearLayout root, composer, factsPage, statsPage;
    private Pager pager;
    /** Which screen is shown: these pages replace the view, so back has to unwind them itself. */
    private String screen = "home";
    private PitchView pitch;
    private TextView status, clockLabel;
    private int minute;
    /** One refresh a minute while a match is current; ESPN calls its own feed stale after nine
     *  seconds, so this is conservative, and the server caches hard enough to absorb it. */
    private static final long POLL = 60_000L, WARMUP = 3600L;
    private static final int LATE = 140;
    private long polled;
    private long clockAnchor, clockBase;
    private int period;
    private boolean clockRunning;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() { updateClock(); ticker.postDelayed(this, 1000); }
    };
    /** Regulation half, and the break the referee is expected to give, in seconds. */
    private static final int HALF = 45, BREAK = 15 * 60;
    private boolean syncing;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** key → {symbole, nom, nom court, poids}. */
    private final LinkedHashMap<String,String[]> actions = new LinkedHashMap<>();
    /** The palette pairs each gesture with its failure: top row succeeded, bottom row did not. */
    private static final String[] SUCCEEDED = {"positive","goal","assist","pass","dribble","shot_on","defense","save"};
    private static final String[] FAILED = {"negative","own_goal","lost_ball","pass_missed",
        "dribble_lost","shot_off","duel_lost","save_missed","yellow","red"};

    /** The note being composed: every player involved, each with its own action. */
    private final LinkedHashMap<String,String> entries = new LinkedHashMap<>();
    /** Note currently open, new or reopened to be extended; "" when nothing is open. */
    private String noteId = "";
    /** Player the palette qualifies; "" while the note waits for one. */
    private String focus = "";
    private String draft = "", draftWritten = "";
    /** True once the open note exists in the log, so later edits must rewrite it. */
    private boolean written;
    /** How many trailing operations undo has already walked back over. */
    private int undone;
    private int noteMinute;
    /**
     * The schema an advanced note carries, or null while the open note is a plain one. A schema
     * is written beside the note under the same id, the way a comment is: the journal keeps one
     * kind of note, and the second mode is a note that also happens to be drawn.
     */
    private Diagram diagram;
    /**
     * The board as it stood before each change, while a note is open. A drawn stroke is a gesture,
     * and a gesture goes wrong: the finger slips, the pass lands on the wrong shirt, the eraser
     * takes the player instead of the line. The journal already records every state — it is what
     * ↶ walks back on the match screen — but not while a note is still being drawn, and reaching
     * for it would mean leaving the note to undo a stroke in it.
     *
     * <p>Whole schemas rather than reverse operations: a schema is bounded by design — thirty
     * tokens, forty strokes, a hundred and twenty keys — so a step costs a few kilobytes, and
     * nothing can drift out of step with the board the way a hand-written inverse would.
     */
    private final List<String> undoable = new ArrayList<>();
    private String drawnBefore = "";
    private static final int UNDOABLE = 40;
    private Button tacticUndo;
    /** What already reached the log, so a stroke never rewrites what has not changed. */
    private String entriesWritten = "", diagramWritten = "";
    private BoardView board;
    private final Handler tacticClock = new Handler(Looper.getMainLooper());
    private boolean tacticPlaying;
    private int tacticTime;
    private Runnable tacticTick;
    private void stopTactic() {
        tacticPlaying = false;
        if (tacticTick != null) tacticClock.removeCallbacks(tacticTick);
    }
    private LinearLayout tacticPanel;
    private Button tacticMinute;
    /** Fixed so that opening a note never moves the players under the finger. */
    private static final int COMPOSER = 214;
    /** The one row of the tactical panel that changes its mind, kept the same height throughout:
     *  choosing a tool or a player must never move the board under the finger either. */
    private static final int SELECTION = 44;
    /**
     * What the provider counts, in the order it reads. ESPN publishes twenty-eight figures, over
     * half of them percentages that only restate the pair above them — a note-taker wants the
     * counts, not `passPct` next to `totalPasses` and `accuratePasses`.
     */
    /**
     * What the provider counts for one player, in reading order, singular then plural. Zeroes are
     * left out — a line of twelve noughts says nothing — and `appearances`, always one for whoever
     * played, and `subIns`, which the pitch already showed, are left out for the same reason.
     */
    private static final String[][] TALLY = {
        {"totalGoals", "but", "buts"}, {"goalAssists", "passe déc.", "passes déc."},
        {"ownGoals", "csc", "csc"}, {"totalShots", "tir", "tirs"},
        {"shotsOnTarget", "cadré", "cadrés"}, {"saves", "arrêt", "arrêts"},
        {"goalsConceded", "encaissé", "encaissés"}, {"shotsFaced", "tir subi", "tirs subis"},
        {"foulsCommitted", "faute", "fautes"}, {"foulsSuffered", "faute subie", "fautes subies"},
        {"yellowCards", "jaune", "jaunes"}, {"redCards", "rouge", "rouges"}};
    /**
     * Counters the provider carries on every player's line although they describe the side: an
     * outfielder shown "1 encaissé" reads as his own mistake. They are kept for the one player
     * they actually describe, and the pitch already knows which one that is.
     */
    private static final Set<String> KEEPER = new HashSet<>(Arrays.asList(
        "saves", "goalsConceded", "shotsFaced"));
    private static final String[][] COUNTED = {
        {"possessionPct", "Possession", " %"}, {"totalShots", "Tirs", ""},
        {"shotsOnTarget", "Tirs cadrés", ""}, {"wonCorners", "Corners", ""},
        {"offsides", "Hors-jeu", ""}, {"totalPasses", "Passes", ""},
        {"accuratePasses", "Passes réussies", ""}, {"totalTackles", "Tacles", ""},
        {"interceptions", "Interceptions", ""}, {"saves", "Arrêts", ""},
        {"foulsCommitted", "Fautes", ""}, {"yellowCards", "Cartons jaunes", ""},
        {"redCards", "Cartons rouges", ""}};
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRANCE);

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(getApplicationContext());
        prefs = getSharedPreferences("fonote", MODE_PRIVATE);
        // Weight carries the polarity: colour, palette side and the balance all derive from it,
        // instead of asking for "good" or "bad" as if it were a separate action.
        action("positive", "+", "Bonne action", "Bon", 1);
        action("goal", "⚽", "But", "But", 4);
        action("assist", "→", "Passe décisive", "Passe D", 3);
        action("pass", "↗", "Bonne passe", "Passe", 1);
        action("dribble", "↝", "Dribble réussi", "Dribble", 1);
        action("shot_on", "◎", "Tir cadré", "Tir", 1);
        action("defense", "◇", "Geste défensif", "Défense", 1);
        action("save", "⛊", "Arrêt", "Arrêt", 2);
        action("negative", "−", "Mauvaise action", "Raté", -1);
        action("own_goal", "↩", "CSC", "CSC", -4);
        action("lost_ball", "×", "Perte de balle", "Perte", -1);
        action("pass_missed", "↘", "Passe ratée", "Passe R", -1);
        action("dribble_lost", "⤫", "Dribble raté", "Dribble R", -1);
        action("shot_off", "○", "Tir manqué", "Tir M", -1);
        action("duel_lost", "✕", "Duel perdu", "Duel", -1);
        action("save_missed", "⚑", "Arrêt raté", "Arrêt R", -2);
        action("yellow", "▨", "Jaune", "Jaune", -1);
        action("red", "▣", "Rouge", "Rouge", -3);
        try {
            try (java.io.InputStream input = getAssets().open("match.json")) {
                match = new JSONObject(readText(input)); demoMatch = match;
                try {
                    demoMatches.addAll(loadBundledDemoMatches());
                    if (!demoMatches.isEmpty()) { demoMatch = demoMatches.get(0); match = demoMatch; }
                } catch (Exception bundledError) {
                    throw new java.io.IOException("Impossible de charger les matchs ESPN de démonstration", bundledError);
                }
            }
            if (saved != null) {
                noteId = saved.getString("note_id", "");
                focus = saved.getString("focus", "");
                draft = saved.getString("draft", "");
                draftWritten = saved.getString("draft_written", "");
                written = saved.getBoolean("written", false);
                noteMinute = saved.getInt("note_minute", 0);
                loadEntries(new JSONArray(saved.getString("entries", "[]")));
                entriesWritten = saved.getString("entries_written", "");
                tacticTime = saved.getInt("tactic_time", 0);
                diagramWritten = saved.getString("diagram_written", "");
                String drawn = saved.getString("diagram", "");
                if (!drawn.isEmpty()) diagram = Diagram.from(new JSONObject(drawn));
            }
            // A real fixture can supply its actual kickoff. The demo starts on first opening.
            clockAnchor = prefs.getLong("clock_anchor", match.optLong("kickoff_epoch_ms", System.currentTimeMillis()));
            clockBase = prefs.getLong("clock_base", 0);
            clockRunning = prefs.getBoolean("clock_running", true);
            period = prefs.getInt("clock_period", 1);
            saveClock();
            showHome();
        } catch (Exception e) { error(e); }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("note_id", noteId); state.putString("focus", focus);
        state.putString("draft", draft); state.putString("draft_written", draftWritten);
        state.putBoolean("written", written); state.putInt("note_minute", noteMinute);
        state.putString("entries", draftEntries().toString());
        state.putString("entries_written", entriesWritten);
        state.putString("diagram_written", diagramWritten);
        state.putInt("tactic_time", board == null ? tacticTime : board.time());
        state.putString("diagram", diagram == null ? "" : drawnJson());
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        ticker.removeCallbacks(tick);
        worker.shutdown();
        super.onDestroy();
    }
    private Pager browsePager;
    private FrameLayout homeCard, calendarCard, annotatedCard;
    private LinearLayout homeRoot, calendarRoot, annotatedRoot;
    private String calendarSearch = "", annotatedSearch = "";
    private JSONArray calendarFixtures = new JSONArray();
    private LocalDate calendarCentre = LocalDate.now();

    private boolean browsing() {
        return browsePager != null && ("home".equals(screen) || "calendar".equals(screen) || "annotated".equals(screen));
    }

    private void selectBrowsePage() {
        int selected = browsePager.page();
        screen = selected == 0 ? "annotated" : selected == 1 ? "home" : "calendar";
        root = selected == 0 ? annotatedRoot : selected == 1 ? homeRoot : calendarRoot;
        if (selected == 2) updateFavoriteButtons(calendarRoot);
        if (selected == 0) updateFavoriteButtons(annotatedRoot);
    }

    private void updateFavoriteButtons(android.view.ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageButton && child.getTag() instanceof JSONObject) {
                JSONObject fixture = (JSONObject) child.getTag();
                favoriteAppearance((ImageButton) child, fixture.optString("id"),
                    fixture.optJSONObject("homeTeam").optString("name") + " contre "
                    + fixture.optJSONObject("awayTeam").optString("name"));
            } else if (child instanceof android.view.ViewGroup) updateFavoriteButtons((android.view.ViewGroup) child);
        }
    }

    @Override public void onBackPressed() {
        if ("tactic".equals(screen)) { leaveTactic(); return; }
        if ("match".equals(screen)) {
            // Undo the screen before undoing the work: the card first, then the note, then leave.
            // One card at a time: facts sit between the pitch and the figures.
            if (pager != null && pager.page() > 0) { pager.show(pager.page() - 1, true); return; }
            if (!noteId.isEmpty()) { closeNote(); return; }
            showHome(); return;
        }
        if (!"home".equals(screen)) { showHome(); return; }
        super.onBackPressed();
    }
    @Override protected void onResume() { super.onResume(); ticker.removeCallbacks(tick); ticker.post(tick); }
    @Override protected void onPause() { stopTactic(); ticker.removeCallbacks(tick); super.onPause(); }
    private long clockSeconds() { return MatchClock.seconds(System.currentTimeMillis(), clockAnchor, clockBase, clockRunning); }
    private void updateClock() {
        int previous = minute;
        minute = (int)(clockSeconds()/60);
        if (clockLabel == null) return;
        // The pitch belongs to the same clock as the notes: one minute, one set of players.
        if (minute != previous && pitch != null) pitch.setMinute(minute);
        follow();
        if (minute != previous && !noteId.isEmpty() && minute - noteMinute == 2) renderComposer();
        long left = (clockAnchor - System.currentTimeMillis()) / 1000;
        // A match whistled off is fixed at the minute it ended: seconds tick for nobody.
        boolean over = match != null && match.optLong("end_epoch_ms") > 0 && !clockRunning;
        clockLabel.setText(clockRunning && left > 0
            ? "⏳  " + MatchClock.countdown(left) + "  ⌄"
            : over ? "⏹  " + MatchClock.stamp(clockSeconds(), period) + "  ⌄"
            : (clockRunning ? "●  " : "Ⅱ  ") + MatchClock.display(clockSeconds(), period) + "  ⌄");
    }
    /**
     * A match being watched is re-read once a minute, so a substitution reaches the pitch while
     * it still matters. Bounded on every side: only the match screen, only a match of the day,
     * and never while a note is open — the players must not move under the finger mid-note.
     */
    private void follow() {
        if (!"match".equals(screen) || match == null || !noteId.isEmpty() || !hasServer()) return;
        if (!match.optString("id").startsWith("fd-")) return;
        // A match the provider has whistled off has nothing left to publish.
        if (match.optLong("end_epoch_ms") > 0) return;
        long ahead = (clockAnchor - System.currentTimeMillis()) / 1000;
        // Compositions are published about an hour before kickoff and the match is over well
        // after ninety minutes: outside that window there is nothing new to learn.
        if (ahead > 0 ? ahead > WARMUP : minute > LATE) return;
        long now = System.currentTimeMillis();
        if (now - polled < POLL) return;
        polled = now;
        String id = match.optString("id").substring(3);
        worker.execute(() -> {
            try {
                JSONObject fresh = convertMatch(new JSONObject(get("/v1/football/matches/" + id)));
                runOnUiThread(() -> absorb(fresh));
            } catch (Exception unreachable) {
                // A refresh that fails is a refresh missed, not an error to put on screen:
                // the notes are local and the next minute tries again.
            }
        });
    }

    /**
     * The pitch takes a newer run of play in place; a composition that has changed shape —
     * published late, or a squad that differs — earns a redraw instead.
     */
    private void absorb(JSONObject fresh) {
        if (!"match".equals(screen) || match == null || !noteId.isEmpty()) return;
        if (!fresh.optString("id").equals(match.optString("id"))) return;
        boolean same = squad(fresh).equals(squad(match));
        match = fresh;
        adoptClock();
        if (!same || pitch == null || factsPage == null && told()
            || statsPage == null && counted()) { showMatch(); return; }
        pitch.setChanges(fresh.optJSONArray("changes"), minute);
        // The card is rebuilt where it stands: a reader of the facts is not sent back to the pitch.
        if (factsPage != null) renderFacts();
        if (statsPage != null) renderStats();
    }

    /** Who the match knows about, as one comparable string. */
    private String squad(JSONObject source) {
        JSONArray players = source.optJSONArray("players");
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; players != null && i < players.length(); i++)
            ids.add(players.optJSONObject(i).optString("id"));
        java.util.Collections.sort(ids);
        return String.join(",", ids);
    }

    private void saveClock() {
        String key = "clock_" + (match == null ? "default" : match.optString("id", "default"));
        prefs.edit().putLong(key + "_anchor", clockAnchor).putLong(key + "_base", clockBase)
            .putBoolean(key + "_running", clockRunning).putInt(key + "_period", period).apply();
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }
    private static String readText(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toString("UTF-8");
    }
    private LinearLayout frame() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16)); box.setBackgroundColor(BACKGROUND);
        return box;
    }
    private LinearLayout page(String title) { return page(title, null); }
    /**
     * Scrolling page, for lists. The match page lays itself out to the screen instead.
     * Back sits at the top left and actions at the top right, where every other Android
     * application puts them, and the returned bar is where a screen hangs its own actions.
     */
    private LinearLayout page(String title, Runnable back) {
        return page(title, back, -1);
    }

    private LinearLayout page(String title, Runnable back, int card) {
        ScrollView scroll = new ScrollView(this);
        // Without this the page ends where its content does and the window shows through.
        scroll.setFillViewport(true); scroll.setBackgroundColor(BACKGROUND);
        // The system bars are part of the page: a black strip above the title is a seam.
        getWindow().setStatusBarColor(BACKGROUND); getWindow().setNavigationBarColor(BACKGROUND);
        root = frame(); root.setPadding(dp(16), dp(10), dp(16), dp(24));
        scroll.addView(root);
        if (card < 0) setContentView(scroll);
        else {
            FrameLayout host = card == 0 ? homeCard : card == 1 ? calendarCard : annotatedCard;
            host.removeAllViews(); host.addView(scroll);
            if (card == 0) homeRoot = root; else if (card == 1) calendarRoot = root; else annotatedRoot = root;
        }
        LinearLayout bar = strip(); root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        if (back != null) bar.addView(barAction(R.drawable.ic_arrow_back, "Revenir", back), barSize(0));
        TextView heading = new TextView(this);
        heading.setText(title); heading.setTextSize(26); heading.setTextColor(Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(back == null ? 0 : dp(10), dp(8), 0, dp(8));
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        return bar;
    }
    /**
     * A round icon action for the bar, at the 44 dp target a finger expects. The icon is a vector
     * drawable of our own: the platform's own ic_menu_* are Gingerbread rasters that differ from
     * one manufacturer to the next, and a font glyph like ⚙ lands on whichever emoji the system has.
     */
    private ImageButton barAction(int icon, String described, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(INK));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setBackground(tappable(rounded(CHIP, 22)));
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setContentDescription(described);
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private LinearLayout.LayoutParams barSize(int leftMargin) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(44), dp(44));
        size.leftMargin = dp(leftMargin); size.gravity = Gravity.CENTER_VERTICAL;
        return size;
    }
    /** Touch feedback: a surface that answers the finger is the cheapest sign of a live control. */
    private Drawable tappable(Drawable surface) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), surface, null);
    }
    /** A section heading, with an optional text action on its right the way lists do it. */
    private LinearLayout section(String title, String action, Runnable go) {
        LinearLayout row = strip();
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14); p.bottomMargin = dp(6); root.addView(row, p);
        TextView heading = new TextView(this);
        heading.setText(title); heading.setTextSize(20); heading.setTextColor(Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) row.addView(link(action, go));
        return row;
    }
    /** A text action: modern shorthand for "there is more this way". */
    private Button link(String text, Runnable action) {
        Button button = button(text, action);
        button.setTextSize(13); button.setTextColor(ACCENT);
        button.setBackground(tappable(rounded(Color.TRANSPARENT, 12)));
        button.setPadding(dp(10), 0, dp(10), 0); button.setMinHeight(dp(40));
        return button;
    }
    private TextView label(String text) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(16);
        view.setTextColor(Color.WHITE); view.setPadding(0, dp(8), 0, dp(8)); root.addView(view); return view;
    }
    private Button button(String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false);
        button.setTextSize(13); button.setTextColor(INK);
        button.setBackground(tappable(rounded(CHIP, 12)));
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        button.setMinHeight(dp(48)); button.setOnClickListener(v -> action.run()); return button;
    }
    private Button accent(Button button) {
        button.setBackground(tappable(rounded(ACCENT, 12))); button.setTextColor(ON_ACCENT); return button;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    /** A soft edge is the only hint that a row keeps going past the screen. */
    private void fade(HorizontalScrollView scroll) {
        scroll.setHorizontalFadingEdgeEnabled(true); scroll.setFadingEdgeLength(dp(22));
    }
    /**
     * A row that scrolls sideways, inside a page that also turns sideways.
     *
     * <p>It claims the gesture in {@code onInterceptTouchEvent}, which a parent sees before its
     * own children: a listener would never fire here, because the buttons of the palette consume
     * the touch first. Claiming only when there is something to scroll leaves a row that already
     * fits free to turn the card instead.
     */
    private HorizontalScrollView sideways() {
        HorizontalScrollView scroll = new HorizontalScrollView(this) {
            @Override public boolean onInterceptTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && (canScrollHorizontally(1) || canScrollHorizontally(-1)))
                    getParent().requestDisallowInterceptTouchEvent(true);
                return super.onInterceptTouchEvent(event);
            }
        };
        scroll.setHorizontalScrollBarEnabled(false); fade(scroll);
        return scroll;
    }
    /** Small inline control living on a line of text. */
    private Button mini(String text, String described, Runnable action) {
        Button button = button(text, action);
        button.setTextSize(11); button.setTextColor(MUTED); button.setMinHeight(0);
        button.setPadding(dp(4), 0, dp(4), 0); button.setContentDescription(described);
        return button;
    }
    private LinearLayout strip() {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); return row;
    }
    private void full(String text, Runnable action) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(8);
        Button button = button(text, action);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.addView(button, p);
    }

    // ——— Accueil, calendrier et préférences ———

    private void showHome() {
        if (browsing()) {
            browsePager.show(1, true);
            selectBrowsePage();
            return;
        }
        browsePager = new Pager(this);
        homeCard = new FrameLayout(this); calendarCard = new FrameLayout(this);
        annotatedCard = new FrameLayout(this);
        browsePager.addPage(annotatedCard); browsePager.addPage(homeCard); browsePager.addPage(calendarCard);
        renderAnnotated();
        showHomeShell();
        loadFixtures(LocalDate.now(), LocalDate.now(), true);
        renderCalendar(calendarCentre);
        browsePager.onTurn(this::selectBrowsePage);
        browsePager.show(1, false);
        selectBrowsePage();
        setContentView(browsePager);
        refreshClubFixtures();
    }

    private void calendar(LocalDate centre) {
        if (!browsing()) showHome();
        renderCalendar(centre);
        browsePager.show(2, true);
        selectBrowsePage();
    }

    private void renderCalendar(LocalDate centre) {
        calendarCentre = centre;
        screen = "calendar";
        LinearLayout title = page("Calendrier", this::showHome, 1);
        // Two arrows and the window they move: lighter than three buttons, and it says the dates.
        LinearLayout dates = strip();
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
        row.topMargin = dp(6); root.addView(dates, row);
        dates.addView(barAction(R.drawable.ic_chevron_left, "Semaine précédente",
            () -> calendar(centre.minusDays(7))), barSize(0));
        TextView date = new TextView(this);
        DateTimeFormatter span = DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE);
        date.setText("du " + centre.minusDays(3).format(span) + " au " + centre.plusDays(3).format(span));
        date.setTextColor(INK); date.setTextSize(15); date.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        date.setGravity(Gravity.CENTER);
        dates.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
        dates.addView(barAction(R.drawable.ic_chevron_right, "Semaine suivante",
            () -> calendar(centre.plusDays(7))), barSize(0));
        // Only fixtures scroll: the title and week controls keep the page identifiable.
        ScrollView fixtures = (ScrollView) root.getParent();
        root.removeView(title); root.removeView(dates);
        calendarCard.removeAllViews();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(BACKGROUND);
        LinearLayout header = frame();
        header.setPadding(dp(16), dp(10), dp(16), dp(12));
        header.addView(title); header.addView(dates, row);
        // The week arrows are round and heavy: the field needs air under them, more than the
        // hairline the arrows leave on their own.
        LinearLayout.LayoutParams search = new LinearLayout.LayoutParams(-1, -2);
        search.topMargin = dp(10);
        header.addView(searchField("Rechercher dans cette semaine", calendarSearch, query -> {
            calendarSearch = query;
            LinearLayout previous = root; root = calendarRoot;
            renderFixtures(calendarFixtures, false); root = previous;
        }), search);
        layout.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(this); divider.setBackgroundColor(CHIP);
        layout.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        root.setPadding(dp(16), 0, dp(16), dp(24));
        layout.addView(fixtures, new LinearLayout.LayoutParams(-1, 0, 1));
        calendarCard.addView(layout);
        loadFixtures(centre.minusDays(3), centre.plusDays(3), false);
    }

    private EditText searchField(String hint, String value, java.util.function.Consumer<String> changed) {
        EditText field = new EditText(this);
        field.setSingleLine(true); field.setTextColor(INK); field.setHintTextColor(MUTED);
        field.setTextSize(15); field.setHint(hint); field.setText(value);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        // Consume submission: TextView's default Enter handling moves focus to the next
        // search field, which can be on another card and scroll the pager there.
        field.setOnEditorActionListener((view, action, event) -> {
            boolean enter = event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER;
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH && !enter) return false;
            if (event == null || event.getAction() == android.view.KeyEvent.ACTION_UP) {
                android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
            return true;
        });
        field.setBackground(rounded(CHIP, 12));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setContentDescription(hint);
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) { changed.accept(text.toString()); }
            public void afterTextChanged(android.text.Editable text) {}
        });
        return field;
    }

    private void renderAnnotated() {
        screen = "annotated";
        LinearLayout title = page("Mes matchs annotés", this::showHome, 2);
        ScrollView list = (ScrollView) root.getParent();
        root.removeView(title); annotatedCard.removeAllViews();
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(BACKGROUND);
        LinearLayout header = frame(); header.setPadding(dp(16), dp(10), dp(16), dp(12));
        header.addView(title);
        header.addView(searchField("Équipe, compétition, date ou note", annotatedSearch, query -> {
            annotatedSearch = query;
            LinearLayout previous = root; root = annotatedRoot;
            renderAnnotatedMatches(); root = previous;
        }));
        layout.addView(header, new LinearLayout.LayoutParams(-1, -2));
        layout.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        annotatedCard.addView(layout);
        renderAnnotatedMatches();
    }

    private void renderAnnotatedMatches() {
        root.removeAllViews();
        try {
            Map<String, List<JSONObject>> byMatch = new LinkedHashMap<>();
            for (JSONObject note : notes(null))
                byMatch.computeIfAbsent(note.optString("match_id"), ignored -> new ArrayList<>()).add(note);
            Map<String, JSONObject> known = new HashMap<>();
            JSONArray fixtures = store.fixtures();
            for (int i = 0; i < fixtures.length(); i++) {
                JSONObject fixture = fixtures.getJSONObject(i); known.put("fd-" + fixture.optString("id"), fixture);
            }
            if (demoMatch != null) known.put(demoMatch.optString("id"), demoMatch);
            for (JSONObject demo : demoMatches) known.put(demo.optString("id"), demo);
            // Include the original bundled fixture for notes made before the current demos existed.
            try (java.io.InputStream input = getAssets().open("match.json")) {
                JSONObject original = new JSONObject(readText(input)); known.put(original.optString("id"), original);
            }
            int shown = 0;
            for (Map.Entry<String, List<JSONObject>> item : byMatch.entrySet()) {
                String id = item.getKey(); JSONObject fixture = known.get(id);
                if (fixture == null) {
                    if (id.startsWith("fd-")) fixture = new JSONObject().put("id", Integer.parseInt(id.substring(3)))
                        .put("homeTeam", new JSONObject().put("name", "Match " + id))
                        .put("awayTeam", new JSONObject().put("name", "Détails non téléchargés"))
                        .put("competition", new JSONObject());
                    else fixture = new JSONObject().put("id", id).put("title", "Match " + id)
                        .put("teams", new JSONArray()).put("players", new JSONArray());
                }
                boolean found = FixtureSelection.matches(fixture, annotatedSearch);
                for (JSONObject note : item.getValue())
                    found |= FixtureSelection.contains(note.optString("comment"), annotatedSearch);
                if (!found) continue;
                label(item.getValue().size() + (item.getValue().size() == 1 ? " note" : " notes")).setTextColor(MUTED);
                fixtureDate(fixture); fixtureCard(fixture, !id.startsWith("fd-")); shown++;
            }
            if (shown == 0) label(byMatch.isEmpty() ? "Les matchs où vous prenez des notes apparaîtront ici, même une fois terminés."
                : "Aucun match annoté ne correspond à cette recherche.");
        } catch (Exception error) { error(error); }
    }

    private void profile() {
        screen = "profile"; page("Mes suivis", this::showHome);
        TextView help = label("Choisissez des compétitions et/ou des clubs. Un match est affiché s’il correspond à au moins un de vos choix.");
        help.setTextColor(MUTED); help.setTextSize(13);
        try {
            String cached = store.downloaded("/v1/football/competitions");
            renderProfile(cached == null ? new JSONObject() : new JSONObject(cached),
                new JSONObject().put("matches", store.fixtures()));
            label("Vos suivis sont conservés sur cet appareil. Catalogue actualisé si le serveur est disponible.");
        } catch (Exception error) { error(error); }
        if (!hasServer()) return;
        worker.execute(() -> {
            try {
                JSONObject competitions = new JSONObject(get("/v1/football/competitions"));
                JSONObject matches = new JSONObject().put("matches", store.fixtures());
                runOnUiThread(() -> { if ("profile".equals(screen)) renderProfile(competitions, matches); });
            } catch (Exception unavailable) { /* The local catalogue remains usable. */ }
        });
    }

    private void renderProfile(JSONObject catalogue, JSONObject fixtureData) {
        page("Mes suivis", this::showHome);
        Set<String> competitionIds = new HashSet<>(prefs.getStringSet("follow_competitions", Collections.emptySet()));
        Set<String> teamIds = new HashSet<>(prefs.getStringSet("follow_teams", Collections.emptySet()));
        // Both lists arrive in the order a server or a calendar happened to hold them, and a list
        // you pick from is searched by eye: alphabetical, and by a collator rather than by code
        // point, so that Évian sits with the E's and not after Z.
        Collator alphabet = Collator.getInstance(Locale.FRANCE);
        section("Compétitions", null, null);
        JSONArray competitions = catalogue.optJSONArray("competitions");
        List<JSONObject> named = new ArrayList<>();
        if (competitions != null) for (int i = 0; i < competitions.length(); i++) {
            JSONObject item = competitions.optJSONObject(i);
            if (item != null) named.add(item);
        }
        named.sort((one, other) -> alphabet.compare(one.optString("name"), other.optString("name")));
        for (JSONObject item : named) {
            String id = String.valueOf(item.optInt("id"));
            CheckBox choice = choice(item.optString("name"), competitionIds.contains(id));
            choice.setOnCheckedChangeListener((v, checked) -> saveChoice("follow_competitions", id, checked));
        }
        section("Clubs des matchs enregistrés", null, null);
        LinkedHashMap<String,String> teams = new LinkedHashMap<>();
        JSONArray fixtures = fixtureData.optJSONArray("matches");
        if (fixtures != null) for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.optJSONObject(i);
            JSONObject home = fixture.optJSONObject("homeTeam"), away = fixture.optJSONObject("awayTeam");
            if (home != null) teams.put(String.valueOf(home.optInt("id")), home.optString("name"));
            if (away != null) teams.put(String.valueOf(away.optInt("id")), away.optString("name"));
        }
        List<Map.Entry<String,String>> clubs = new ArrayList<>(teams.entrySet());
        clubs.sort((one, other) -> alphabet.compare(one.getValue(), other.getValue()));
        for (Map.Entry<String,String> team : clubs) {
            CheckBox choice = choice(team.getValue(), teamIds.contains(team.getKey()));
            choice.setOnCheckedChangeListener((v, checked) -> saveChoice("follow_teams", team.getKey(), checked));
        }
        TextView note = label("Les clubs des calendriers consultés restent disponibles sans connexion.");
        note.setTextColor(MUTED); note.setTextSize(12);
    }

    private CheckBox choice(String text, boolean checked) {
        CheckBox box = new CheckBox(this); box.setText(text); box.setChecked(checked);
        box.setTextColor(INK); box.setTextSize(15); box.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        box.setPadding(dp(4), dp(5), dp(4), dp(5)); root.addView(box, new LinearLayout.LayoutParams(-1, dp(48)));
        return box;
    }

    private void saveChoice(String key, String id, boolean checked) {
        Set<String> values = new HashSet<>(prefs.getStringSet(key, Collections.emptySet()));
        if (checked) values.add(id); else values.remove(id);
        prefs.edit().putStringSet(key, values).apply();
    }

    private final int[] fixturesRequests = new int[2];

    private JSONArray localFixtures(LocalDate from, LocalDate to) throws Exception {
        List<JSONObject> found = new ArrayList<>();
        JSONArray all = store.fixtures();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.getJSONObject(i);
            ZonedDateTime kickoff = local(item.optString("utcDate"));
            if (from == null || (kickoff != null && !kickoff.toLocalDate().isBefore(from)
                    && !kickoff.toLocalDate().isAfter(to))) found.add(item);
        }
        found.sort(Comparator.comparing(item -> item.optString("utcDate")));
        return new JSONArray(found);
    }

    private void savedMatches() {
        screen = "saved"; page("Matchs enregistrés", this::showHome);
        label("Disponibles sur cet appareil. Les scores et compositions peuvent dater de la dernière connexion.");
        try {
            JSONArray all = localFixtures(null, null);
            for (int i = 0; i < all.length(); i++) fixtureCard(all.getJSONObject(i), false);
            if (all.length() == 0) label("Aucun match téléchargé. Consultez le calendrier avec le serveur pour en conserver une copie.");
        } catch (Exception error) { error(error); }
    }

    private void loadFixtures(LocalDate from, LocalDate to, boolean home) {
        int slot = home ? 0 : 1;
        int request = ++fixturesRequests[slot];
        Pager requestedPager = browsePager;
        if (prefs.getBoolean("demo_mode", true)) {
            showOfflineFixtures(home);
            return;
        }
        try {
            renderFixtures(localFixtures(from, to), home);
            label("Copie locale · actualisation si le serveur est disponible.");
        } catch (Exception error) { error(error); }
        if (!hasServer()) return;
        worker.execute(() -> {
            try {
                JSONObject data = new JSONObject(get("/v1/football/matches?dateFrom=" + from + "&dateTo=" + to.plusDays(1)));
                runOnUiThread(() -> {
                    if (request != fixturesRequests[slot] || requestedPager != browsePager || !browsing()) return;
                    root = home ? homeRoot : calendarRoot;
                    renderFixtures(data.optJSONArray("matches"), home);
                    selectBrowsePage();
                    if (!home) refreshHomeCard();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (request != fixturesRequests[slot] || requestedPager != browsePager || !browsing()) return;
                    root = home ? homeRoot : calendarRoot;
                    try { renderFixtures(localFixtures(from, to), home); }
                    catch (Exception localError) { error(localError); }
                    label("Serveur indisponible · données enregistrées, pouvant être anciennes. Les autres matchs restent accessibles dans Matchs enregistrés.");
                    selectBrowsePage();
                });
            }
        });
    }

    private void showOfflineFixtures(boolean home) {
        if (!home) {
            empty("Le calendrier nécessite le serveur. Le match de démonstration est disponible à l’accueil.", "Accueil", this::showHome);
            return;
        }
        showHomeShell();
        for (JSONObject demo : demoMatches) fixtureCard(demo, true);
        TextView offline = label("Mode démo hors ligne · ces deux matchs sont disponibles sans serveur.");
        offline.setTextColor(MUTED); offline.setTextSize(12);
    }

    /** Two self-contained fixtures so a freshly installed app remains useful without a server. */
    private List<JSONObject> loadBundledDemoMatches() throws Exception {
        JSONArray bundled;
        try (java.io.InputStream input = getAssets().open("demo-matches.json")) {
            bundled = new JSONArray(readText(input));
        }
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < bundled.length(); i++) {
            JSONObject source = bundled.getJSONObject(i);
            JSONObject converted = convertMatch(source);
            boolean finished = "FINISHED".equals(source.optString("status")) || mark(source, "end") != 0;
            String home = source.optJSONObject("homeTeam").optString("name");
            String away = source.optJSONObject("awayTeam").optString("name");
            converted.put("id", finished ? "demo-psg-monaco-2026-09-04" : "demo-strasbourg-monaco-2026-09-12")
                .put("title", home + " · " + away)
                .put("stage", source.optString("stage") + (finished ? " · match terminé" : " · prochain match"))
                .put("date", source.optString("utcDate"))
                .put("demo_status", finished ? "FINI" : "À VENIR")
                .put("demo_score_home", source.optJSONObject("score").optJSONObject("fullTime").optString("home", ""))
                .put("demo_score_away", source.optJSONObject("score").optJSONObject("fullTime").optString("away", ""));
            result.add(converted);
        }
        return result;
    }

    private List<JSONObject> buildDemoMatches(JSONObject template) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        JSONObject finished = new JSONObject(template.toString());
        finished.put("id", "demo-psg-monaco-2026-09-04")
            .put("title", "Paris Saint-Germain · AS Monaco")
            .put("competition", "Ligue 1")
            .put("stage", "J3 · match terminé")
            .put("date", "2026-09-04")
            .put("demo_kickoff", "21:05")
            .put("demo_status", "FINI")
            .put("demo_score_home", "1")
            .put("demo_score_away", "2");
        JSONArray finishedTeams = finished.getJSONArray("teams");
        finishedTeams.getJSONObject(0).put("name", "Paris Saint-Germain");
        finishedTeams.getJSONObject(1).put("name", "AS Monaco");
        finishedTeams.getJSONObject(0).put("formation", "4-3-3");
        finishedTeams.getJSONObject(1).put("formation", "4-2-3-1");
        finished.put("players", demoPsgMonacoPlayers());
        result.add(finished);

        JSONObject upcoming = new JSONObject(template.toString());
        upcoming.put("id", "demo-strasbourg-monaco-2026-09-12")
            .put("title", "RC Strasbourg · AS Monaco")
            .put("competition", "Ligue 1")
            .put("stage", "J4 · prochain match")
            .put("date", "2026-09-12")
            .put("demo_status", "À VENIR")
            .put("demo_kickoff", "17:15");
        JSONArray upcomingTeams = upcoming.getJSONArray("teams");
        upcomingTeams.getJSONObject(0).put("name", "RC Strasbourg");
        upcomingTeams.getJSONObject(1).put("name", "AS Monaco");
        upcomingTeams.getJSONObject(0).put("formation", "4-3-3");
        upcomingTeams.getJSONObject(1).put("formation", "4-2-3-1");
        upcoming.put("players", demoStrasbourgMonacoPlayers());
        result.add(upcoming);
        return result;
    }

    private JSONObject demoPlayer(String id, String name, int number, String team,
                                  String position, double x, double y) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("number", number)
            .put("team", team).put("position", position).put("x", x).put("y", y);
    }

    private JSONArray demoPsgMonacoPlayers() throws Exception {
        JSONArray players = new JSONArray();
        players.put(demoPlayer("demo-psg-safonov", "Matvey Safonov", 39, "home", "Goalkeeper", .50, .055));
        players.put(demoPlayer("demo-psg-hakimi", "Achraf Hakimi", 2, "home", "Right Back", .14, .17));
        players.put(demoPlayer("demo-psg-marquinhos", "Marquinhos", 5, "home", "Centre Back", .36, .16));
        players.put(demoPlayer("demo-psg-pacho", "Willian Pacho", 51, "home", "Centre Back", .64, .16));
        players.put(demoPlayer("demo-psg-zaire", "Warren Zaïre-Emery", 33, "home", "Left Back", .86, .17));
        players.put(demoPlayer("demo-psg-neves", "João Neves", 87, "home", "Midfielder", .28, .29));
        players.put(demoPlayer("demo-psg-vitinha", "Vitinha", 17, "home", "Midfielder", .50, .27));
        players.put(demoPlayer("demo-psg-fabian", "Fabián Ruiz", 8, "home", "Midfielder", .72, .29));
        players.put(demoPlayer("demo-psg-akliouche", "Maghnes Akliouche", 11, "home", "Right Wing", .20, .42));
        players.put(demoPlayer("demo-psg-doue", "Désiré Doué", 14, "home", "Centre Forward", .50, .45));
        players.put(demoPlayer("demo-psg-kvaratskhelia", "Khvicha Kvaratskhelia", 7, "home", "Left Wing", .80, .42));
        players.put(demoPlayer("demo-monaco-hradeky", "Lukáš Hrádecký", 1, "away", "Goalkeeper", .50, .945));
        players.put(demoPlayer("demo-monaco-vanderson", "Vanderson", 2, "away", "Right Back", .14, .83));
        players.put(demoPlayer("demo-monaco-sane", "Sadibou Sané", 72, "away", "Centre Back", .36, .84));
        players.put(demoPlayer("demo-monaco-dier", "Eric Dier", 15, "away", "Centre Back", .64, .84));
        players.put(demoPlayer("demo-monaco-nazinho", "Flávio Nazinho", 20, "away", "Left Back", .86, .83));
        players.put(demoPlayer("demo-monaco-zakaria", "Denis Zakaria", 6, "away", "Midfielder", .35, .70));
        players.put(demoPlayer("demo-monaco-camara", "Lamine Camara", 8, "away", "Midfielder", .65, .70));
        players.put(demoPlayer("demo-monaco-idumbo", "Stanis Idumbo", 17, "away", "Right Wing", .20, .58));
        players.put(demoPlayer("demo-monaco-golovin", "Aleksandr Golovin", 10, "away", "Attacking Midfielder", .50, .57));
        players.put(demoPlayer("demo-monaco-coulibaly", "Mamadou Coulibaly", 28, "away", "Left Wing", .80, .58));
        players.put(demoPlayer("demo-monaco-brunner", "Paris Brunner", 29, "away", "Centre Forward", .50, .48));
        return players;
    }

    /** Generic placeholders keep the not-yet-played fixture usable without inventing a lineup. */
    private JSONArray demoStrasbourgMonacoPlayers() throws Exception {
        JSONArray players = new JSONArray();
        double[] homeX = {.50, .14, .36, .64, .86, .28, .50, .72, .20, .50, .80};
        double[] homeY = {.055, .17, .16, .16, .17, .29, .27, .29, .42, .45, .42};
        double[] awayX = {.50, .14, .36, .64, .86, .35, .65, .20, .50, .80, .50};
        double[] awayY = {.945, .83, .84, .84, .83, .70, .70, .58, .57, .58, .48};
        for (int i = 0; i < 11; i++) {
            players.put(demoPlayer("demo-strasbourg-" + (i + 1), "Strasbourg " + (i + 1), i + 1,
                "home", i == 0 ? "Goalkeeper" : "Player", homeX[i], homeY[i]));
            players.put(demoPlayer("demo-monaco-upcoming-" + (i + 1), "Monaco " + (i + 1), i + 1,
                "away", i == 0 ? "Goalkeeper" : "Player", awayX[i], awayY[i]));
        }
        return players;
    }

    private void renderFixtures(JSONArray fixtures, boolean home) {
        if (home) showHomeShell(); else {
            calendarFixtures = fixtures == null ? new JSONArray() : fixtures;
            calendarShellOnly();
        }
        int shown = 0;
        String day = "";
        if (fixtures != null) for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.optJSONObject(i);
            if (!follows(fixture) || (!home && !FixtureSelection.matches(fixture, calendarSearch))) continue;
            // A week of fixtures is a week of days: say which one, once, above its matches.
            if (!home) {
                ZonedDateTime kickoff = local(fixture.optString("utcDate"));
                String named = kickoff == null ? "Date inconnue" : kickoff.format(DAY);
                if (!named.equals(day)) { day = named; dayHeader(named); }
            }
            fixtureCard(fixture, false); shown++;
        }
        if (shown == 0) {
            if (!home && !calendarSearch.trim().isEmpty()) label("Aucun match ne correspond à cette recherche dans la semaine affichée.");
            else empty("Aucun match correspondant à vos suivis sur cette période.", "Choisir mes suivis", this::profile);
        }
    }

    /** Kickoff in the reader's own zone: UTC on a home screen is a machine's idea of a clock. */
    private ZonedDateTime local(String utc) {
        try { return Instant.parse(utc).atZone(ZoneId.systemDefault()); }
        catch (Exception unparsable) { return null; }
    }

    private void dayHeader(String named) {
        TextView header = new TextView(this);
        header.setText(named.toUpperCase(Locale.FRANCE));
        header.setTextSize(11); header.setTextColor(MUTED); header.setLetterSpacing(.09f);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14); p.bottomMargin = dp(6); root.addView(header, p);
    }

    private void loading(String message) {
        LinearLayout row = strip(); row.setPadding(dp(2), dp(20), 0, dp(20));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        row.addView(spinner, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView text = new TextView(this); text.setText(message);
        text.setTextColor(MUTED); text.setTextSize(14); text.setPadding(dp(12), 0, 0, 0);
        row.addView(text); root.addView(row);
    }

    /** Nothing to show is still something to say, with the way out beside it. */
    private void empty(String message, String action, Runnable go) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER);
        box.setPadding(dp(16), dp(26), dp(16), dp(20)); box.setBackground(rounded(SURFACE, 16));
        TextView text = new TextView(this); text.setText(message);
        text.setTextColor(MUTED); text.setTextSize(14); text.setGravity(Gravity.CENTER);
        box.addView(text);
        if (action != null) box.addView(link(action, go));
        root.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /** The home screen without its fixtures: drawn once on entry, redrawn when they arrive. */
    private void showHomeShell() {
        screen = "home";
        // Settings are not content: they belong in the bar as icons, not in the middle of the page.
        LinearLayout bar = page("Fonote", null, 0);
        bar.addView(barAction(R.drawable.ic_star, "Mes suivis", this::profile), barSize(8));
        bar.addView(barAction(R.drawable.ic_settings, "Options", this::options), barSize(8));
        TextView intro = label("Les matchs que vous suivez, prêts à être notés.");
        intro.setTextColor(MUTED); intro.setTextSize(14); intro.setPadding(0, 0, 0, 0);
        full("Matchs enregistrés", this::savedMatches);
        homeFollows();
        section("Aujourd’hui", "Calendrier ›", () -> calendar(LocalDate.now()));
    }

    private void homeFollows() {
        try {
            JSONArray all = localFixtures(null, null);
            Set<String> favorites = prefs.getStringSet("follow_matches", Collections.emptySet());
            section("Mes matchs favoris", "Calendrier ›", () -> calendar(calendarCentre));
            int shown = 0;
            for (int i = 0; i < all.length(); i++) {
                JSONObject fixture = all.getJSONObject(i);
                if (!favorites.contains(fixture.optString("id")) || !FixtureSelection.unfinished(fixture)) continue;
                fixtureDate(fixture); fixtureCard(fixture, false); shown++;
            }
            if (shown == 0) label("Aucun favori à venir ou en cours. Ajoutez-en avec l’étoile du calendrier.").setTextColor(MUTED);

            Set<String> clubs = prefs.getStringSet("follow_teams", Collections.emptySet());
            section("Prochains matchs de mes clubs", "Mes suivis ›", this::profile);
            Map<String, JSONObject> next = FixtureSelection.next(all, clubs, Instant.now());
            Set<String> displayed = new HashSet<>();
            for (JSONObject fixture : next.values()) {
                if (!displayed.add(fixture.optString("id"))) continue;
                fixtureDate(fixture); fixtureCard(fixture, false);
            }
            if (clubs.isEmpty()) label("Suivez un club pour afficher son prochain match.").setTextColor(MUTED);
            else if (next.size() < clubs.size())
                label("Prochain match encore inconnu pour certains clubs. Actualisation à la connexion au serveur.").setTextColor(MUTED);
        } catch (Exception error) { error(error); }
    }

    private void fixtureDate(JSONObject fixture) {
        ZonedDateTime date = local(fixture.optString("utcDate"));
        if (date != null) dayHeader(date.format(DAY));
    }

    private void refreshHomeCard() {
        if (!browsing()) return;
        try {
            if (prefs.getBoolean("demo_mode", true)) showOfflineFixtures(true);
            else renderFixtures(localFixtures(LocalDate.now(), LocalDate.now()), true);
        } catch (Exception error) { error(error); }
        selectBrowsePage();
    }

    private final Map<String, Long> clubFetched = new HashMap<>();
    private final Set<String> clubsFetching = new HashSet<>();

    private void refreshClubFixtures() {
        if (!hasServer() || prefs.getBoolean("demo_mode", true)) return;
        String base = prefs.getString("url", "");
        for (String club : prefs.getStringSet("follow_teams", Collections.emptySet())) {
            String key = base + "/" + club;
            if (clubsFetching.contains(key) || System.currentTimeMillis() - clubFetched.getOrDefault(key, 0L) < 300_000) continue;
            clubsFetching.add(key);
            worker.execute(() -> {
                boolean success = false;
                try {
                    LocalDate today = LocalDate.now();
                    String path = "/v1/football/teams/" + club + "/matches?dateFrom=" + today
                        + "&dateTo=" + today.plusYears(1) + "&limit=100";
                    store.download(path, get(base, path));
                    success = true;
                } catch (Exception unavailable) { /* Previously downloaded fixtures remain usable. */ }
                boolean fetched = success;
                runOnUiThread(() -> {
                    clubsFetching.remove(key);
                    if (fetched) clubFetched.put(key, System.currentTimeMillis());
                    refreshHomeCard();
                });
            });
        }
    }

    private void favoriteAppearance(ImageButton star, String id, String title) {
        boolean selected = prefs.getStringSet("follow_matches", Collections.emptySet()).contains(id);
        star.setImageResource(selected ? R.drawable.ic_star_filled : R.drawable.ic_star);
        star.setImageTintList(ColorStateList.valueOf(selected ? ACCENT : MUTED));
        star.setSelected(selected);
        star.setContentDescription((selected ? "Ne plus suivre " : "Suivre ") + title);
    }

    private void toggleFavorite(JSONObject fixture, ImageButton star, String title) {
        String id = fixture.optString("id");
        boolean selected = !prefs.getStringSet("follow_matches", Collections.emptySet()).contains(id);
        saveChoice("follow_matches", id, selected);
        favoriteAppearance(star, id, title);
        refreshHomeCard();
        if (selected && hasServer()) worker.execute(() -> {
            try { get("/v1/football/matches/" + id); }
            catch (Exception unavailable) { /* The calendar entry already supports offline notes. */ }
        });
    }

    private void calendarShellOnly() {
        // The fixed header lives outside this list and survives every refresh.
        root.removeAllViews();
    }

    private boolean follows(JSONObject fixture) {
        Set<String> comps = prefs.getStringSet("follow_competitions", Collections.emptySet());
        Set<String> teams = prefs.getStringSet("follow_teams", Collections.emptySet());
        if (comps.isEmpty() && teams.isEmpty()) return true;
        JSONObject competition = fixture.optJSONObject("competition");
        JSONObject home = fixture.optJSONObject("homeTeam"), away = fixture.optJSONObject("awayTeam");
        return (competition != null && comps.contains(String.valueOf(competition.optInt("id"))))
            || (home != null && teams.contains(String.valueOf(home.optInt("id"))))
            || (away != null && teams.contains(String.valueOf(away.optInt("id"))));
    }

    /**
     * One fixture, read the way a fixture is read: when on the left, who in the middle, what
     * competition underneath. A match under way says so in place of its kickoff time.
     */
    private void fixtureCard(JSONObject fixture, boolean demo) {
        String homeName = "?", awayName = "?", subtitle = "";
        String state = null; boolean live = false;
        ZonedDateTime kickoff = null;
        if (demo) {
            JSONArray teams = fixture.optJSONArray("teams");
            if (teams != null && teams.length() == 2) {
                homeName = teams.optJSONObject(0).optString("name");
                awayName = teams.optJSONObject(1).optString("name");
            }
            subtitle = fixture.optString("stage") + " · " + fixture.optString("competition");
            state = fixture.optString("demo_status", "DÉMO");
        } else {
            JSONObject home = fixture.optJSONObject("homeTeam"), away = fixture.optJSONObject("awayTeam");
            JSONObject competition = fixture.optJSONObject("competition");
            if (home != null) homeName = home.optString("shortName", home.optString("name"));
            if (away != null) awayName = away.optString("shortName", away.optString("name"));
            subtitle = competition == null ? "" : competition.optString("name");
            kickoff = local(fixture.optString("utcDate"));
            state = state(fixture.optString("status"));
            live = "IN_PLAY".equals(fixture.optString("status")) || "PAUSED".equals(fixture.optString("status"));
        }
        LinearLayout card = new LinearLayout(this); card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.setBackground(tappable(rounded(SURFACE, 16)));
        card.setClickable(true); card.setFocusable(true);
        card.setOnClickListener(v -> { if (demo) { match = fixture; openMatch(); } else openRemoteMatch(fixture); });
        card.setContentDescription(homeName + " contre " + awayName + ", " + subtitle
            + ", " + (state != null ? state : kickoff == null ? "horaire inconnu"
                : "à " + kickoff.format(HOUR)) + ", ouvrir la feuille de notes");

        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL); when.setGravity(Gravity.CENTER);
        TextView first = new TextView(this);
        first.setText(state != null ? state : kickoff == null ? "—" : kickoff.format(HOUR));
        first.setTextSize(state != null ? 11 : 17); first.setGravity(Gravity.CENTER);
        first.setTypeface(state != null ? Typeface.DEFAULT_BOLD : Typeface.MONOSPACE, Typeface.BOLD);
        first.setTextColor(live || demo ? ACCENT : INK);
        when.addView(first, new LinearLayout.LayoutParams(-1, -2));
        if (kickoff != null) {
            TextView second = new TextView(this);
            second.setText(state == null ? kickoff.format(DateTimeFormatter.ofPattern("d MMM", Locale.FRANCE))
                : kickoff.format(HOUR));
            second.setTextSize(11); second.setTextColor(MUTED); second.setGravity(Gravity.CENTER);
            when.addView(second, new LinearLayout.LayoutParams(-1, -2));
        }
        card.addView(when, new LinearLayout.LayoutParams(dp(56), -2));

        LinearLayout sides = new LinearLayout(this); sides.setOrientation(LinearLayout.VERTICAL);
        sides.setPadding(dp(12), 0, dp(8), 0);
        // A match under way or over carries its score; one still to come has nothing to say yet.
        JSONObject score = state == null || demo ? null : fixture.optJSONObject("score");
        JSONObject goals = score == null ? null : score.optJSONObject("fullTime");
        sides.addView(side(homeName, demo ? fixture.optString("demo_score_home", "") : goals == null ? "" : goals.optString("home", "")));
        sides.addView(side(awayName, demo ? fixture.optString("demo_score_away", "") : goals == null ? "" : goals.optString("away", "")));
        TextView note = new TextView(this); note.setText(subtitle);
        note.setTextSize(12); note.setTextColor(MUTED); note.setPadding(0, dp(3), 0, 0);
        note.setMaxLines(1); note.setEllipsize(TextUtils.TruncateAt.END);
        sides.addView(note);
        card.addView(sides, new LinearLayout.LayoutParams(0, -2, 1));

        if (!demo) {
            String title = homeName + " contre " + awayName;
            ImageButton star = barAction(R.drawable.ic_star, "Suivre " + title, () -> {});
            star.setTag(fixture);
            favoriteAppearance(star, fixture.optString("id"), title);
            star.setOnClickListener(v -> toggleFavorite(fixture, star, title));
            card.addView(star, barSize(0));
        } else {
            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_chevron_right);
            chevron.setImageTintList(ColorStateList.valueOf(MUTED));
            card.addView(chevron, new LinearLayout.LayoutParams(dp(16), dp(16)));
        }

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10); root.addView(card, p);
    }

    private LinearLayout side(String name, String goals) {
        LinearLayout row = strip();
        TextView team = new TextView(this); team.setText(name);
        team.setTextSize(15.5f); team.setTextColor(INK);
        team.setMaxLines(1); team.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(team, new LinearLayout.LayoutParams(0, -2, 1));
        if (!goals.isEmpty() && !"null".equals(goals)) {
            TextView count = new TextView(this); count.setText(goals);
            count.setTextSize(15.5f); count.setTextColor(Color.WHITE);
            count.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            count.setPadding(dp(8), 0, 0, 0);
            row.addView(count);
        }
        return row;
    }

    /** The provider's stage code, said in French. An unknown code keeps its own words. */
    private String stage(String code, int matchday) {
        String named;
        switch (code) {
            case "REGULAR_SEASON": named = "Championnat"; break;
            case "GROUP_STAGE": named = "Phase de groupes"; break;
            case "PLAYOFFS": named = "Barrages"; break;
            case "PRELIMINARY_ROUND": named = "Tour préliminaire"; break;
            case "LAST_16": named = "Huitièmes"; break;
            case "QUARTER_FINALS": named = "Quarts de finale"; break;
            case "SEMI_FINALS": named = "Demi-finales"; break;
            case "THIRD_PLACE": named = "Petite finale"; break;
            case "FINAL": named = "Finale"; break;
            default: named = code.isEmpty() ? "Match" : code.replace('_', ' ');
        }
        return matchday > 0 ? named + " · J" + matchday : named;
    }

    /** The provider's status, said plainly. Null when the kickoff time is the thing to show. */
    private String state(String status) {
        switch (status) {
            case "IN_PLAY": return "EN JEU";
            case "PAUSED": return "MI-TEMPS";
            case "FINISHED": return "FINI";
            case "POSTPONED": return "REPORTÉ";
            case "SUSPENDED": return "SUSPENDU";
            case "CANCELLED": return "ANNULÉ";
            default: return null;
        }
    }

    private void openRemoteMatch(JSONObject fixture) {
        String path = "/v1/football/matches/" + fixture.optInt("id");
        try {
            String cached = store.downloaded(path);
            // A calendar entry is already enough for general notes, even before a lineup was fetched.
            match = convertMatch(cached == null ? fixture : new JSONObject(cached));
            openMatch();
            toast("Copie enregistrée · actualisation si possible");
        } catch (Exception error) { error(error); return; }
        if (!hasServer()) return;
        worker.execute(() -> {
            try {
                JSONObject converted = convertMatch(new JSONObject(get(path)));
                runOnUiThread(() -> absorb(converted));
            } catch (Exception unavailable) {
                // Keep the local match and the note being written intact.
            }
        });
    }

    private JSONObject convertMatch(JSONObject source) throws Exception {
        JSONObject home = source.getJSONObject("homeTeam"), away = source.getJSONObject("awayTeam");
        JSONArray homeLineup = home.optJSONArray("lineup"), awayLineup = away.optJSONArray("lineup");
        boolean complete = homeLineup != null && awayLineup != null
            && homeLineup.length() == 11 && awayLineup.length() == 11;
        if (!complete) { homeLineup = new JSONArray(); awayLineup = new JSONArray(); }
        JSONObject converted = new JSONObject().put("id", "fd-" + source.getInt("id"))
            .put("title", home.optString("name") + " · " + away.optString("name"))
            .put("competition", source.getJSONObject("competition").optString("name"))
            .put("stage", stage(source.optString("stage", ""), source.optInt("matchday", 0)))
            .put("date", source.optString("utcDate")).put("source", "football-data.org / " + source.optString("lineup_source", "composition indisponible"))
            .put("lineup_available", complete).put("lineup_status", source.optString("lineup_status", "unavailable"))
            .put("lineup_source", source.optString("lineup_source", "ESPN"))
            .put("kickoff_epoch_ms", kickoff(source))
            // The break and the final whistle matter as much as the start: without them the
            // clock runs on alone and a finished match shows an hour of added time.
            .put("halftime_epoch_ms", mark(source, "halftime"))
            .put("second_half_epoch_ms", mark(source, "second_half"))
            .put("end_epoch_ms", mark(source, "end"))
            .put("end_minute", source.optJSONObject("clock") == null ? 0
                : source.optJSONObject("clock").optInt("end_minute"));
        JSONArray teams = new JSONArray();
        teams.put(team("home", home, colour(home, "#7D9CD9")));
        teams.put(team("away", away, colour(away, "#F6DADB")));
        JSONArray players = new JSONArray();
        addLineup(players, homeLineup, "home", home.optString("formation"));
        addLineup(players, awayLineup, "away", away.optString("formation"));
        if (complete) {
            addBench(players, home.optJSONArray("bench"), "home");
            addBench(players, away.optJSONArray("bench"), "away");
        }
        JSONObject goals = source.optJSONObject("score") == null ? null
            : source.optJSONObject("score").optJSONObject("fullTime");
        return converted.put("teams", teams).put("players", players)
            .put("changes", changes(complete ? source.optJSONArray("timeline") : null))
            // The provider's own account of the match travels whole, beside the notes and never
            // inside them: the client shows it on a page of its own.
            .put("timeline", source.optJSONArray("timeline") == null
                ? new JSONArray() : source.optJSONArray("timeline"))
            .put("team_stats", source.optJSONObject("team_stats") == null
                ? new JSONObject() : source.optJSONObject("team_stats"))
            .put("ground", source.optJSONObject("ground") == null
                ? new JSONObject() : source.optJSONObject("ground"))
            .put("score", new JSONObject().put("home", goals == null ? "" : goals.optString("home", ""))
                .put("away", goals == null ? "" : goals.optString("away", "")));
    }

    /**
     * The minute the match actually began, when the provider says so. The scheduled hour is a
     * plan: a kickoff held up ten minutes leaves every note ten minutes out for the whole match.
     */
    private long kickoff(JSONObject source) throws Exception {
        long published = mark(source, "kickoff");
        if (published != 0) return published;
        String date = source.optString("utcDate");
        return date.isEmpty() ? System.currentTimeMillis() : java.time.OffsetDateTime.parse(date).toInstant().toEpochMilli();
    }

    /** One published mark of the run of play, in epoch milliseconds. Zero until it happens. */
    private long mark(JSONObject source, String name) {
        JSONObject clock = source.optJSONObject("clock");
        String at = clock == null ? "" : clock.optString(name);
        try {
            return at.isEmpty() ? 0 : Instant.parse(at).toEpochMilli();
        } catch (java.time.format.DateTimeParseException malformed) { return 0; }
    }

    /** The substitutions, in the run of play the provider published. */
    private JSONArray changes(JSONArray timeline) throws Exception {
        JSONArray published = new JSONArray();
        for (int i = 0; timeline != null && i < timeline.length(); i++) {
            JSONObject event = timeline.optJSONObject(i);
            JSONArray actors = event == null ? null : event.optJSONArray("players");
            // ESPN names the player coming on first, the one going off second.
            if (event == null || !"substitution".equals(event.optString("kind"))
                || actors == null || actors.length() != 2 || event.isNull("minute")) continue;
            published.put(new JSONObject().put("minute", event.optInt("minute"))
                .put("in", actors.optString(0)).put("out", actors.optString(1)));
        }
        return published;
    }

    /** The pitch parses this straight away, so nothing malformed may reach it. */
    private String colour(JSONObject team, String fallback) {
        String value = team.optString("colour");
        return value.matches("#[0-9A-Fa-f]{6}") ? value : fallback;
    }

    private JSONObject team(String key, JSONObject source, String colour) throws Exception {
        JSONObject converted = new JSONObject().put("key", key).put("name", source.optString("name"))
            .put("formation", source.optString("formation", "Composition")).put("colour", colour);
        String trigram = trigram(source), badge = badge(source);
        if (!trigram.isEmpty()) converted.put("tla", trigram);
        if (!badge.isEmpty()) converted.put("logo", badge);
        return converted;
    }

    /**
     * Three letters for a club, ESPN's first. Both providers publish one and they disagree —
     * ESPN writes TRY for Troyes where football-data writes ETR, and its RC for Strasbourg comes
     * with the space that pads a two-letter word into a three-letter field. ESPN's read better,
     * football-data's is there for a competition ESPN does not cover.
     */
    private static String trigram(JSONObject team) {
        String espn = team.optString("abbreviation").trim();
        return espn.isEmpty() ? team.optString("tla").trim() : espn;
    }

    /**
     * The badge, if it is one we can draw. football-data serves a good half of its crests as
     * SVG, which {@link BitmapFactory} does not read and which would arrive as a blank square.
     */
    private static String badge(JSONObject team) {
        String espn = team.optString("logo");
        if (!espn.isEmpty()) return espn;
        String crest = team.optString("crest");
        return crest.toLowerCase(Locale.ROOT).endsWith(".png") ? crest : "";
    }

    /** Each player stands where his published position says, not where the sheet lists him. */
    private void addLineup(JSONArray target, JSONArray lineup, String side, String formation) throws Exception {
        String[] positions = new String[lineup.length()];
        for (int i = 0; i < positions.length; i++)
            positions[i] = lineup.getJSONObject(i).optString("position");
        double[][] spots = Formation.spots(positions, formation);
        for (int i = 0; i < lineup.length(); i++) {
            JSONObject player = lineup.getJSONObject(i);
            target.put(new JSONObject().put("id", player.optString("fonote_id", "fd-" + player.optInt("id")))
                .put("name", player.optString("name")).put("number", player.optInt("shirtNumber"))
                .put("team", side).put("position", player.optString("position"))
                .put("stats", player.optJSONObject("stats") == null
                    ? new JSONObject() : player.optJSONObject("stats"))
                .put("x", spots[i][0]).put("y", spots[i][1]));
        }
    }

    /** Substitutes carry no spot: they are drawn on the one they inherit, or not at all. */
    private void addBench(JSONArray target, JSONArray bench, String side) throws Exception {
        for (int i = 0; bench != null && i < bench.length(); i++) {
            JSONObject player = bench.getJSONObject(i);
            target.put(new JSONObject().put("id", player.optString("fonote_id", "fd-" + player.optInt("id")))
                .put("name", player.optString("name")).put("number", player.optInt("shirtNumber"))
                .put("team", side).put("position", player.optString("position"))
                .put("stats", player.optJSONObject("stats") == null
                    ? new JSONObject() : player.optJSONObject("stats")));
        }
    }

    private void openMatch() {
        noteId = ""; focus = ""; entries.clear(); written = false;
        adoptClock();
        // The detail was just fetched: the first refresh is due a minute from now, not at once.
        polled = System.currentTimeMillis(); showMatch();
    }

    /**
     * The provider outranks the stored clock, but only when its marks say something new: a half
     * begun, a half ended. Between two identical readings the clock is the user's own, adjustment
     * to his broadcast included.
     */
    private void adoptClock() {
        String key = "clock_" + match.optString("id");
        long[] published = publishedClock();
        if (published != null && !marks().equals(prefs.getString(key + "_marks", ""))) {
            syncClock(published);
            return;
        }
        clockAnchor = prefs.getLong(key + "_anchor", match.optLong("kickoff_epoch_ms", System.currentTimeMillis()));
        clockBase = prefs.getLong(key + "_base", 0); clockRunning = prefs.getBoolean(key + "_running", true);
        period = prefs.getInt(key + "_period", 1);
    }

    /** The clock the provider's marks describe, or null while it has published none. */
    private long[] publishedClock() {
        return match == null ? null : MatchClock.state(match.optLong("kickoff_epoch_ms"),
            match.optLong("halftime_epoch_ms"), match.optLong("second_half_epoch_ms"),
            match.optLong("end_epoch_ms"), match.optInt("end_minute"));
    }

    /** The marks as one comparable string: two identical readings say nothing new. */
    private String marks() {
        return match.optLong("kickoff_epoch_ms") + "/" + match.optLong("halftime_epoch_ms")
            + "/" + match.optLong("second_half_epoch_ms") + "/" + match.optLong("end_epoch_ms")
            + "/" + match.optInt("end_minute");
    }

    /** Puts the provider's clock on screen and remembers that its marks have been taken. */
    private void syncClock(long[] published) {
        clockAnchor = published[0]; clockBase = published[1];
        period = (int)published[2]; clockRunning = published[3] == 1;
        prefs.edit().putString("clock_" + match.optString("id") + "_marks", marks()).apply();
        saveClock();
    }

    private boolean hasServer() {
        // No URL saved means deliberate local/demo mode. The old emulator default (10.0.2.2)
        // made a phone wait for a server that could never exist on that device.
        return prefs.contains("url") && !prefs.getString("url", "").trim().isEmpty();
    }

    private String get(String path) throws Exception {
        String data = get(prefs.getString("url", "http://10.0.2.2:8080"), path);
        if (path.startsWith("/v1/football/")) return store.download(path, data);
        return data;
    }

    private String get(String base, String path) throws Exception {
        URL endpoint = new URL(base + path);
        if (!endpoint.getProtocol().equals("http") && !endpoint.getProtocol().equals("https"))
            throw new java.io.IOException("Adresse HTTP ou HTTPS attendue");
        HttpURLConnection connection = (HttpURLConnection)endpoint.openConnection();
        try {
        connection.setConnectTimeout(5000); connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        int code = connection.getResponseCode();
        java.io.InputStream stream = code < 400 ? connection.getInputStream() : connection.getErrorStream();
        String response;
        try (java.io.InputStream input = stream) { response = input == null ? "" : readText(input); }
        if (code < 200 || code >= 300) throw new java.io.IOException("Réponse HTTP " + code);
        return response;
        } finally { connection.disconnect(); }
    }

    /** Decoded badges for the current session; downloaded images also live on disk. */
    private final Map<String, Bitmap> badges = new HashMap<>();
    private final Set<String> fetching = new HashSet<>();

    /**
     * The club's badge on its button, as soon as it has arrived. A note opens long before an
     * image lands, so the button is drawn with its three letters and the badge joins them; the
     * next time it is drawn the badge is already here and the button is whole at once. A failure
     * is silent — three letters name the club perfectly well without it.
     */
    private void wearCrest(Button button, String url) {
        if (url.isEmpty()) return;
        Bitmap known = badges.get(url);
        if (known != null) { wear(button, known); return; }
        if (!fetching.add(url)) return;
        worker.execute(() -> {
            Bitmap loaded = crest(url);
            runOnUiThread(() -> {
                fetching.remove(url);
                if (loaded == null) return;
                // A session opens a handful of matches, not a thousand; the cap is there so a
                // very long one cannot grow this without bound.
                if (badges.size() >= 24) badges.clear();
                badges.put(url, loaded);
                wear(button, loaded);
            });
        });
    }

    private void wear(Button button, Bitmap badge) {
        Drawable drawable = new BitmapDrawable(getResources(), badge);
        drawable.setBounds(0, 0, dp(18), dp(18));
        button.setCompoundDrawablesRelative(drawable, null, null, null);
        button.setCompoundDrawablePadding(dp(5));
    }

    /** A badge, decoded no larger than the button draws it: the provider serves 500 by 500. */
    private Bitmap crest(String url) {
        try {
            if (url.startsWith("asset://")) {
                String path = url.substring("asset://".length());
                try (java.io.InputStream input = getAssets().open(path)) {
                    return BitmapFactory.decodeStream(input);
                }
            }
            java.io.File folder = new java.io.File(getFilesDir(), "badges");
            if (!folder.isDirectory() && !folder.mkdirs()) return null;
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte value : digest) name.append(String.format(Locale.ROOT, "%02x", value & 255));
            java.io.File saved = new java.io.File(folder, name + ".png");
            if (saved.isFile()) {
                Bitmap cached = BitmapFactory.decodeFile(saved.getAbsolutePath());
                if (cached != null) return cached;
            }
            URL endpoint = new URL(url);
            if (!endpoint.getProtocol().equals("http") && !endpoint.getProtocol().equals("https"))
                return null;
            HttpURLConnection connection = (HttpURLConnection)endpoint.openConnection();
            try {
                connection.setConnectTimeout(5000); connection.setReadTimeout(15000);
                if (connection.getResponseCode() != 200) return null;
                byte[] raw;
                try (java.io.InputStream input = connection.getInputStream()) {
                    raw = readBytes(input, 1 << 20);
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
                options.inSampleSize = Math.max(1, options.outHeight / Math.max(1, dp(18)));
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
                if (bitmap != null) {
                    android.util.AtomicFile file = new android.util.AtomicFile(saved);
                    java.io.FileOutputStream out = null;
                    try {
                        out = file.startWrite();
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new java.io.IOException("Image invalide");
                        file.finishWrite(out);
                    } catch (java.io.IOException failure) { if (out != null) file.failWrite(out); }
                }
                return bitmap;
            } finally { connection.disconnect(); }
        } catch (Exception error) {
            return null;
        }
    }

    /** Read up to a limit rather than whatever arrives: an image is not a stream we control. */
    private static byte[] readBytes(java.io.InputStream input, int limit) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while (bytes.size() < limit && (count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }

    private void remoteError(Exception error) {
        label(error.getMessage() == null ? "Impossible de charger les matchs." : error.getMessage()).setTextColor(Color.rgb(240,186,120));
    }

    private void showMatch() {
        screen = "match";
        // The match and what the provider says about it are two cards side by side: the notes
        // are the work, the facts are a glance away, and neither is a detour through a menu.
        int showing = pager == null ? 0 : pager.page();
        pager = new Pager(this); factsPage = null; statsPage = null;
        // Fills the screen when it fits, scrolls when it does not: the composer stays reachable.
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        root = frame(); scroll.addView(root); pager.addPage(scroll); setContentView(pager);
        // A composition that is there says so in one word at the foot of the screen; only its
        // absence needs a sentence, and an empty pitch has all the room to carry one.
        if (match.has("lineup_available") && !match.optBoolean("lineup_available")) {
            String info = "provider_error".equals(match.optString("lineup_status"))
                ? "Source des compositions injoignable. Notes générales disponibles avec + ; réessayez plus tard."
                : "Composition indisponible auprès des sources. Vous pouvez prendre des notes générales avec +.";
            TextView notice = label(info); notice.setTextSize(12); notice.setTextColor(MUTED);
        }
        getWindow().setStatusBarColor(BACKGROUND); getWindow().setNavigationBarColor(BACKGROUND);
        LinearLayout header = strip(); root.addView(header);
        TextView fixture = new TextView(this); fixture.setText(
            (teamName("home") + "  /  " + teamName("away")).toUpperCase(java.util.Locale.FRANCE)
            + "\n" + match.optString("stage") + " · " + (period == 1 ? "1re" : "2e") + " mi-temps");
        fixture.setTextSize(13); fixture.setTextColor(Color.rgb(194, 208, 198)); fixture.setLineSpacing(dp(5),1);
        header.addView(fixture, new LinearLayout.LayoutParams(0, -2, 1));
        clockLabel = new TextView(this); clockLabel.setTextSize(20); clockLabel.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        clockLabel.setTextColor(Color.rgb(209,242,162)); clockLabel.setGravity(Gravity.CENTER);
        clockLabel.setPadding(dp(12),0,dp(12),0); clockLabel.setBackground(rounded(Color.rgb(32,49,41),12));
        clockLabel.setContentDescription("Chronomètre du match, toucher pour ajuster ou changer de période");
        clockLabel.setOnClickListener(v -> clock()); header.addView(clockLabel,new LinearLayout.LayoutParams(-2,dp(48)));
        pitch = new PitchView(this, match, glassMarkers(), minute, this::tapPlayer, this::pullPlayer);
        pitch.setMinimumHeight(dp(300));
        LinearLayout.LayoutParams pitchSize = new LinearLayout.LayoutParams(-1, 0, 1);
        pitchSize.topMargin = dp(8); pitchSize.bottomMargin = dp(8);
        root.addView(pitch, pitchSize);
        composer = new LinearLayout(this); composer.setOrientation(LinearLayout.VERTICAL);
        composer.setBackground(rounded(SURFACE, 16)); composer.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(composer, new LinearLayout.LayoutParams(-1, dp(COMPOSER)));
        String source = match.optBoolean("lineup_available")
            ? "  ·  " + match.optString("lineup_source", "ESPN") + ", placement schématique" : "";
        LinearLayout footer = strip(); root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setText(formations() + source + "  ·  " + match.optString("competition"));
        status.setTextSize(12); status.setTextColor(Color.rgb(177,198,184));
        status.setPadding(0, dp(8), 0, dp(8));
        status.setMaxLines(1); status.setEllipsize(TextUtils.TruncateAt.END);
        footer.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        // A card nobody knows about is a card nobody opens; this says so without costing a row.
        if (told() || counted()) {
            TextView hint = new TextView(this);
            hint.setText(told() ? "Faits  ›" : "Statistiques  ›");
            hint.setTextSize(12); hint.setTextColor(ACCENT);
            hint.setPadding(dp(10), dp(8), 0, dp(8));
            hint.setContentDescription((told() ? "Faits du match" : "Statistiques")
                + ", ou balayer vers la gauche");
            hint.setOnClickListener(v -> pager.show(1, true));
            footer.addView(hint, new LinearLayout.LayoutParams(-2, -2));
        }
        LinearLayout navigation = strip(); root.addView(navigation);
        String[] titles = {"↶", "≡ Notes", "☆ Bilan", "•••"};
        String[] described = {"Annuler la dernière action", "Mes observations", "Bilan par joueur", "Autres actions"};
        Runnable[] clicks = {this::undo, this::history, this::standings, () -> new AlertDialog.Builder(this).setTitle("Mon match")
            .setItems(new String[]{"Accueil", "Synchroniser", "Configurer le serveur", "Exporter mes observations"}, (d,n) -> {
                if(n==0) showHome(); else if(n==1) sync(); else if(n==2) settings(); else export();
            }).show()};
        for(int i=0;i<titles.length;i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(48),1); p.setMargins(dp(3),0,dp(3),0);
            Button tab = button(titles[i], clicks[i]);
            tab.setContentDescription(described[i]);
            navigation.addView(tab, p);
        }
        if (told()) {
            ScrollView beside = new ScrollView(this); beside.setFillViewport(true);
            factsPage = frame(); beside.addView(factsPage); pager.addPage(beside);
            renderFacts();
        }
        if (counted()) {
            ScrollView further = new ScrollView(this); further.setFillViewport(true);
            statsPage = frame(); further.addView(statsPage); pager.addPage(further);
            renderStats();
        }
        root = (LinearLayout)scroll.getChildAt(0);
        pager.show(showing, false);
        updateClock(); renderComposer();
    }

    /** Whether the provider gave any account of this match to put on the second card. */
    private boolean told() {
        JSONArray published = match.optJSONArray("timeline");
        return published != null && published.length() > 0;
    }

    /** Whether the provider counted anything worth a card of figures of its own. */
    private boolean counted() {
        JSONObject counts = match.optJSONObject("team_stats");
        JSONObject home = counts == null ? null : counts.optJSONObject("home");
        JSONObject away = counts == null ? null : counts.optJSONObject("away");
        if (home == null || away == null) return false;
        for (String[] stat : COUNTED)
            if (!home.optString(stat[0]).isEmpty() || !away.optString(stat[0]).isEmpty()) return true;
        return false;
    }

    // ——— Ce que le fournisseur raconte, à côté des notes ———

    /**
     * The provider's own account of the match, on a page of its own. Deliberately not folded into
     * the notes: the bilan promises to measure only what was observed, so a goal ESPN counted is
     * shown next to that promise, never inside it.
     */
    private void renderFacts() {
        LinearLayout previous = root;
        root = factsPage; factsPage.removeAllViews();
        TextView title = label("Faits du match");
        title.setTextSize(24); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView caveat = label("Relevé du fournisseur, à côté de vos notes. Rien ici n’entre dans "
            + "votre journal ni dans votre bilan.");
        caveat.setTextSize(12); caveat.setTextColor(MUTED);
        JSONObject score = match.optJSONObject("score");
        String home = score == null ? "" : score.optString("home"), away = score == null ? "" : score.optString("away");
        if (!home.isEmpty() || !away.isEmpty()) {
            TextView board = label(teamName("home") + "   " + (home.isEmpty() ? "–" : home)
                + " – " + (away.isEmpty() ? "–" : away) + "   " + teamName("away"));
            board.setTextSize(19); board.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        timelineSection();
        groundSection();
        crumbs("‹  Balayer vers la droite pour revenir au terrain",
            counted() ? "Statistiques  ›" : null, 2);
        root = previous;
    }

    /**
     * The figures, one more card to the right. Their own page because they are read differently
     * from the story of the match: a column of counts is scanned, a timeline is followed.
     */
    private void renderStats() {
        LinearLayout previous = root;
        root = statsPage; statsPage.removeAllViews();
        // Named for what it shows, not for who supplies it; the caveat below says where it comes from.
        TextView title = label("Statistiques");
        title.setTextSize(24); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView caveat = label("Compteurs " + match.optString("lineup_source", "du fournisseur")
            + ", à côté de vos notes. Rien ici n’entre dans votre journal ni dans votre bilan.");
        caveat.setTextSize(12); caveat.setTextColor(MUTED);
        countedSection();
        crumbs("‹  Balayer vers la droite pour revenir "
            + (told() ? "aux faits" : "au terrain"), null, 0);
        root = previous;
    }

    /** Where this card sits and where the next one is, on the line that closes a page. */
    private void crumbs(String back, String forward, int page) {
        LinearLayout row = strip();
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14); root.addView(row, p);
        TextView here = new TextView(this);
        here.setText(back); here.setTextSize(12); here.setTextColor(MUTED);
        row.addView(here, new LinearLayout.LayoutParams(0, -2, 1));
        if (forward == null) return;
        TextView next = new TextView(this);
        next.setText(forward); next.setTextSize(12); next.setTextColor(ACCENT);
        next.setPadding(dp(10), dp(6), 0, dp(6));
        next.setContentDescription(forward.replace("  ›", "") + ", ou balayer vers la gauche");
        next.setOnClickListener(v -> pager.show(page, true));
        row.addView(next, new LinearLayout.LayoutParams(-2, -2));
    }

    /** Goals, cards and changes, in the order they happened. The rest is clock keeping. */
    private void timelineSection() {
        JSONArray published = match.optJSONArray("timeline");
        Map<String,String> names = playerNames();
        boolean titled = false;
        for (int i = 0; published != null && i < published.length(); i++) {
            JSONObject event = published.optJSONObject(i);
            String mark = moment(event.optString("kind"), event.optBoolean("scoring"));
            if (mark.isEmpty() || event.isNull("minute")) continue;
            if (!titled) { section("Le fil du match", null, null); titled = true; }
            LinearLayout row = strip();
            TextView when = new TextView(this);
            when.setText(event.optInt("minute") + "’"); when.setTextSize(13); when.setTextColor(MUTED);
            row.addView(when, new LinearLayout.LayoutParams(dp(38), -2));
            TextView what = new TextView(this); what.setText(mark); what.setTextSize(13);
            row.addView(what, new LinearLayout.LayoutParams(dp(34), -2));
            TextView who = new TextView(this); who.setText(actors(event, names)); who.setTextSize(13);
            who.setTextColor(sideColour(event.optString("team")));
            row.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = dp(6); root.addView(row, p);
        }
    }

    /** Both counts on one line, the label between them, so the two sides are read at a glance. */
    private void countedSection() {
        JSONObject counts = match.optJSONObject("team_stats");
        JSONObject home = counts == null ? null : counts.optJSONObject("home");
        JSONObject away = counts == null ? null : counts.optJSONObject("away");
        if (home == null || away == null) return;
        // Which column is whose: the colours say it, and a side that is only a colour is a riddle.
        LinearLayout heading = strip();
        heading.addView(figure(teamName("home"), Gravity.START, sideColour("home")),
            new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(figure(teamName("away"), Gravity.END, sideColour("away")),
            new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams head = new LinearLayout.LayoutParams(-1, -2);
        head.topMargin = dp(14); head.bottomMargin = dp(10); root.addView(heading, head);
        for (String[] stat : COUNTED) {
            String left = home.optString(stat[0]), right = away.optString(stat[0]);
            if (left.isEmpty() && right.isEmpty()) continue;
            LinearLayout row = strip();
            row.addView(figure(left + stat[2], Gravity.END, sideColour("home")),
                new LinearLayout.LayoutParams(dp(74), -2));
            TextView label = new TextView(this); label.setText(stat[1]);
            label.setTextSize(13); label.setTextColor(MUTED); label.setGravity(Gravity.CENTER);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(figure(right + stat[2], Gravity.START, sideColour("away")),
                new LinearLayout.LayoutParams(dp(74), -2));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = dp(7); root.addView(row, p);
        }
    }

    private void groundSection() {
        JSONObject where = match.optJSONObject("ground");
        if (where == null) return;
        List<String> lines = new ArrayList<>();
        if (!where.optString("venue").isEmpty()) lines.add(where.optString("venue"));
        if (!where.optString("referee").isEmpty()) lines.add("Arbitre : " + where.optString("referee"));
        // Attendance is published as zero when it is simply not known; a zero crowd is a lie.
        if (where.optInt("attendance") > 0)
            lines.add(String.format(java.util.Locale.FRANCE, "%,d spectateurs",
                where.optInt("attendance")).replace(',', ' '));
        if (lines.isEmpty()) return;
        section("La rencontre", null, null);
        TextView text = label(String.join("\n", lines));
        text.setTextSize(13); text.setTextColor(Color.rgb(194, 208, 198));
    }

    private TextView figure(String text, int gravity, int colour) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(15); view.setTextColor(colour);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); view.setGravity(gravity);
        return view;
    }

    /** The mark a published moment deserves, or nothing for the clock keeping nobody reads. */
    private String moment(String kind, boolean scoring) {
        if (scoring) return "own-goal".equals(kind) ? "⚽" : "⚽";
        if (kind.startsWith("yellow")) return "🟨";
        if (kind.startsWith("red")) return "🟥";
        return "substitution".equals(kind) ? "⇄" : "";
    }

    /**
     * Who was involved, read from the order the provider publishes: the scorer then who assisted,
     * the player coming on then the one going off.
     */
    private String actors(JSONObject event, Map<String,String> names) {
        JSONArray players = event.optJSONArray("players");
        if (players == null || players.length() == 0) return "";
        String first = names.getOrDefault(players.optString(0), players.optString(0));
        if (players.length() < 2) return "own-goal".equals(event.optString("kind")) ? first + "  csc" : first;
        String second = names.getOrDefault(players.optString(1), players.optString(1));
        return "substitution".equals(event.optString("kind"))
            ? first + "  ←  " + second : first + "  (p. " + second + ")";
    }

    private Map<String,String> playerNames() {
        Map<String,String> names = new HashMap<>();
        JSONArray players = match.optJSONArray("players");
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            names.put(player.optString("id"), PlayerName.shorten(player.optString("name")));
        }
        return names;
    }

    private int sideColour(String side) {
        JSONObject team = sideTeam(side);
        return team == null ? Color.WHITE : Color.parseColor(team.optString("colour"));
    }

    // ——— Composition d'une note ———

    private void action(String key, String icon, String name, String small, int weight) {
        actions.put(key, new String[]{icon, name, small, String.valueOf(weight)});
    }
    private String icon(String key) { String[] a = actions.get(key); return a == null ? "•" : a[0]; }
    private String actionName(String key) { String[] a = actions.get(key); return a == null ? key : a[1]; }
    private String shortAction(String key) { String[] a = actions.get(key); return a == null ? key : a[2]; }
    private int weight(String key) { String[] a = actions.get(key); return a == null ? 0 : Integer.parseInt(a[3]); }
    private boolean good(String key) { return weight(key) > 0; }
    /** Muted background of an action, by polarity. */
    private int fill(String key) { return good(key) ? Color.rgb(30, 56, 42) : Color.rgb(58, 40, 28); }
    /** Readable foreground on that background, and the colour of the mark on the pitch. */
    private int tint(String key) { return good(key) ? Color.rgb(178, 230, 168) : Color.rgb(240, 186, 120); }
    private int solid(String key) { return good(key) ? ACCENT : Color.rgb(240, 186, 120); }
    private int onSolid(String key) { return good(key) ? ON_ACCENT : Color.rgb(48, 28, 12); }
    private int balanceTint(int sum) { return sum > 0 ? Color.rgb(178, 230, 168)
        : sum < 0 ? Color.rgb(240, 186, 120) : MUTED; }
    /**
     * A note open means the panel collects: every player touched joins it. With no note open, a
     * player opens one. There is no save button — each action rewrites the note straight away —
     * so the only thing left to say out loud is when one moment ends and the next begins.
     */
    private void tapPlayer(String id) {
        if (noteId.isEmpty()) {
            noteId = UUID.randomUUID().toString(); entries.clear();
            draft = ""; draftWritten = ""; written = false;
            updateClock(); noteMinute = minute;
        }
        if (!entries.containsKey(id)) entries.put(id, "");
        focus = id; renderComposer();
    }
    /** Long press on the pitch: the most visual way to take someone back out of the note. */
    private void pullPlayer(String id) {
        if (noteId.isEmpty() || !entries.containsKey(id)) return;
        entries.remove(id);
        if (id.equals(focus)) focus = entries.isEmpty() ? "" : last(entries.keySet());
        pitch.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (written && entries.isEmpty()) {
            try { record(operation("delete", noteId)); toast("Note supprimée"); }
            catch (Exception e) { error(e); }
            closeNote(); return;
        }
        if (written) writeNote();
        toast(shortName(id) + " retiré de la note");
        renderComposer();
    }
    private void qualify(String key) {
        if (focus.isEmpty()) { toast("Touche d’abord un joueur"); return; }
        entries.put(focus, key);
        if (!writeNote()) return;
        composer.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        String recap = shortName(focus) + " · " + actionName(key);
        status.setText(noteMinute + "′ · " + recap);
        toast("Noté · " + noteMinute + "′  " + recap);
        renderComposer();
    }
    /**
     * Rewrites the open note under its own id: the log keeps every version, the reader takes the
     * last. Each of the three things a note is made of — who was involved, what was written, what
     * was drawn — is re-emitted only when it actually changed. A schema is drawn stroke by stroke,
     * and re-sending the participants on every stroke would bury the journal under versions that
     * say the same thing.
     */
    private boolean writeNote() {
        try {
            String people = noteMinute + "·" + draftEntries();
            if (!written || !people.equals(entriesWritten)) {
                record(operation("note", noteId).put("match_id", match.getString("id"))
                    .put("minute", noteMinute).put("entries", draftEntries()));
                entriesWritten = people;
            }
            if (!draft.trim().equals(draftWritten)) {
                draftWritten = draft.trim();
                record(operation("comment", noteId).put("text", draftWritten));
            }
            if (diagram != null && !drawnJson().equals(diagramWritten)) {
                diagramWritten = drawnJson();
                record(operation("diagram", noteId).put("schema", new JSONObject(diagramWritten)));
            }
            written = true;
            return true;
        } catch (Exception e) { error(e); return false; }
    }
    /** The open schema as it would be written down; "" when the note carries none. */
    private String drawnJson() {
        try { return diagram == null ? "" : diagram.toJson().toString(); }
        catch (Exception e) { return ""; }
    }
    /** The note is already in the log, so dropping it is a deletion, not an abandon. */
    private void discardNote() {
        try { record(operation("delete", noteId)); toast("Note supprimée"); }
        catch (Exception e) { error(e); return; }
        closeNote();
    }
    private void closeNote() {
        noteId = ""; entries.clear(); focus = ""; draft = ""; draftWritten = ""; written = false;
        tacticTime = 0;
        diagram = null; entriesWritten = ""; diagramWritten = "";
        renderComposer();
    }
    /**
     * Reopen a note so the same moment can name one more player. A note that was drawn reopens
     * where it was drawn: the board is the note, and the panel could not show it.
     */
    private void amend(JSONObject note) {
        noteId = note.optString("note_id"); noteMinute = note.optInt("minute");
        loadEntries(entriesOf(note));
        draft = note.optString("comment"); draftWritten = draft; written = true;
        entriesWritten = noteMinute + "·" + draftEntries();
        JSONObject drawn = note.optJSONObject("schema");
        diagram = drawn == null ? null : Diagram.from(drawn);
        diagramWritten = drawnJson();
        focus = "";
        if (diagram != null) showTactic(); else renderComposer();
    }
    private void removeNote(JSONObject note) {
        try {
            record(operation("delete", note.getString("note_id")));
            toast("Note supprimée"); renderComposer();
        } catch (Exception e) { error(e); }
    }
    /** A note with nobody in it is its text, so it is written straight from the text. */
    private void generalNote() {
        updateClock();
        EditText input = new EditText(this); input.setHint("Ce que je veux noter");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        new AlertDialog.Builder(this).setTitle("Note sans joueur · " + minute + "′").setView(input)
            .setNegativeButton("Annuler", null).setPositiveButton("Noter", (d,w) -> {
                if (input.getText().toString().trim().isEmpty()) { toast("Note vide, rien n’a été écrit"); return; }
                noteId = UUID.randomUUID().toString(); entries.clear();
                noteMinute = minute; draft = input.getText().toString(); draftWritten = ""; written = false;
                if (writeNote()) toast("Noté · " + noteMinute + "′");
                closeNote();
            }).show();
    }
    private void renderComposer() {
        // The panel belongs to the match screen; a note closed from anywhere else has none to dress.
        if (composer == null || !"match".equals(screen)) return;
        composer.removeAllViews();
        boolean open = !noteId.isEmpty();
        if (open) renderDraft(); else renderIdle();
        Map<String,String> marks = new HashMap<>();
        Map<String,Integer> tints = new HashMap<>();
        if (open) {
            for (Map.Entry<String,String> entry : entries.entrySet()) {
                String action = entry.getValue();
                marks.put(entry.getKey(), action.isEmpty() ? "?" : icon(action));
                tints.put(entry.getKey(), action.isEmpty() ? ACCENT : solid(action));
            }
        } else {
            // The signed balance, not the mark out of ten: beside a shirt number, "10" would
            // read as a number. The mark needs the standings page to explain itself anyway.
            try {
                for (Map.Entry<String,int[]> entry : balances().entrySet()) {
                    marks.put(entry.getKey(), balanceText(entry.getValue()[0]));
                    tints.put(entry.getKey(), balanceTint(entry.getValue()[0]));
                }
            } catch (Exception e) { error(e); }
        }
        pitch.setMarks(marks, tints, focus, open);
    }
    /**
     * At rest the panel must answer one question: how do I write a note? The pitch is the
     * answer, so the instruction gets the weight and the note without a player stays a footnote
     * — giving that one the accent button sent everybody down the one path that leads nowhere.
     */
    private void renderIdle() {
        TextView prompt = new TextView(this);
        prompt.setText("↑   Touche un joueur sur le terrain");
        prompt.setTextSize(15); prompt.setTextColor(Color.WHITE);
        composer.addView(prompt);
        TextView how = new TextView(this);
        how.setText("puis son action — la note s’écrit aussitôt");
        how.setTextSize(12); how.setTextColor(MUTED); how.setPadding(0, dp(3), 0, dp(4));
        composer.addView(how);
        try {
            List<JSONObject> recent = notes();
            for (int i = 0; i < Math.min(3, recent.size()); i++) {
                JSONObject note = recent.get(i);
                LinearLayout line = strip();
                TextView text = new TextView(this);
                text.setText(note.optInt("minute") + "′   " + summary(note));
                text.setTextSize(12); text.setTextColor(i == 0 ? INK : MUTED);
                // The newest note shares its line with two controls, so let it breathe over two.
                text.setMaxLines(i == 0 ? 2 : 1); text.setEllipsize(TextUtils.TruncateAt.END);
                line.addView(text, new LinearLayout.LayoutParams(0, -1, 1));
                // The moment just written is the one still likely to need a second name, or none at all.
                if (i == 0) {
                    boolean drawn = note.optJSONObject("schema") != null;
                    line.addView(mini(drawn ? "▤ ouvrir" : "＋ joueur",
                            drawn ? "Rouvrir le schéma de cette note" : "Ajouter un joueur à cette note",
                            () -> amend(note)),
                        new LinearLayout.LayoutParams(dp(86), dp(34)));
                    line.addView(mini("🗑", "Supprimer cette note", () -> removeNote(note)),
                        new LinearLayout.LayoutParams(dp(44), dp(34)));
                }
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(i == 0 ? 46 : 24));
                p.topMargin = dp(i == 0 ? 4 : 2);
                composer.addView(line, p);
            }
        } catch (Exception e) { error(e); }
        composer.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = strip();
        bottom.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        // The two other ways of writing, offered as what they are: side paths off the fast one.
        bottom.addView(ghost("▤  Tactique", "Note tactique : placer les joueurs et tracer le jeu",
            this::openTactic), new LinearLayout.LayoutParams(dp(112), dp(40)));
        LinearLayout.LayoutParams generalSize = new LinearLayout.LayoutParams(dp(146), dp(40));
        generalSize.leftMargin = dp(6);
        bottom.addView(ghost("+  Note sans joueur", "Note sans joueur", this::generalNote), generalSize);
        composer.addView(bottom, new LinearLayout.LayoutParams(-1, dp(40)));
    }
    private void renderDraft() {
        LinearLayout line = strip();
        Button minuteButton = button(noteMinute + "′", this::editNoteMinute);
        minuteButton.setTextSize(15); minuteButton.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        minuteButton.setContentDescription("Minute de la note, toucher pour corriger");
        // A panel left open is the one risk of collecting: the match moving on says so.
        if (minute - noteMinute >= 2) {
            minuteButton.setTextColor(Color.rgb(240, 186, 120));
            minuteButton.setContentDescription("Note ouverte depuis " + (minute - noteMinute)
                + " minutes, toucher pour corriger sa minute");
        }
        LinearLayout.LayoutParams minuteSize = new LinearLayout.LayoutParams(dp(58), dp(44));
        minuteSize.rightMargin = dp(8); line.addView(minuteButton, minuteSize);
        HorizontalScrollView chips = sideways();
        LinearLayout chipRow = strip(); chips.addView(chipRow);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("↑  Touche un joueur pour l’ajouter");
            empty.setTextSize(12); empty.setTextColor(MUTED); empty.setGravity(Gravity.CENTER_VERTICAL);
            chipRow.addView(empty, new LinearLayout.LayoutParams(-2, dp(44)));
        }
        for (Map.Entry<String,String> entry : entries.entrySet()) addChip(chipRow, entry.getKey(), entry.getValue());
        line.addView(chips, new LinearLayout.LayoutParams(0, dp(44), 1));
        composer.addView(line, new LinearLayout.LayoutParams(-1, dp(44)));

        HorizontalScrollView palette = sideways();
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        palette.addView(rows);
        String current = entries.containsKey(focus) ? entries.get(focus) : "";
        int columns = Math.max(SUCCEEDED.length, FAILED.length);
        for (String[] side : new String[][]{SUCCEEDED, FAILED}) {
            LinearLayout row = strip();
            for (int i = 0; i < columns; i++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(56), dp(46));
                p.rightMargin = dp(5); p.bottomMargin = side == SUCCEEDED ? dp(4) : 0;
                if (i >= side.length) { row.addView(new View(this), p); continue; }
                Button cell = cell(side[i], side[i].equals(current));
                // Nothing to qualify yet: dimmed reads as "not yet", where live-but-inert reads as broken.
                cell.setAlpha(entries.containsKey(focus) ? 1f : .35f);
                row.addView(cell, p);
            }
            rows.addView(row);
        }
        LinearLayout.LayoutParams paletteSize = new LinearLayout.LayoutParams(-1, dp(96));
        paletteSize.topMargin = dp(6); composer.addView(palette, paletteSize);

        LinearLayout footer = strip();
        Button note = button("💬", this::editDraft);
        note.setTextSize(15);
        if (!draft.isEmpty()) {
            GradientDrawable carries = rounded(CHIP, 12); carries.setStroke(dp(1), ACCENT);
            note.setBackground(carries); note.setTextColor(ACCENT);
        }
        note.setContentDescription(draft.isEmpty() ? "Ajouter un commentaire"
            : "Commentaire écrit, toucher pour le modifier");
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(dp(52), dp(46));
        small.rightMargin = dp(6);
        footer.addView(note, small);
        // Written already, so leaving and discarding are two different things and both must be here.
        if (written) {
            Button drop = button("🗑  Supprimer", this::discardNote);
            drop.setTextSize(13); drop.setContentDescription("Supprimer cette note");
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(126), dp(46));
            p.rightMargin = dp(6); footer.addView(drop, p);
        }
        Button done = accent(button(written ? "Terminé" : "Abandonner", this::closeNote));
        done.setTextSize(15);
        done.setContentDescription(written ? "Terminer cette note et revenir à mes notes"
            : "Abandonner cette note et revenir à mes notes");
        footer.addView(done, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams footerSize = new LinearLayout.LayoutParams(-1, dp(46));
        footerSize.topMargin = dp(6); composer.addView(footer, footerSize);
    }
    /** An outlined control: present, reachable, and visibly not the main road. */
    private Button ghost(String text, String described, Runnable action) {
        Button button = button(text, action);
        button.setTextSize(12); button.setTextColor(MUTED); button.setMinHeight(0);
        button.setPadding(dp(6), 0, dp(6), 0); button.setContentDescription(described);
        GradientDrawable outline = rounded(SURFACE, 10);
        outline.setStroke(dp(1), Color.argb(60, 255, 255, 255));
        button.setBackground(outline);
        return button;
    }
    /** Polarity is the colour; being chosen is the fill. The two never say the same thing. */
    private Button cell(String key, boolean chosen) {
        Button cell = button("", () -> qualify(key));
        SpannableString text = new SpannableString(icon(key) + "\n" + shortAction(key));
        text.setSpan(new RelativeSizeSpan(1.6f), 0, icon(key).length(), 0);
        cell.setText(text); cell.setTextSize(10); cell.setPadding(0, dp(2), 0, dp(2));
        cell.setMinHeight(0); cell.setContentDescription(actionName(key));
        cell.setOnLongClickListener(v -> { toast(actionName(key)); return true; });
        cell.setBackground(rounded(chosen ? solid(key) : fill(key), 12));
        cell.setTextColor(chosen ? onSolid(key) : tint(key));
        return cell;
    }
    private void addChip(LinearLayout row, String id, String action) {
        boolean waiting = action.isEmpty(), aimed = id.equals(focus);
        LinearLayout chip = new LinearLayout(this); chip.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable shape = rounded(waiting ? CHIP : fill(action), 12);
        shape.setStroke(dp(aimed ? 2 : 1), waiting ? ACCENT
            : aimed ? solid(action) : Color.argb(60, 255, 255, 255));
        chip.setBackground(shape);
        TextView name = new TextView(this);
        name.setText((waiting ? "?" : icon(action)) + "  " + shortName(id));
        name.setTextSize(13); name.setTextColor(waiting ? ACCENT : tint(action));
        name.setGravity(Gravity.CENTER_VERTICAL); name.setPadding(dp(10), 0, dp(4), 0);
        name.setContentDescription(shortName(id) + (waiting ? ", action à choisir" : ", " + actionName(action)));
        name.setOnClickListener(v -> { focus = id; renderComposer(); });
        chip.addView(name, new LinearLayout.LayoutParams(-2, -1));
        TextView remove = new TextView(this);
        remove.setText("×"); remove.setTextSize(18); remove.setTextColor(MUTED);
        remove.setGravity(Gravity.CENTER); remove.setContentDescription("Retirer " + shortName(id));
        remove.setOnClickListener(v -> {
            entries.remove(id);
            if (id.equals(focus)) focus = entries.isEmpty() ? "" : last(entries.keySet());
            renderComposer();
        });
        chip.addView(remove, new LinearLayout.LayoutParams(dp(32), -1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(44));
        p.rightMargin = dp(6); row.addView(chip, p);
    }
    private static String last(Set<String> keys) {
        String result = ""; for (String key : keys) result = key; return result;
    }
    private void editNoteMinute() {
        NumberPicker picker = new NumberPicker(this); picker.setMinValue(0); picker.setMaxValue(150);
        picker.setValue(noteMinute); picker.setWrapSelectorWheel(false);
        new AlertDialog.Builder(this).setTitle("Minute de cette note").setView(picker)
            .setPositiveButton("Appliquer", (d,w) -> {
                picker.clearFocus(); noteMinute = picker.getValue();
                // A minute corrected on a note already written has to reach the log to mean anything.
                if (written) writeNote();
                if ("tactic".equals(screen)) renderTactic(); else renderComposer();
            }).setNegativeButton("Annuler", null).show();
    }
    private void editDraft() {
        EditText input = new EditText(this); input.setHint("Commentaire facultatif");
        input.setText(draft);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        new AlertDialog.Builder(this).setTitle("Commentaire").setView(input)
            .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", (d,w) -> {
                draft = input.getText().toString();
                // A tactical note may carry nothing else, so its comment is written straight away.
                if (written || "tactic".equals(screen)) writeNote();
                if ("tactic".equals(screen)) renderTactic(); else renderComposer();
            }).show();
    }
    // ——— Note tactique ———

    /**
     * The second way of writing a note. The fast one answers "who did what, and when"; this one
     * answers "where, and to whom" — a pass is worth drawing when what matters is the line it
     * took and the players it left behind, and no palette of eighteen symbols can say that.
     *
     * <p>It is the same note underneath: same id, same minute, same comment, same recap, and the
     * actions given here count in the bilan exactly like the ones tapped on the pitch. Only the
     * surface differs, and it takes the whole screen because a board that shares it with a panel
     * is a board nobody can draw on.
     */
    private void openTactic() {
        if (noteId.isEmpty()) {
            updateClock();
            noteId = UUID.randomUUID().toString(); entries.clear();
            draft = ""; draftWritten = ""; entriesWritten = ""; written = false;
            noteMinute = minute;
        }
        if (diagram == null) diagram = new Diagram();
        showTactic();
    }

    private void showTactic() {
        stopTactic();
        screen = "tactic";
        getWindow().setStatusBarColor(BACKGROUND); getWindow().setNavigationBarColor(BACKGROUND);
        LinearLayout page = frame(); page.setPadding(dp(12), dp(10), dp(12), dp(12));
        setContentView(page); root = page;
        LinearLayout bar = strip(); page.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        bar.addView(barAction(R.drawable.ic_arrow_back, "Revenir au terrain", this::leaveTactic), barSize(0));
        TextView heading = new TextView(this);
        heading.setText("Note tactique"); heading.setTextSize(21); heading.setTextColor(Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(10), dp(8), 0, dp(8));
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        tacticUndo = button("↶", this::undoTactic);
        tacticUndo.setTextSize(17);
        tacticUndo.setContentDescription("Annuler le dernier geste sur le tableau");
        bar.addView(tacticUndo, new LinearLayout.LayoutParams(dp(48), dp(44)));
        tacticMinute = button(noteMinute + "′", this::editNoteMinute);
        tacticMinute.setTextSize(15); tacticMinute.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tacticMinute.setContentDescription("Minute de la note, toucher pour corriger");
        LinearLayout.LayoutParams minuteSize = new LinearLayout.LayoutParams(dp(58), dp(44));
        minuteSize.leftMargin = dp(6);
        bar.addView(tacticMinute, minuteSize);
        board = new BoardView(this, match, glassMarkers(), true, false);
        board.setDiagram(diagram);
        board.setTime(tacticTime);
        board.setTravel(prefs.getInt("travel", BoardView.AUTO));
        // Selecting is only ever a change of what the panel offers; the note itself is untouched.
        board.watch(index -> renderTactic(), this::boardChanged);
        LinearLayout.LayoutParams boardSize = new LinearLayout.LayoutParams(-1, 0, 1);
        boardSize.topMargin = dp(8); boardSize.bottomMargin = dp(8);
        page.addView(board, boardSize);
        tacticPanel = new LinearLayout(this); tacticPanel.setOrientation(LinearLayout.VERTICAL);
        page.addView(tacticPanel, new LinearLayout.LayoutParams(-1, -2));
        // A note opens on its own history: the board as it arrives is the floor ↶ stops at.
        undoable.clear(); drawnBefore = drawnJson();
        renderTactic();
    }

    /**
     * What the five tools mean, said in full for the reader and for anyone listening to it. Each
     * carries a vector of our own rather than a font glyph: an arrow and an emoji are drawn by
     * whichever fonts the system has, at whichever size and — for the ball — in whichever colours,
     * so five cells side by side never quite lined up. Drawn here, they share a graisse, a size
     * and a centre.
     */
    private static final class Tool {
        /** A vector of ours, or 0 when the mark is a glyph the system draws better than we would. */
        final int icon, mode;
        /** The stroke a drawing tool lays down, or "" for the ball and the eraser. */
        final String glyph, kind, name, described;
        Tool(int icon, int mode, String kind, String name, String described) {
            this(icon, "", mode, kind, name, described);
        }
        Tool(String glyph, int mode, String kind, String name, String described) {
            this(0, glyph, mode, kind, name, described);
        }
        private Tool(int icon, String glyph, int mode, String kind, String name, String described) {
            this.icon = icon; this.glyph = glyph; this.mode = mode;
            this.kind = kind; this.name = name; this.described = described;
        }
    }
    private static final Tool[] TOOLS = {
        new Tool(R.drawable.ic_tool_pass, BoardView.DRAW, Diagram.PASS, "Passe",
            "Tracer une passe : trait plein, ballon au départ"),
        new Tool(R.drawable.ic_tool_run, BoardView.DRAW, Diagram.RUN, "Course",
            "Tracer le déplacement d’un joueur : trait pointillé sans ballon, ondulé s’il l’a "
            + "dans les pieds"),
        new Tool(R.drawable.ic_tool_shot, BoardView.DRAW, Diagram.SHOT, "Tir",
            "Tracer un tir : trait double"),
        new Tool("⚽", BoardView.BALL, "", "Ballon",
            "Placer le ballon ou le donner à un joueur à cet instant"),
        new Tool(R.drawable.ic_eraser, BoardView.ERASE, "", "Gomme",
            "Effacer un joueur ou un tracé d’un toucher")};

    private void renderTactic() {
        stopTactic();
        if (tacticUndo != null) {
            tacticUndo.setEnabled(!undoable.isEmpty());
            tacticUndo.setAlpha(undoable.isEmpty() ? .35f : 1f);
        }
        tacticPanel.removeAllViews();
        tacticTimeline();
        tacticMinute.setText(noteMinute + "′");
        HorizontalScrollView tools = sideways();
        LinearLayout toolRow = strip(); tools.addView(toolRow);
        for (Tool entry : TOOLS) {
            boolean chosen = board.tool() == entry.mode
                && (entry.kind.isEmpty() || entry.kind.equals(board.stroke()));
            Runnable pick = () -> {
                if (chosen) board.setTool(BoardView.MOVE);
                else if (!entry.kind.isEmpty()) board.setStroke(entry.kind);
                else board.setTool(entry.mode);
                renderTactic();
            };
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(62), dp(48));
            p.rightMargin = dp(5);
            toolRow.addView(tool(entry, chosen, pick), p);
        }
        tacticPanel.addView(tools, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout second = strip();
        boolean full = Diagram.FULL.equals(diagram.board);
        second.addView(wide(full ? "Vider le terrain" : "Remplir le terrain",
            full ? "Retirer tous les joueurs du terrain"
                 : "Placer les 22 joueurs à leur poste, à déplacer ensuite", this::flipBoard));
        for (String side : new String[]{"home", "away"}) {
            // Three letters and a badge, where the club's name never fitted: a quarter of this
            // row is some sixty dp, which « Paris Saint-Germain » leaves as « Paris Sain… ».
            // No « + » before them: the crest and the club's colour say whose button this is,
            // and what it does is said in full to whoever asks the screen.
            JSONObject team = sideTeam(side);
            String trigram = team == null ? "" : team.optString("tla");
            Button add = wide(trigram.isEmpty() ? teamName(side) : trigram,
                "Ajouter un joueur ou un pion — " + teamName(side), () -> addToken(side));
            add.setMaxLines(1); add.setEllipsize(TextUtils.TruncateAt.END);
            add.setTextColor(sideColour(side));
            wearCrest(add, team == null ? "" : team.optString("logo"));
            second.addView(add);
        }
        second.addView(wide("◆ Positions", "Voir, ajouter ou supprimer les positions clés de la piste sélectionnée", this::tacticKeys));
        LinearLayout.LayoutParams secondSize = new LinearLayout.LayoutParams(-1, dp(42));
        secondSize.topMargin = dp(6); tacticPanel.addView(second, secondSize);

        // One row, always: whoever is held, or what the tool in hand is for. Its height never
        // varies, so choosing a tool or a player never moves the board under the finger.
        List<Integer> held = board.chosen();
        if (held.size() > 1) tacticGroup(held);
        else if (held.size() == 1) tacticSelection(diagram.tokens.get(held.get(0)), held.get(0));
        else tacticHint();

        LinearLayout footer = strip();
        Button comment = button("💬", this::editDraft);
        comment.setTextSize(15);
        if (!draft.isEmpty()) {
            GradientDrawable carries = rounded(CHIP, 12); carries.setStroke(dp(1), ACCENT);
            comment.setBackground(carries); comment.setTextColor(ACCENT);
        }
        comment.setContentDescription(draft.isEmpty() ? "Ajouter un commentaire"
            : "Commentaire écrit, toucher pour le modifier");
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(dp(52), dp(46));
        small.rightMargin = dp(6); footer.addView(comment, small);
        if (written) {
            Button drop = button("🗑  Supprimer", this::discardTactic);
            drop.setTextSize(13); drop.setContentDescription("Supprimer cette note tactique");
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(126), dp(46));
            p.rightMargin = dp(6); footer.addView(drop, p);
        }
        Button done = accent(button(written ? "Terminé" : "Abandonner", this::leaveTactic));
        done.setTextSize(15);
        done.setContentDescription(written ? "Terminer cette note et revenir au terrain"
            : "Abandonner cette note et revenir au terrain");
        footer.addView(done, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams footerSize = new LinearLayout.LayoutParams(-1, dp(46));
        footerSize.topMargin = dp(6); tacticPanel.addView(footer, footerSize);
    }

    private String seconds(int time) { return String.format(Locale.FRANCE, "%.1f s", time / 10.0); }

    private void tacticTimeline() {
        LinearLayout row = strip();
        Button play = button("▶", () -> {});
        play.setContentDescription("Lire ou mettre en pause la séquence");
        row.addView(play, new LinearLayout.LayoutParams(dp(48), dp(40)));
        // Le temps se lit à côté du bouton, pas contre lui, et dans la lettre des boutons : un
        // TextView nu porte la romaine du système en 14, quand un Button porte la demi-grasse en
        // 13 — deux polices dans la même rangée pour un texte qui, lui aussi, répond au doigt.
        TextView label = new TextView(this); label.setTextColor(INK); label.setText(seconds(board.time()));
        label.setTextSize(13); label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        label.setPadding(dp(6), 0, dp(6), 0);
        label.setBackground(tappable(rounded(Color.TRANSPARENT, 10)));
        label.setContentDescription("Temps actuel, toucher pour saisir un instant précis");
        label.setOnClickListener(v -> tacticTimeInput("Aller à", board.time(), at -> {
            board.setTime(at); renderTactic();
        }));
        LinearLayout.LayoutParams clockSize = new LinearLayout.LayoutParams(dp(66), dp(40));
        clockSize.leftMargin = dp(6);
        row.addView(label, clockSize);
        SeekBar seek = new SeekBar(this) {
            private final android.graphics.Paint marks = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private Track heldTrack;
            private Track.Key heldKey;
            private float touchX, touchY;
            private boolean markerGesture, moved, deleted;
            private Runnable deleteKey;

            private Track selectedTrack() {
                int selected = board.selected();
                return board.tool() == BoardView.BALL ? diagram.ball
                    : selected >= 0 ? diagram.tokens.get(selected).track : null;
            }
            /**
             * The bar is drawn rather than dressed: a rectangle the width of the row, filled up
             * to the playhead, with the sequence's key positions as rectangles standing in it
             * and the playhead as one taller rectangle across it. The platform's own groove and
             * round thumb are set aside — a slider from a settings screen, and one this row
             * could never line its buttons up with, its track sitting where the drawable's
             * padding put it rather than where the eye wants it.
             */
            private final int GROOVE = CHIP, PLAYED = Color.rgb(63, 89, 80);
            private float middle() { return getHeight() / 2f; }
            private float span() { return dp(6); }
            private float trackLeft() { return getPaddingLeft(); }
            private float trackRight() { return getWidth() - getPaddingRight(); }
            /** Where an instant stands on the bar, never so near the end that its mark is cut. */
            private float mark(int time) {
                float left = trackLeft(), right = trackRight();
                float at = left + (right - left) * time / (float)getMax();
                return Math.max(left + dp(3), Math.min(right - dp(3), at));
            }
            private float keyX(Track.Key key) { return mark(key.time); }
            /** The instant a finger is pointing at, for a drag the bar took over from a marker. */
            private int timeAt(float x) {
                float width = trackRight() - trackLeft();
                if (width <= 0) return 0;
                return Math.max(0, Math.min(getMax(),
                    Math.round((x - trackLeft()) / width * getMax())));
            }
            private void cancelDelete() {
                if (deleteKey != null) removeCallbacks(deleteKey);
                deleteKey = null;
            }
            @Override protected void onDetachedFromWindow() {
                cancelDelete(); super.onDetachedFromWindow();
            }
            @Override public boolean performClick() {
                super.performClick(); return true;
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    cancelDelete(); heldTrack = selectedTrack(); heldKey = null;
                    // Tighter than when the markers stood in a strip of their own: they share
                    // the bar with the scrub now, and everything not on one of them is a seek.
                    float nearest = dp(9);
                    if (heldTrack != null && Math.abs(event.getY() - middle()) <= dp(14)) {
                        for (Track.Key key : heldTrack.keys) {
                            float distance = Math.abs(event.getX() - keyX(key));
                            if (distance < nearest) { nearest = distance; heldKey = key; }
                        }
                    }
                    markerGesture = heldKey != null; moved = false; deleted = false;
                    if (markerGesture) {
                        stopTactic(); play.setText("▶");
                        touchX = event.getX(); touchY = event.getY();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        deleteKey = () -> {
                            deleteKey = null;
                            if (heldTrack != selectedTrack() || !heldTrack.keys.remove(heldKey)) return;
                            deleted = true;
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            toast("Position à " + seconds(heldKey.time) + " supprimée");
                            boardChanged();
                        };
                        postDelayed(deleteKey, android.view.ViewConfiguration.getLongPressTimeout());
                        return true;
                    }
                }
                if (!markerGesture) return super.onTouchEvent(event);
                if (action == MotionEvent.ACTION_MOVE) {
                    if (Math.hypot(event.getX()-touchX, event.getY()-touchY)
                            > android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        moved = true; cancelDelete();
                    }
                    // A drag that began on a marker was a scrub after all: the bar takes it back
                    // rather than swallowing the gesture until the finger is lifted.
                    if (moved && !deleted) setProgress(timeAt(event.getX()));
                } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
                    moved = true; cancelDelete();
                } else if (action == MotionEvent.ACTION_UP) {
                    cancelDelete(); markerGesture = false;
                    if (!deleted && !moved) { setProgress(heldKey.time); performClick(); }
                    if (!deleted) renderTactic();
                }
                return true;
            }
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                float middle = middle(), span = span();
                float left = trackLeft(), right = trackRight(), head = mark(getProgress());
                marks.setColor(GROOVE);
                canvas.drawRect(left, middle - span, right, middle + span, marks);
                marks.setColor(PLAYED);
                canvas.drawRect(left, middle - span, head, middle + span, marks);
                Track track = selectedTrack();
                if (track != null) {
                    marks.setColor(ACCENT);
                    for (Track.Key key : track.keys)
                        canvas.drawRect(keyX(key) - dp(2), middle - span, keyX(key) + dp(2), middle + span, marks);
                }
                // Taller than the bar and paler than a key: the playhead crosses what it passes.
                marks.setColor(INK);
                float thick = dp(5) / 2f;
                canvas.drawRect(head - thick, middle - span - dp(4), head + thick, middle + span + dp(4), marks);
            }
        };
        // Nothing of the platform's slider is kept but its arithmetic: the bar is painted in
        // onDraw, on the middle of a view as tall as the row, so it lines up with the buttons
        // on either side of it instead of floating above them.
        seek.setThumb(null); seek.setProgressDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        seek.setSplitTrack(false);
        seek.setPadding(dp(4), 0, dp(4), 0);
        seek.setMax(Math.min(Track.END, Math.max(diagram.duration() + 50, board.time())));
        seek.setProgress(board.time()); seek.setContentDescription("Temps dans la séquence. Touchez un repère pour rejoindre sa position ; appui long pour la supprimer");
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(56), 1));
        boolean automatic = board.travel() == BoardView.AUTO;
        Button duration = button("↝ " + (automatic ? "auto" : seconds(board.travel())), () -> {
            String[] values = {"Auto — d’après la longueur", "0,5 s", "1 s", "2 s", "3 s", "5 s", "10 s"};
            int[] times = {BoardView.AUTO, 5, 10, 20, 30, 50, 100};
            new AlertDialog.Builder(this).setTitle("Durée du prochain trajet")
                .setItems(values, (d, which) -> {
                    board.setTravel(times[which]);
                    prefs.edit().putInt("travel", times[which]).apply();
                    renderTactic();
                }).show();
        });
        duration.setTextSize(11);
        duration.setContentDescription("Durée du prochain trajet, " + (automatic
            ? "automatique d’après la longueur du trait" : seconds(board.travel())));
        row.addView(duration, new LinearLayout.LayoutParams(dp(84), dp(40)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar view, int value, boolean user) {
                board.setTime(value); label.setText(seconds(value));
            }
            public void onStartTrackingTouch(SeekBar view) { stopTactic(); play.setText("▶"); }
            public void onStopTrackingTouch(SeekBar view) { renderTactic(); }
        });
        play.setOnClickListener(v -> {
            if (tacticPlaying) { stopTactic(); play.setText("▶"); return; }
            if (board.time() >= diagram.duration()) seek.setProgress(0);
            tacticPlaying = true; play.setText("Ⅱ");
            long started = android.os.SystemClock.uptimeMillis();
            int from = board.time();
            tacticTick = () -> {
                if (!tacticPlaying || !board.isAttachedToWindow()) { stopTactic(); return; }
                int at = Math.min(diagram.duration(), from + (int)((android.os.SystemClock.uptimeMillis()-started)/100));
                seek.setProgress(at);
                if (at >= diagram.duration()) { stopTactic(); play.setText("▶"); }
                else tacticClock.postDelayed(tacticTick, 40);
            };
            tacticClock.post(tacticTick);
        });
        // Editing during playback freezes the playhead before the gesture changes the model.
        board.setOnTouchListener((v, event) -> { stopTactic(); play.setText("▶"); return false; });
        tacticPanel.addView(row, new LinearLayout.LayoutParams(-1, dp(56)));
    }

    private void tacticTimeInput(String title, int time, java.util.function.IntConsumer accept) {
        stopTactic();
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(String.format(Locale.US, "%.1f", time / 10.0)); field.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title + " (secondes)")
            .setView(field).setNegativeButton("Annuler", null).setPositiveButton("Valider", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                double value = Double.parseDouble(field.getText().toString().replace(',', '.'));
                if (!Double.isFinite(value) || value < 0 || value > Track.END / 10.0) throw new NumberFormatException();
                accept.accept((int)Math.round(value * 10)); dialog.dismiss();
            } catch (NumberFormatException e) { field.setError("Saisissez un temps entre 0 et 120 secondes"); }
        }));
        dialog.show();
    }

    private void tacticKeys() {
        stopTactic();
        int selected = board.selected();
        boolean ball = board.tool() == BoardView.BALL;
        if (!ball && selected < 0) {
            if (!diagram.shapes.isEmpty()) {
                new AlertDialog.Builder(this).setItems(new String[]{"Effacer le dernier ancien tracé"},
                    (d, w) -> undoStroke()).show();
            } else toast("Sélectionnez un joueur ou l’outil Ballon");
            return;
        }
        Diagram.Token token = ball ? null : diagram.tokens.get(selected);
        Track track = ball ? diagram.ball : token.track;
        List<String> labels = new ArrayList<>();
        labels.add("Ajouter / fixer la position à " + seconds(board.time()));
        Track.Key current = track.at(board.time());
        if (current != null) {
            labels.add("Supprimer la position à " + seconds(board.time()));
            labels.add("Changer le temps de cette position…");
        }
        int offset = labels.size();
        for (Track.Key key : track.keys) labels.add("◆ " + seconds(key.time));
        new AlertDialog.Builder(this).setTitle(ball ? "Positions du ballon" : "Positions du joueur")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which >= offset) { board.setTime(track.keys.get(which-offset).time); renderTactic(); return; }
                if (which == 2) {
                    tacticTimeInput("Déplacer la position vers", current.time, at -> {
                        if (track.at(at) != null && track.at(at) != current) { toast("Une position existe déjà à cet instant"); return; }
                        track.keys.remove(current); current.time = at; track.put(current);
                        board.setTime(at); boardChanged();
                    }); return;
                }
                if (which == 1) track.keys.remove(current);
                else if (current == null) {
                    double[] p = ball ? diagram.ballPosition(board.time()) : diagram.position(token, board.time());
                    if (p == null) p = new double[]{.5, .5};
                    Track.Key key = new Track.Key(board.time(), p[0], p[1]);
                    if (ball && diagram.ballHeld(board.time())) {
                        for (Track.Key k : track.keys) if (k.time <= board.time()) key.owner = k.owner;
                    }
                    if (!track.put(key)) { toast("Piste pleine"); return; }
                }
                boardChanged();
            }).show();
    }

    /** What the tool in hand is for, said once, where the selection would otherwise be. */
    private void tacticHint() {
        TextView hint = new TextView(this);
        hint.setText(board.tool() == BoardView.ERASE
            ? "Touchez un joueur ou un tracé pour l’effacer"
            : board.tool() == BoardView.DRAW
            ? (board.travel() == BoardView.AUTO
                ? "Tracez le trajet : départ au curseur, durée d’après sa longueur"
                : "Tracez le trajet : départ au curseur, arrivée après la durée choisie")
            : board.tool() == BoardView.BALL ? "Touchez un joueur pour lui donner le ballon, ou le terrain"
            : "Placez les joueurs ; utilisez Course pour les faire avancer");
        hint.setTextSize(12); hint.setTextColor(MUTED); hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setMaxLines(1); hint.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(SELECTION));
        p.topMargin = dp(6); tacticPanel.addView(hint, p);
    }

    /**
     * A block of players held at once. Nothing is offered here but letting them go and taking
     * them off: an action describes one player, and giving the same one to nine at a time would
     * write nine observations nobody made.
     *
     * <p>Two lines rather than one, because a rectangle is a blunt instrument and the way to
     * correct it — a long press on the one player it caught or missed — is not a gesture anybody
     * guesses. Said here, where the group is, and only while there is a group to correct.
     */
    private void tacticGroup(List<Integer> held) {
        LinearLayout row = strip();
        TextView who = new TextView(this);
        SpannableString text = new SpannableString(held.size() + " joueurs · glissez-en un, ils suivent"
            + "\nappui long sur un joueur pour l’ôter ou l’ajouter");
        int first = String.valueOf(held.size()).length() + 9;
        text.setSpan(new ForegroundColorSpan(MUTED), first, text.length(), 0);
        who.setText(text);
        who.setTextSize(11); who.setTextColor(INK); who.setMaxLines(2);
        who.setLineSpacing(dp(2), 1); who.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
        Button loose = button("Aucun", () -> { board.select(-1); renderTactic(); });
        loose.setTextSize(12); loose.setMinHeight(0); loose.setTextColor(MUTED);
        loose.setContentDescription("Relâcher la sélection");
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(74), dp(38));
        p.rightMargin = dp(6); row.addView(loose, p);
        Button remove = button("×", () -> {
            // Descending, so removing one never renumbers those still to be removed.
            for (int i = held.size() - 1; i >= 0; i--) diagram.removeToken(held.get(i));
            board.select(-1); boardChanged();
        });
        remove.setTextSize(17); remove.setMinHeight(0); remove.setTextColor(MUTED);
        remove.setContentDescription("Retirer ces " + held.size() + " joueurs du schéma");
        row.addView(remove, new LinearLayout.LayoutParams(dp(42), dp(38)));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(SELECTION));
        size.topMargin = dp(6); tacticPanel.addView(row, size);
    }

    /**
     * The player the board is pointing at. A schema is a drawing, so nothing forces an action on
     * anyone; but the one the moment is about usually deserves one, and it belongs in the bilan
     * with all the others rather than in a second ledger of its own.
     */
    private void tacticSelection(Diagram.Token token, int index) {
        LinearLayout row = strip();
        TextView who = new TextView(this);
        who.setText(Diagram.named(token) ? shortName(token.playerId) : "Pion " + token.label);
        who.setTextSize(13); who.setTextColor(INK); who.setMaxLines(1);
        who.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
        if (Diagram.named(token)) {
            String current = entries.containsKey(token.playerId) ? entries.get(token.playerId) : "";
            Button act = button(current.isEmpty() ? "Action…" : icon(current) + "  " + shortAction(current),
                () -> chooseAction(token.playerId));
            act.setTextSize(12); act.setMinHeight(0);
            act.setContentDescription(current.isEmpty() ? "Donner une action à ce joueur"
                : actionName(current) + ", toucher pour changer");
            if (!current.isEmpty()) {
                act.setBackground(rounded(fill(current), 12)); act.setTextColor(tint(current));
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(124), dp(38));
            p.rightMargin = dp(6); row.addView(act, p);
        }
        Button remove = button("×", () -> {
            diagram.removeToken(index); board.select(-1); boardChanged();
        });
        remove.setTextSize(17); remove.setMinHeight(0); remove.setTextColor(MUTED);
        remove.setContentDescription("Retirer ce joueur du schéma");
        row.addView(remove, new LinearLayout.LayoutParams(dp(42), dp(38)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(SELECTION));
        p.topMargin = dp(6); tacticPanel.addView(row, p);
    }

    /**
     * A tool: its mark above its name, and whether it holds the next finger. The mark is a 20 dp
     * vector whose ink is centred on its own square, so the five of them sit at one height and
     * leave the same air above the word underneath — which a line of text never did, its glyph
     * hanging from a baseline with the descent of a letter nobody wrote left empty below it.
     *
     * <p>The pair is set two dp lower than the middle of the cell. Centred exactly, it looks
     * high: the name reserves room under its baseline for a jambage none of the five words has,
     * so the ink stops short of the bottom while the box does not. The eye reads the ink.
     */
    private Button tool(Tool entry, boolean chosen, Runnable go) {
        Button cell = button(entry.name, go);
        String described = entry.described
            + (chosen ? ", toucher à nouveau pour désélectionner" : "");
        cell.setTextSize(10); cell.setPadding(0, dp(6), 0, dp(2));
        cell.setMinHeight(0); cell.setContentDescription(described);
        cell.setOnLongClickListener(v -> { toast(described); return true; });
        cell.setBackground(rounded(chosen ? ACCENT : CHIP, 12));
        cell.setTextColor(chosen ? ON_ACCENT : INK);
        cell.setCompoundDrawablesRelativeWithIntrinsicBounds(null,
            mark(entry, chosen ? ON_ACCENT : INK), null, null);
        cell.setCompoundDrawablePadding(dp(3));
        return cell;
    }

    /**
     * A tool's mark, boxed in the same 20 dp square whatever it is made of. Four of the five are
     * vectors of ours, tinted like the word below them. The ball is the system's own ⚽: every
     * football drawn in one weight of line reads as a target or as a wheel, and the one everybody
     * already has is rounder than anything a pentagon in a circle will do. Boxed here rather than
     * set in the line of text, it keeps the height and the colours the system gives it while
     * sitting exactly where the other four sit.
     */
    private Drawable mark(Tool entry, int colour) {
        int side = dp(20);
        if (entry.icon != 0) {
            Drawable icon = getDrawable(entry.icon).mutate();
            icon.setTint(colour);
            return icon;
        }
        Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        pen.setTextAlign(Paint.Align.CENTER);
        // Réglé sur l'encre des vecteurs voisins, pas sur la boîte : un emoji remplit son
        // cadratin alors qu'une flèche laisse de l'air, et à taille égale il écraserait la rangée.
        pen.setTextSize(side * .78f);
        return new Drawable() {
            @Override public int getIntrinsicWidth() { return side; }
            @Override public int getIntrinsicHeight() { return side; }
            @Override public void draw(Canvas canvas) {
                Paint.FontMetrics metrics = pen.getFontMetrics();
                canvas.drawText(entry.glyph, getBounds().centerX(),
                    getBounds().centerY() - (metrics.ascent + metrics.descent) / 2, pen);
            }
            @Override public void setAlpha(int alpha) { pen.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { pen.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /** A board action, sharing the row evenly. */
    private Button wide(String text, String described, Runnable action) {
        Button button = button(text, action);
        button.setTextSize(12); button.setMinHeight(0); button.setContentDescription(described);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1);
        p.rightMargin = dp(5); button.setLayoutParams(p);
        return button;
    }

    private void chooseAction(String playerId) {
        List<String> keys = new ArrayList<>(actions.keySet());
        String[] items = new String[keys.size() + 1];
        items[0] = "Aucune action";
        for (int i = 0; i < keys.size(); i++)
            items[i + 1] = icon(keys.get(i)) + "   " + actionName(keys.get(i));
        new AlertDialog.Builder(this).setTitle(shortName(playerId)).setItems(items, (d, which) -> {
            if (which == 0) entries.remove(playerId); else entries.put(playerId, keys.get(which - 1));
            if (writeNote()) renderTactic();
        }).show();
    }

    /**
     * Adding a player puts him where he actually stands, not in the middle of the pitch: a schema
     * almost always starts from the real shape and departs from it, so the drag that follows is a
     * correction rather than a placement from nothing.
     */
    private void addToken(String side) {
        if (diagram.tokens.size() >= Diagram.TOKENS) { toast("Le schéma est plein"); return; }
        Map<String,String> standing = standingNow();
        Set<String> already = playersOnBoard();
        List<String> ids = new ArrayList<>(), items = new ArrayList<>();
        JSONArray players = match.optJSONArray("players");
        List<JSONObject> available = new ArrayList<>();
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            if (player == null || !side.equals(player.optString("team"))) continue;
            String id = player.optString("id");
            if (already.contains(id) || !standing.containsKey(id)) continue;
            available.add(player);
        }
        available.sort(Comparator.comparingInt((JSONObject player) -> {
            int number = player.optInt("number", 0);
            return number > 0 ? number : Integer.MAX_VALUE;
        }).thenComparing(player -> player.optString("name"), String.CASE_INSENSITIVE_ORDER));
        for (JSONObject player : available) {
            String id = player.optString("id");
            ids.add(id); items.add(shortName(id));
        }
        ids.add("#" + side); items.add("Pion — " + teamName(side));
        new AlertDialog.Builder(this).setTitle("Ajouter — " + teamName(side))
            .setItems(items.toArray(new String[0]), (d, which) -> {
                String id = ids.get(which);
                if (id.startsWith("#")) diagram.tokens.add(pawn(id.substring(1)));
                else {
                    double[] spot = spotOf(standing.get(id));
                    diagram.tokens.add(new Diagram.Token(id, "", "", spot[0], spot[1]));
                }
                board.select(diagram.tokens.size() - 1);
                boardChanged();
            }).show();
    }

    /** A nameless disc, numbered per side and laid out in a row so two never land on each other. */
    private Diagram.Token pawn(String side) {
        int count = 0;
        for (Diagram.Token token : diagram.tokens)
            if (!Diagram.named(token) && side.equals(token.team)) count++;
        double x = Math.min(.92, .28 + (count % 5) * .11), y = Math.min(.92, .5 + (count / 5) * .09);
        return new Diagram.Token("", side, String.valueOf(count + 1), x, y);
    }

    /** Empty pitch and full pitch, the two ways of starting, swapped at any moment. */
    private void flipBoard() {
        if (Diagram.FULL.equals(diagram.board)) {
            diagram.board = Diagram.BLANK;
            while (!diagram.tokens.isEmpty()) diagram.removeToken(diagram.tokens.size() - 1);
            toast("Terrain vidé · ↶ le rétablit");
        } else {
            diagram.board = Diagram.FULL;
            while (!diagram.tokens.isEmpty()) diagram.removeToken(diagram.tokens.size() - 1);
            for (Map.Entry<String,String> on : standingNow().entrySet()) {
                if (diagram.tokens.size() >= Diagram.TOKENS) break;
                double[] spot = spotOf(on.getValue());
                diagram.tokens.add(new Diagram.Token(on.getKey(), "", "", spot[0], spot[1]));
            }
        }
        board.select(-1);
        boardChanged();
    }

    private void undoStroke() {
        if (diagram.shapes.isEmpty()) { toast("Aucun tracé à effacer"); return; }
        diagram.shapes.remove(diagram.shapes.size() - 1);
        boardChanged();
    }

    /**
     * Every change to the board reaches the log at once, exactly as an action does in the fast
     * mode: there is no save button here either. What is on the board is the note, so a player
     * rubbed out takes the action he was given with him.
     */
    private void boardChanged() {
        remember();
        entries.keySet().retainAll(playersOnBoard());
        board.invalidate();
        if (writeNote()) renderTactic();
    }

    /**
     * One step back, taken after the change rather than before it: every change comes through
     * here, so what the board looked like a moment ago is simply what it looked like the last
     * time we passed. A change that leaves the schema identical — a selection, a replay — is not
     * a step, or ↶ would need pressing twice to undo one stroke.
     */
    private void remember() {
        String now = drawnJson();
        if (now.equals(drawnBefore)) return;
        if (undoable.size() >= UNDOABLE) undoable.remove(0);
        undoable.add(drawnBefore);
        drawnBefore = now;
    }

    /** Puts the board back as it was one change ago, and writes that as any other change. */
    private void undoTactic() {
        if (undoable.isEmpty()) { toast("Plus rien à annuler"); return; }
        String previous = undoable.remove(undoable.size() - 1);
        Diagram restored;
        try { restored = Diagram.from(new JSONObject(previous)); }
        catch (Exception failure) { error(failure); return; }
        stopTactic();
        diagram = restored;
        // Not a step of its own: what it puts back is where the next change starts from.
        drawnBefore = previous;
        board.setDiagram(diagram);
        entries.keySet().retainAll(playersOnBoard());
        board.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (writeNote()) renderTactic();
    }

    private void leaveTactic() { stopTactic(); closeNote(); showMatch(); }

    private void discardTactic() {
        stopTactic();
        try { record(operation("delete", noteId)); toast("Note supprimée"); }
        catch (Exception e) { error(e); return; }
        closeNote(); showMatch();
    }

    private Set<String> playersOnBoard() {
        Set<String> ids = new HashSet<>();
        if (diagram != null) for (Diagram.Token token : diagram.tokens)
            if (Diagram.named(token)) ids.add(token.playerId);
        return ids;
    }

    /** Who stands on the pitch at the note's minute: each holder, and the spot he holds. */
    private Map<String,String> standingNow() {
        List<String> starters = new ArrayList<>();
        JSONArray players = match.optJSONArray("players");
        for (int i = 0; players != null && i < players.length(); i++)
            if (players.optJSONObject(i).has("x")) starters.add(players.optJSONObject(i).optString("id"));
        List<Lineup.Change> changes = new ArrayList<>();
        JSONArray published = match.optJSONArray("changes");
        for (int i = 0; published != null && i < published.length(); i++) {
            JSONObject change = published.optJSONObject(i);
            changes.add(new Lineup.Change(change.optInt("minute"),
                change.optString("in"), change.optString("out")));
        }
        Map<String,String> standing = new LinkedHashMap<>();
        for (Map.Entry<String,String> held : Lineup.holders(starters, changes, noteMinute).entrySet())
            standing.put(held.getValue(), held.getKey());
        return standing;
    }

    /** Where a starting spot is, on the board's own frame — the away side already turned round. */
    private double[] spotOf(String starterId) {
        JSONObject player = starterId == null ? null : playerById(starterId);
        if (player == null || !player.has("x")) return new double[]{.5, .5};
        double x = player.optDouble("x"), y = player.optDouble("y");
        if (!"home".equals(player.optString("team"))) { x = 1 - x; y = 1 - y; }
        return new double[]{x, y};
    }

    /** A player still waiting for an action is on screen but not yet in what gets written. */
    private JSONArray draftEntries() {
        JSONArray list = new JSONArray();
        for (Map.Entry<String,String> entry : entries.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            try { list.put(new JSONObject().put("player_id", entry.getKey()).put("action", entry.getValue())); }
            catch (org.json.JSONException ignored) { }
        }
        return list;
    }
    private void loadEntries(JSONArray list) {
        entries.clear();
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.optJSONObject(i);
            if (entry != null) entries.put(entry.optString("player_id"), entry.optString("action"));
        }
    }
    // ——— Lecture des notes ———

    /** Notes now name several players; the ones written before carry a single one inline. */
    private static JSONArray entriesOf(JSONObject note) {
        JSONArray list = note.optJSONArray("entries");
        if (list != null) return list;
        JSONArray legacy = new JSONArray();
        String id = note.optString("player_id");
        if (!id.isEmpty()) {
            try { legacy.put(new JSONObject().put("player_id", id).put("action", note.optString("action"))); }
            catch (org.json.JSONException ignored) { }
        }
        return legacy;
    }
    /**
     * A moment reads faster when it always comes in the same order, whatever order it was tapped in:
     * strongest first, the good before the bad, ties settled by the palette. The log keeps the real
     * order of entry — this is a reading order, so changing it never rewrites a note.
     */
    private List<JSONObject> ordered(JSONArray list) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < list.length(); i++)
            if (list.optJSONObject(i) != null) result.add(list.optJSONObject(i));
        List<String> palette = new ArrayList<>(actions.keySet());
        result.sort((a, b) -> {
            int strength = Integer.compare(weight(b.optString("action")), weight(a.optString("action")));
            return strength != 0 ? strength : Integer.compare(
                palette.indexOf(a.optString("action")), palette.indexOf(b.optString("action")));
        });
        return result;
    }
    /**
     * What a drawing says, in one line. « Schéma » named the thing and told nobody anything about
     * it: a list of moments is read to find one again, and every drawn moment looked alike.
     *
     * <p>The action the sequence ends on, and only that one. Three good passes and a shot are
     * remembered as the shot, and whatever a schema was drawn around, it was drawn towards its
     * last move. Everything is weighed on the one clock the schema already keeps, so a run made
     * after the ball was played has the last word as readily as the ball does; a ball and a
     * player arriving at the same instant are named by the ball, which is what the eye follows.
     * Never the whole sequence: a line in a list is not a sequence, and the note itself is one
     * touch away.
     */
    private String schemaSummary(JSONObject schema) {
        Diagram drawn = Diagram.from(schema);
        int last = -1;
        String said = "";
        Track.Key previous = null;
        for (Track.Key key : drawn.ball.keys) {
            // A kind is written on the arrival, the departure being where the ball was until then.
            if (previous != null && previous.flight && key.time >= last) {
                last = key.time;
                String from = who(drawn, previous.owner), to = who(drawn, key.owner);
                said = Diagram.SHOT.equals(key.kind)
                    ? (from.isEmpty() ? "Frappe" : "Frappe de " + from)
                    : !from.isEmpty() && !to.isEmpty() ? from + " passe à " + to
                    : !from.isEmpty() ? "Passe de " + from
                    : !to.isEmpty() ? "Ballon pour " + to : "Ballon joué";
            }
            previous = key;
        }
        // A player's own trips. Whether one is a carry is read off the ball, exactly as the board
        // reads it to decide between a dashed line and a waved one.
        for (Diagram.Token token : drawn.tokens) {
            int since = 0;
            for (Track.Key key : token.track.keys) {
                if (key.time > since && key.time > last) {
                    last = key.time;
                    said = (drawn.carrying(token, since, key.time) ? "Conduite de " : "Course de ")
                        + who(token);
                }
                since = key.time;
            }
        }
        if (!said.isEmpty()) return said;
        // Older schemas carry strokes nobody owns: their shape is all they can be named by.
        if (!drawn.shapes.isEmpty()) {
            String kind = drawn.shapes.get(drawn.shapes.size() - 1).kind;
            return Diagram.SHOT.equals(kind) ? "Tir" : Diagram.RUN.equals(kind) ? "Course"
                : Diagram.CARRY.equals(kind) ? "Conduite" : "Passe";
        }
        int placed = drawn.tokens.size();
        return placed == 0 ? "Schéma"
            : "Placement · " + placed + (placed > 1 ? " joueurs" : " joueur");
    }

    /** The player a token names, or the pion it is, for a sentence rather than for a panel. */
    private String who(Diagram drawn, String tokenId) {
        for (Diagram.Token token : drawn.tokens) if (token.id.equals(tokenId)) return who(token);
        return "";
    }
    private String who(Diagram.Token token) {
        return Diagram.named(token) ? shortName(token.playerId)
            : token.label.isEmpty() ? "un pion" : "pion " + token.label;
    }

    /** One line: "⚽ 10 Mbappé · → 6 Pogba". */
    private String summary(JSONObject note) {
        List<JSONObject> list = ordered(entriesOf(note));
        String drawn = note.optJSONObject("schema") == null ? "" : "▤  ";
        if (list.isEmpty())
            return note.optString("comment").isEmpty()
                ? (drawn.isEmpty() ? "Note générale" : drawn + schemaSummary(note.optJSONObject("schema")))
                : drawn + note.optString("comment");
        StringBuilder text = new StringBuilder(drawn);
        for (JSONObject entry : list) {
            if (text.length() > 0) text.append("  ·  ");
            text.append(icon(entry.optString("action"))).append(" ").append(shortName(entry.optString("player_id")));
        }
        return text.toString();
    }
    /** Anything the user asks for lands here, and puts undo back at the end of the log. */
    private void record(JSONObject op) { store.add(op); undone = 0; }
    private JSONObject operation(String kind, String noteId) throws Exception {
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind).put("note_id", noteId);
    }
    /** What the match says about one side, or null while none is loaded. */
    private JSONObject sideTeam(String key) {
        JSONArray teams = match == null ? null : match.optJSONArray("teams");
        for (int i = 0; teams != null && i < teams.length(); i++)
            if (key.equals(teams.optJSONObject(i).optString("key"))) return teams.optJSONObject(i);
        return null;
    }
    private String teamName(String key) {
        JSONObject team = sideTeam(key);
        return team == null ? key : team.optString("name");
    }
    private String formations() {
        JSONArray teams = match.optJSONArray("teams");
        return teams.optJSONObject(0).optString("formation") + "  /  " + teams.optJSONObject(1).optString("formation");
    }
    private JSONObject playerById(String id) {
        JSONArray players = match.optJSONArray("players");
        for (int i=0; i<players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            if (id.equals(player.optString("id"))) return player;
        }
        return null;
    }
    private String playerName(String id) {
        JSONObject player = playerById(id);
        return player == null ? id : player.optInt("number") + " · " + PlayerName.shorten(player.optString("name"))
            + " (" + teamName(player.optString("team")) + ")";
    }
    /** Short enough for a chip: "10 Mbappé". */
    private String shortName(String id) {
        JSONObject player = playerById(id);
        return player == null ? id : player.optInt("number") + " " + PlayerName.shorten(player.optString("name"));
    }
    private List<JSONObject> notes() throws Exception { return notes(match.optString("id")); }

    private List<JSONObject> notes(String matchId) throws Exception {
        LinkedHashMap<String, JSONObject> notes = new LinkedHashMap<>();
        Set<String> deleted = new HashSet<>();
        Map<String,String> comments = new HashMap<>();
        Map<String,JSONObject> schemas = new HashMap<>();
        JSONArray ops = store.operations(false);
        for (int i=0; i<ops.length(); i++) {
            JSONObject op = ops.getJSONObject(i); String id = op.getString("note_id");
            switch (op.getString("kind")) {
                case "note": notes.put(id, op); break;
                case "delete": deleted.add(id); break;
                // Only an explicit restore brings a note back: a later edit never does, so a
                // deletion still wins over a modification arriving from another device.
                case "restore": deleted.remove(id); break;
                case "comment": comments.put(id, op.getString("text")); break;
                case "diagram": schemas.put(id, op.getJSONObject("schema")); break;
            }
        }
        List<JSONObject> visible = new ArrayList<>();
        for (Map.Entry<String,JSONObject> entry : notes.entrySet()) {
            if (!deleted.contains(entry.getKey())
                    && (matchId == null || matchId.equals(entry.getValue().optString("match_id")))) {
                entry.getValue().put("comment", comments.getOrDefault(entry.getKey(), ""));
                if (schemas.containsKey(entry.getKey()))
                    entry.getValue().put("schema", schemas.get(entry.getKey()));
                visible.add(entry.getValue());
            }
        }
        Collections.reverse(visible); return visible;
    }
    /** id → {solde pondéré, actions positives, actions négatives}, sur les notes visibles. */
    private Map<String,int[]> balances() throws Exception {
        Map<String,int[]> result = new LinkedHashMap<>();
        for (JSONObject note : notes()) {
            JSONArray list = entriesOf(note);
            for (int i = 0; i < list.length(); i++) {
                String action = list.optJSONObject(i).optString("action");
                int[] cell = result.computeIfAbsent(list.optJSONObject(i).optString("player_id"), k -> new int[3]);
                cell[0] += weight(action);
                if (weight(action) > 0) cell[1]++; else if (weight(action) < 0) cell[2]++;
            }
        }
        return result;
    }
    /**
     * A partial log cannot produce an absolute rating, so the mark stays anchored on average
     * and moves by half a point per weighted point — the detail beside it is what justifies it.
     */
    private static String balanceText(int sum) {
        return sum > 0 ? "+" + sum : sum < 0 ? "−" + -sum : "0";
    }
    private String scoreText(int sum) {
        double score = Math.max(1, Math.min(10, 6 + sum / 2.0));
        return score == Math.rint(score) ? String.valueOf((int)score)
            : String.format(java.util.Locale.FRANCE, "%.1f", score);
    }
    /**
     * What the provider counted for this player, or nothing at all when it counted nothing.
     *
     * <p>Set beside the mark, never inside it: the mark answers "what did I see", this answers
     * "what does the provider say happened", and a bilan that mixed the two would stop meaning
     * either. A goalkeeper and a striker end up with different lines, which is the point.
     */
    private String counted(String id) {
        JSONArray players = match.optJSONArray("players");
        JSONObject stats = null;
        boolean keeps = false;
        for (int i = 0; players != null && i < players.length(); i++)
            if (players.optJSONObject(i).optString("id").equals(id)) {
                stats = players.optJSONObject(i).optJSONObject("stats");
                // The same reading of a position the pitch uses to place him — and, for a
                // substitute the provider only calls "Substitute", what he was counted doing.
                keeps = Formation.band(players.optJSONObject(i).optString("position")) == 0;
            }
        if (stats == null) return "";
        keeps = keeps || stats.optInt("saves", 0) > 0 || stats.optInt("shotsFaced", 0) > 0;
        StringBuilder line = new StringBuilder();
        for (String[] tally : TALLY) {
            int value = stats.optInt(tally[0], 0);
            if (value <= 0 || !keeps && KEEPER.contains(tally[0])) continue;
            if (line.length() > 0) line.append("   ·   ");
            line.append(value).append(' ').append(value > 1 ? tally[2] : tally[1]);
        }
        return line.toString();
    }

    private void standings() {
        screen = "standings"; page("Bilan de mes notes", this::showMatch);

        TextView caveat = label("Calculé sur mes seules notes : ce que j’ai remarqué, pas le match complet. "
            + "Base 6, une demi-note par point. La ligne grise est le compte du fournisseur, "
            + "montré à côté et jamais compris dans la note.");
        caveat.setTextSize(12); caveat.setTextColor(MUTED);
        try {
            Map<String,int[]> balance = balances();
            Map<String,LinkedHashMap<String,Integer>> detail = new HashMap<>();
            for (JSONObject note : notes()) {
                JSONArray list = entriesOf(note);
                for (int i = 0; i < list.length(); i++) {
                    JSONObject entry = list.optJSONObject(i);
                    LinkedHashMap<String,Integer> counts = detail.computeIfAbsent(
                        entry.optString("player_id"), k -> new LinkedHashMap<>());
                    String action = entry.optString("action");
                    counts.put(action, counts.getOrDefault(action, 0) + 1);
                }
            }
            List<Map.Entry<String,int[]>> ranked = new ArrayList<>(balance.entrySet());
            ranked.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
            if (ranked.isEmpty()) { label("Aucune note pour l’instant."); return; }
            for (Map.Entry<String,int[]> row : ranked) {
                int[] cell = row.getValue();
                StringBuilder acts = new StringBuilder();
                Map<String,Integer> counts = detail.get(row.getKey());
                List<String> keys = new ArrayList<>(counts.keySet());
                keys.retainAll(actions.keySet());
                List<String> palette = new ArrayList<>(actions.keySet());
                keys.sort((a, b) -> {
                    int strength = Integer.compare(weight(b), weight(a));
                    return strength != 0 ? strength : Integer.compare(palette.indexOf(a), palette.indexOf(b));
                });
                for (String key : keys) {
                    if (acts.length() > 0) acts.append("   ");
                    acts.append(icon(key)).append(" ").append(actionName(key));
                    if (counts.get(key) > 1) acts.append(" ×").append(counts.get(key));
                }
                String head = scoreText(cell[0]) + "   " + playerName(row.getKey());
                String mine = head + "\n" + balanceText(cell[0]) + " de solde   ·   "
                    + cell[1] + " ↑   " + cell[2] + " ↓\n" + acts;
                String theirs = counted(row.getKey());
                if (!theirs.isEmpty())
                    theirs = "\n" + match.optString("lineup_source", "ESPN") + " : " + theirs;
                SpannableString text = new SpannableString(mine + theirs);
                text.setSpan(new RelativeSizeSpan(1.5f), 0, scoreText(cell[0]).length(), 0);
                // The provider's line is set back a shade: it is context, not the mark.
                if (!theirs.isEmpty())
                    text.setSpan(new ForegroundColorSpan(MUTED), mine.length(), text.length(), 0);
                card(text, balanceTint(cell[0]));
            }
            int silent = match.optJSONArray("players").length() - ranked.size();
            if (silent > 0) label(silent + (silent > 1 ? " joueurs sans note" : " joueur sans note"));
        } catch (Exception e) { error(e); }
    }
    private void card(CharSequence text, int accentColour) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(13);
        view.setTextColor(INK); view.setLineSpacing(dp(4), 1);
        GradientDrawable shape = rounded(CHIP, 12);
        shape.setStroke(dp(1), accentColour);
        view.setBackground(shape); view.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(8);
        root.addView(view, p);
    }
    /**
     * Undo cannot remove anything from an append-only log, so it appends the operation that
     * compensates the last one — a restore for a deletion, the previous version of a rewritten
     * note, the previous text of a comment. Each undo appends exactly one operation, which is
     * why walking further back skips two positions per step.
     */
    private void undo() {
        try {
            JSONArray ops = store.operations(false);
            int index = ops.length() - 1 - 2 * undone;
            if (index < 0) { toast("Plus rien à annuler"); return; }
            JSONObject target = ops.getJSONObject(index);
            String id = target.getString("note_id"), said;
            JSONObject compensation;
            switch (target.getString("kind")) {
                case "delete":
                    compensation = operation("restore", id); said = "Suppression annulée"; break;
                case "restore":
                    compensation = operation("delete", id); said = "Note supprimée de nouveau"; break;
                case "comment": {
                    String previous = previousComment(ops, index, id);
                    compensation = operation("comment", id).put("text", previous);
                    said = previous.isEmpty() ? "Commentaire retiré" : "Commentaire précédent rétabli";
                    break;
                }
                case "diagram": {
                    JSONObject previous = previousSchema(ops, index, id);
                    // Nothing before it means the schema never existed: an empty board says so,
                    // and the note it belongs to is left alone.
                    compensation = operation("diagram", id).put("schema",
                        previous == null ? new Diagram().toJson() : previous);
                    said = previous == null ? "Schéma effacé" : "Schéma précédent rétabli";
                    break;
                }
                default: {
                    JSONObject earlier = previousVersion(ops, index, id);
                    if (earlier == null) {
                        compensation = operation("delete", id); said = "Note annulée";
                    } else {
                        compensation = operation("note", id)
                            .put("match_id", earlier.getString("match_id"))
                            .put("minute", earlier.getInt("minute"))
                            .put("entries", entriesOf(earlier));
                        said = "Version précédente rétablie";
                    }
                }
            }
            store.add(compensation);
            undone++;
            toast(said);
            if (noteId.isEmpty()) renderComposer(); else closeNote();
        } catch (Exception e) { error(e); }
    }
    private JSONObject previousVersion(JSONArray ops, int before, String id) throws Exception {
        for (int i = before - 1; i >= 0; i--) {
            JSONObject op = ops.getJSONObject(i);
            if ("note".equals(op.getString("kind")) && id.equals(op.getString("note_id"))) return op;
        }
        return null;
    }
    private JSONObject previousSchema(JSONArray ops, int before, String id) throws Exception {
        for (int i = before - 1; i >= 0; i--) {
            JSONObject op = ops.getJSONObject(i);
            if ("diagram".equals(op.getString("kind")) && id.equals(op.getString("note_id")))
                return op.getJSONObject("schema");
        }
        return null;
    }
    private String previousComment(JSONArray ops, int before, String id) throws Exception {
        for (int i = before - 1; i >= 0; i--) {
            JSONObject op = ops.getJSONObject(i);
            if ("comment".equals(op.getString("kind")) && id.equals(op.getString("note_id")))
                return op.getString("text");
        }
        return "";
    }
    private void history() {
        screen = "history"; page("Mes observations", this::showMatch);
        try {
            List<JSONObject> notes = notes();
            int moments = notes.size(), acts = 0;
            for (JSONObject note : notes) acts += entriesOf(note).length();
            label(moments + (moments > 1 ? " notes · " : " note · ")
                + acts + (acts > 1 ? " actions relevées" : " action relevée")
                + " · observation non exhaustive");
            for (JSONObject note : notes) {
                List<JSONObject> list = ordered(entriesOf(note));
                JSONObject schema = note.optJSONObject("schema");
                StringBuilder text = new StringBuilder(note.optInt("minute") + "′");
                if (list.isEmpty())
                    text.append(schema == null ? "  ·  Note générale" : "  ·  " + schemaSummary(schema));
                for (JSONObject entry : list) {
                    text.append("\n").append(icon(entry.optString("action"))).append("  ")
                        .append(actionName(entry.optString("action"))).append(" — ")
                        .append(playerName(entry.optString("player_id")));
                }
                if (!note.optString("comment").isEmpty()) text.append("\n").append(note.optString("comment"));
                Runnable edit = () -> new AlertDialog.Builder(this).setTitle("Modifier la note")
                    .setItems(new String[]{"Commentaire", "Supprimer"}, (dialog, which) -> {
                        if (which == 0) comment(note);
                        else try { record(operation("delete", note.getString("note_id"))); history(); }
                        catch (Exception e) { error(e); }
                    }).show();
                if (schema == null) full(text.toString(), edit);
                else drawnNote(note, schema, text.toString(), edit);
            }
        } catch (Exception e) { error(e); }
    }
    /**
     * A drawn note is shown drawn. "→ passe — 6 Pogba" says nearly nothing about a moment whose
     * whole point was the line the ball took and who it went past, so the recap carries the board
     * itself, small, and touching it reopens the board rather than a dialog — the dialog that
     * every other note opens on a tap is on a long press here.
     */
    private void drawnNote(JSONObject note, JSONObject schema, String text, Runnable edit) {
        LinearLayout card = strip();
        card.setBackground(tappable(rounded(CHIP, 12)));
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        BoardView preview = new BoardView(this, match, glassMarkers(), false, true);
        preview.setDiagram(Diagram.from(schema));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(150), dp(190));
        size.rightMargin = dp(12);
        card.addView(preview, size);
        TextView caption = new TextView(this);
        caption.setText(text); caption.setTextSize(13); caption.setTextColor(INK);
        caption.setLineSpacing(dp(4), 1);
        card.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        card.setClickable(true); card.setFocusable(true);
        card.setContentDescription("Note tactique, " + note.optInt("minute")
            + "e minute. " + text.replace("\n", ". ")
            + ". Toucher pour rouvrir le schéma, appui long pour commenter ou supprimer.");
        card.setOnClickListener(v -> amend(note));
        card.setOnLongClickListener(v -> { edit.run(); return true; });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(8);
        root.addView(card, p);
    }
    private void comment(JSONObject note) {
        EditText input = new EditText(this); input.setHint("Commentaire facultatif");
        input.setText(note.optString("comment"));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        new AlertDialog.Builder(this).setTitle("Commentaire").setView(input)
            .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", (d,w) -> {
                try { record(operation("comment", note.getString("note_id")).put("text", input.getText().toString())); history(); }
                catch (Exception e) { error(e); }
            }).show();
    }
    private void clock() {
        long[] published = publishedClock();
        java.util.List<String> choices = new java.util.ArrayList<>(java.util.Arrays.asList(
            "Coup d’envoi : démarrer à 0′",
            "Mi-temps : pause puis reprise auto à 45′",
            "Coup d’envoi 2e mi-temps : démarrer à 45′",
            clockRunning ? "Pause" : "Reprendre le chrono",
            "Ajuster à la minute de ma diffusion"));
        // No adjustment is a dead end: where the provider has published its marks, the match's
        // own time is one tap away, whatever the chrono was set to in the meantime.
        if (published != null) choices.add("Resynchroniser sur le match (" + reading(published) + ")");
        new AlertDialog.Builder(this).setTitle("Chronomètre du match")
            .setItems(choices.toArray(new String[0]), (d,n) -> {
                if(n==5) { syncClock(published); showMatch(); return; }
                if(n==4) { adjustClock(); return; }
                if(n==3) { clockBase = clockSeconds(); clockRunning = !clockRunning; }
                else { clockBase = n==0 ? 0 : HALF*60L; period = n==0 ? 1 : 2; clockRunning = true; }
                // Half-time counts from the whistle, not from 45′, so first-half stoppage
                // time is already absorbed. The future anchor parks MatchClock at 45′
                // until the break is over, and survives the app being closed.
                clockAnchor = System.currentTimeMillis() + (n==1 ? BREAK*1000L : 0);
                saveClock(); showMatch();
            }).setNegativeButton("Fermer",null).show();
    }
    /** What resynchronising would put on the clock, said as the menu will show it. */
    private String reading(long[] published) {
        if (published[0] > System.currentTimeMillis() && published[1] == 0) return "avant le coup d’envoi";
        long seconds = MatchClock.seconds(System.currentTimeMillis(), published[0], published[1], published[3] == 1);
        return MatchClock.stamp(seconds, (int)published[2]) + "′";
    }

    private void adjustClock() {
        NumberPicker picker = new NumberPicker(this); picker.setMinValue(0); picker.setMaxValue(150);
        updateClock();
        picker.setValue(minute); picker.setWrapSelectorWheel(false);
        new AlertDialog.Builder(this).setTitle("Minute affichée sur ta diffusion").setView(picker)
            .setPositiveButton("Appliquer", (d,w) -> {
                picker.clearFocus(); clockBase=picker.getValue()*60L; clockAnchor=System.currentTimeMillis();
                saveClock(); showMatch();
            }).setNegativeButton("Annuler", null).show();
    }
    private void options() {
        screen = "options"; page("Options", this::showHome);
        section("Serveur", null, null);
        TextView help = label("Les matchs sont accessibles sans compte ni jeton personnel. "
            + "La synchronisation des notes utilise un jeton séparé, facultatif.");
        help.setTextColor(MUTED); help.setTextSize(13); help.setPadding(0, 0, 0, dp(10));
        CheckBox demo = choice("Mode démo hors ligne (2 matchs fictifs)", prefs.getBoolean("demo_mode", true));
        demo.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("demo_mode", checked).apply();
            if ("options".equals(screen)) showHome();
        });
        full("Configurer le serveur", this::settings);
        connectionTest(root, () -> prefs.getString("url", "http://10.0.2.2:8080"));
        markerChoice();
    }

    /** Which of the two shirt looks the pitch draws. Glass unless the player asked for paint. */
    private boolean glassMarkers() { return !"solid".equals(prefs.getString("markers", "glass")); }

    /** Taste, not behaviour: the two looks sit side by side and the choice is what it shows. */
    private void markerChoice() {
        section("Pastilles des joueurs", null, null);
        LinearLayout row = strip(); root.addView(row);
        row.addView(shirtChoice(true, "Verre",
            "Pastilles verre : disque sombre, couleur de l'équipe en anneau et en halo"), half());
        row.addView(shirtChoice(false, "Plein",
            "Pastilles pleines : disque peint à la couleur de l'équipe"), half());
    }

    private LinearLayout.LayoutParams half() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
        size.setMargins(dp(3), dp(4), dp(3), dp(4));
        return size;
    }

    private LinearLayout shirtChoice(boolean glass, String name, String described) {
        boolean chosen = glass == glassMarkers();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL); card.setGravity(Gravity.CENTER);
        card.setPadding(dp(10), dp(14), dp(10), dp(14));
        // The sample sits on a patch of pitch, because that is the only place it will ever be seen.
        GradientDrawable back = rounded(Color.rgb(24, 56, 46), 14);
        back.setStroke(dp(chosen ? 2 : 1), chosen ? ACCENT : Color.argb(60, 255, 255, 255));
        card.setBackground(back);
        TextView sample = new TextView(this);
        sample.setText("10"); sample.setTextSize(12.5f); sample.setGravity(Gravity.CENTER);
        sample.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sample.setBackground(PitchView.shirt(this, glass, SAMPLE_KIT, false, false));
        sample.setTextColor(glass ? SAMPLE_KIT : Color.rgb(15, 35, 33));
        sample.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(sample, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView caption = new TextView(this);
        caption.setText(name); caption.setTextSize(14); caption.setPadding(0, dp(8), 0, 0);
        caption.setTextColor(chosen ? ACCENT : INK);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(caption);
        card.setContentDescription(described + (chosen ? ", sélectionné" : ""));
        card.setFocusable(true); card.setMinimumHeight(dp(48));
        card.setOnClickListener(v -> {
            prefs.edit().putString("markers", glass ? "glass" : "solid").apply();
            options();
        });
        return card;
    }

    private void settings() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        EditText url = new EditText(this); url.setHint("https://mon-serveur");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString("url", "http://10.0.2.2:8080")); box.addView(url);
        EditText token = new EditText(this); token.setHint("Jeton de synchronisation (facultatif)");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(prefs.getString("token", "")); box.addView(token);
        connectionTest(box, () -> url.getText().toString().trim().replaceAll("/+$", ""));
        new AlertDialog.Builder(this).setTitle("Serveur personnel").setView(box)
            .setPositiveButton("Enregistrer", (d,w) -> prefs.edit()
                .putString("url", url.getText().toString().trim().replaceAll("/+$", ""))
                .putString("token", token.getText().toString().trim()).apply())
            .setNegativeButton("Annuler", null).show();
    }

    private void connectionTest(LinearLayout box, java.util.function.Supplier<String> address) {
        TextView result = new TextView(this);
        result.setTextColor(MUTED); result.setPadding(dp(8), dp(8), dp(8), dp(8));
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        Button test = button("Tester la connexion", () -> {});
        box.addView(test, new LinearLayout.LayoutParams(-1, dp(48))); box.addView(result);
        test.setOnClickListener(v -> {
            String base = address.get();
            test.setEnabled(false); result.setText("Connexion au serveur…");
            worker.execute(() -> {
                boolean reached = false;
                String message;
                try {
                    JSONObject health = new JSONObject(get(base, "/v1/health"));
                    if (!"fonote".equals(health.optString("service")))
                        throw new java.io.IOException("Ce serveur n’est pas un serveur Fonote");
                    reached = true;
                    if (!health.optBoolean("football_configured")) {
                        message = "Serveur connecté. Clé football absente : renseignez FOOTBALL_DATA_TOKEN dans le .env du serveur, puis redémarrez-le.";
                    } else {
                        runOnUiThread(() -> result.setText("Serveur connecté. Vérification des données football…"));
                        JSONObject data = new JSONObject(get(base, "/v1/football/competitions"));
                        JSONArray competitions = data.getJSONArray("competitions");
                        message = "Connexion réussie : serveur et API football accessibles ("
                            + competitions.length() + " compétitions). Retournez à l’accueil pour charger les matchs.";
                    }
                } catch (Exception error) {
                    message = reached
                        ? "Serveur connecté, mais données football indisponibles. Vérifiez la clé API, le quota et la connexion Internet du serveur."
                        : "Connexion impossible. Vérifiez que le serveur est démarré et que l’adresse est correcte. Sur l’émulateur : http://10.0.2.2:8080. Si le serveur tourne déjà, redémarrez-le avec la dernière version.";
                }
                String feedback = message;
                runOnUiThread(() -> { test.setEnabled(true); result.setText(feedback); });
            });
        });
    }
    private String request(String base, String token, JSONObject body) throws Exception {
        URL url = new URL(base + "/v1/operations");
        if (!url.getProtocol().equals("https") && !url.getProtocol().equals("http")) throw new Exception("URL HTTP(S) requise");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("Serveur : HTTP " + code);
            try (java.io.InputStream in = connection.getInputStream()) {
                return readText(in);
            }
        } finally { connection.disconnect(); }
    }
    private void sync() {
        if (syncing) return;
        String base = prefs.getString("url", ""), token = prefs.getString("token", "");
        if (base.isEmpty() || token.isEmpty()) { settings(); return; }
        syncing = true; status.setText("Synchronisation en cours…");
        worker.execute(() -> {
            String message;
            try {
                JSONArray pending = store.operations(true);
                for (int i=0; i<pending.length(); i++) store.accept(new JSONObject(request(base, token, pending.getJSONObject(i))));
                JSONArray remote = new JSONArray(request(base, token, null));
                for (int i=0; i<remote.length(); i++) store.accept(remote.getJSONObject(i));
                int remaining = store.operations(true).length();
                message = remaining == 0 ? "Synchronisé ✓" : remaining + " modification(s) à synchroniser";
            } catch (Exception e) { message = "Synchronisation impossible. Notes conservées sur cet appareil."; }
            String result = message;
            runOnUiThread(() -> { syncing = false; if (!isDestroyed()) { status.setText(result); renderComposer(); toast(result); } });
        });
    }
    private void export() {
        try {
            JSONArray result = new JSONArray(); for (JSONObject note : notes()) result.put(note);
            Intent intent = new Intent(Intent.ACTION_SEND).setType("application/json")
                .putExtra(Intent.EXTRA_TEXT, result.toString(2));
            startActivity(Intent.createChooser(intent, "Exporter mes observations"));
        } catch (Exception e) { error(e); }
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void error(Exception e) { toast("Opération impossible : " + e.getClass().getSimpleName()); }
}
