package fr.fonote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(16, 27, 32), SURFACE = Color.rgb(20, 34, 31),
        CHIP = Color.rgb(33, 48, 45), ACCENT = Color.rgb(207, 240, 160), ON_ACCENT = Color.rgb(25, 48, 28),
        INK = Color.rgb(229, 238, 231), MUTED = Color.rgb(156, 179, 164);
    private Store store;
    private SharedPreferences prefs;
    private JSONObject match;
    private LinearLayout root, composer;
    /** Which screen is shown: these pages replace the view, so back has to unwind them itself. */
    private String screen = "match";
    private PitchView pitch;
    private TextView status, clockLabel;
    private int minute;
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
    /** Fixed so that opening a note never moves the players under the finger. */
    private static final int COMPOSER = 214;

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
                match = new JSONObject(readText(input));
            }
            if (saved != null) {
                noteId = saved.getString("note_id", "");
                focus = saved.getString("focus", "");
                draft = saved.getString("draft", "");
                draftWritten = saved.getString("draft_written", "");
                written = saved.getBoolean("written", false);
                noteMinute = saved.getInt("note_minute", 0);
                loadEntries(new JSONArray(saved.getString("entries", "[]")));
            }
            // A real fixture can supply its actual kickoff. The demo starts on first opening.
            clockAnchor = prefs.getLong("clock_anchor", match.optLong("kickoff_epoch_ms", System.currentTimeMillis()));
            clockBase = prefs.getLong("clock_base", 0);
            clockRunning = prefs.getBoolean("clock_running", true);
            period = prefs.getInt("clock_period", 1);
            saveClock();
            showMatch();
        } catch (Exception e) { error(e); }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("note_id", noteId); state.putString("focus", focus);
        state.putString("draft", draft); state.putString("draft_written", draftWritten);
        state.putBoolean("written", written); state.putInt("note_minute", noteMinute);
        state.putString("entries", draftEntries().toString());
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        ticker.removeCallbacks(tick);
        worker.shutdown();
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (!"match".equals(screen)) { showMatch(); return; }
        if (!noteId.isEmpty()) { closeNote(); return; }
        super.onBackPressed();
    }
    @Override protected void onResume() { super.onResume(); ticker.removeCallbacks(tick); ticker.post(tick); }
    @Override protected void onPause() { ticker.removeCallbacks(tick); super.onPause(); }
    private long clockSeconds() { return MatchClock.seconds(System.currentTimeMillis(), clockAnchor, clockBase, clockRunning); }
    private void updateClock() {
        int previous = minute;
        minute = (int)(clockSeconds()/60);
        if (clockLabel == null) return;
        if (minute != previous && !noteId.isEmpty() && minute - noteMinute == 2) renderComposer();
        long left = (clockAnchor - System.currentTimeMillis()) / 1000;
        clockLabel.setText(clockRunning && left > 0
            ? String.format(java.util.Locale.FRANCE, "⏳  %d:%02d  ⌄", left/60, left%60)
            : (clockRunning ? "●  " : "Ⅱ  ") + MatchClock.display(clockSeconds(), period) + "  ⌄");
    }
    private void saveClock() {
        prefs.edit().putLong("clock_anchor", clockAnchor).putLong("clock_base", clockBase)
            .putBoolean("clock_running", clockRunning).putInt("clock_period", period).apply();
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
    /** Scrolling page, for lists. The match page lays itself out to the screen instead. */
    private void page(String title) {
        ScrollView scroll = new ScrollView(this);
        root = frame(); root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root); setContentView(scroll);
        TextView heading = label(title); heading.setTextSize(26);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }
    private TextView label(String text) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(16);
        view.setTextColor(Color.WHITE); view.setPadding(0, dp(8), 0, dp(8)); root.addView(view); return view;
    }
    private Button button(String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false);
        button.setTextSize(13); button.setTextColor(INK);
        button.setBackground(rounded(CHIP, 12));
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        button.setMinHeight(dp(48)); button.setOnClickListener(v -> action.run()); return button;
    }
    private Button accent(Button button) {
        button.setBackground(rounded(ACCENT, 12)); button.setTextColor(ON_ACCENT); return button;
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    /** A soft edge is the only hint that a row keeps going past the screen. */
    private void fade(HorizontalScrollView scroll) {
        scroll.setHorizontalFadingEdgeEnabled(true); scroll.setFadingEdgeLength(dp(22));
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
    private void showMatch() {
        screen = "match";
        // Fills the screen when it fits, scrolls when it does not: the composer stays reachable.
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        root = frame(); scroll.addView(root); setContentView(scroll);
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
        pitch = new PitchView(this, match, this::tapPlayer, this::pullPlayer);
        pitch.setMinimumHeight(dp(300));
        LinearLayout.LayoutParams pitchSize = new LinearLayout.LayoutParams(-1, 0, 1);
        pitchSize.topMargin = dp(8); pitchSize.bottomMargin = dp(8);
        root.addView(pitch, pitchSize);
        composer = new LinearLayout(this); composer.setOrientation(LinearLayout.VERTICAL);
        composer.setBackground(rounded(SURFACE, 16)); composer.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(composer, new LinearLayout.LayoutParams(-1, dp(COMPOSER)));
        status = label(formations() + "  ·  " + match.optString("competition") + "  ·  Notes privées");
        status.setTextSize(12); status.setTextColor(Color.rgb(177,198,184));
        status.setMaxLines(1); status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout navigation = strip(); root.addView(navigation);
        String[] titles = {"↶", "≡ Notes", "☆ Bilan", "•••"};
        String[] described = {"Annuler la dernière action", "Mes observations", "Bilan par joueur", "Autres actions"};
        Runnable[] clicks = {this::undo, this::history, this::standings, () -> new AlertDialog.Builder(this).setTitle("Mon match")
            .setItems(new String[]{"Synchroniser", "Configurer le serveur", "Exporter mes observations"}, (d,n) -> {
                if(n==0) sync(); else if(n==1) settings(); else export();
            }).show()};
        for(int i=0;i<titles.length;i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(48),1); p.setMargins(dp(3),0,dp(3),0);
            Button tab = button(titles[i], clicks[i]);
            tab.setContentDescription(described[i]);
            navigation.addView(tab, p);
        }
        updateClock(); renderComposer();
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
    /** Rewrites the open note under its own id: the log keeps every version, the reader takes the last. */
    private boolean writeNote() {
        try {
            record(operation("note", noteId).put("match_id", match.getString("id"))
                .put("minute", noteMinute).put("entries", draftEntries()));
            if (!draft.trim().equals(draftWritten)) {
                draftWritten = draft.trim();
                record(operation("comment", noteId).put("text", draftWritten));
            }
            written = true;
            return true;
        } catch (Exception e) { error(e); return false; }
    }
    /** The note is already in the log, so dropping it is a deletion, not an abandon. */
    private void discardNote() {
        try { record(operation("delete", noteId)); toast("Note supprimée"); }
        catch (Exception e) { error(e); return; }
        closeNote();
    }
    private void closeNote() {
        noteId = ""; entries.clear(); focus = ""; draft = ""; draftWritten = ""; written = false;
        renderComposer();
    }
    /** Reopen a note so the same moment can name one more player. */
    private void amend(JSONObject note) {
        noteId = note.optString("note_id"); noteMinute = note.optInt("minute");
        loadEntries(entriesOf(note));
        draft = note.optString("comment"); draftWritten = draft; written = true;
        focus = ""; renderComposer();
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
                    line.addView(mini("＋ joueur", "Ajouter un joueur à cette note", () -> amend(note)),
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
        Button general = button("+  Note sans joueur", this::generalNote);
        general.setTextSize(12); general.setTextColor(MUTED);
        GradientDrawable ghost = rounded(SURFACE, 10);
        ghost.setStroke(dp(1), Color.argb(60, 255, 255, 255));
        general.setBackground(ghost);
        bottom.addView(general, new LinearLayout.LayoutParams(dp(150), dp(40)));
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
        HorizontalScrollView chips = new HorizontalScrollView(this);
        chips.setHorizontalScrollBarEnabled(false); fade(chips);
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

        HorizontalScrollView palette = new HorizontalScrollView(this);
        palette.setHorizontalScrollBarEnabled(false); fade(palette);
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
                picker.clearFocus(); noteMinute = picker.getValue(); renderComposer();
            }).setNegativeButton("Annuler", null).show();
    }
    private void editDraft() {
        EditText input = new EditText(this); input.setHint("Commentaire facultatif");
        input.setText(draft);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});
        new AlertDialog.Builder(this).setTitle("Commentaire").setView(input)
            .setNegativeButton("Annuler", null).setPositiveButton("Enregistrer", (d,w) -> {
                draft = input.getText().toString(); renderComposer();
            }).show();
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
    /** One line: "⚽ 10 Mbappé · → 6 Pogba". */
    private String summary(JSONObject note) {
        List<JSONObject> list = ordered(entriesOf(note));
        if (list.isEmpty())
            return note.optString("comment").isEmpty() ? "Note générale" : note.optString("comment");
        StringBuilder text = new StringBuilder();
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
    private String teamName(String key) {
        JSONArray teams = match.optJSONArray("teams");
        for (int i = 0; i < teams.length(); i++)
            if (key.equals(teams.optJSONObject(i).optString("key"))) return teams.optJSONObject(i).optString("name");
        return key;
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
        return player == null ? id : player.optInt("number") + " · " + player.optString("name")
            + " (" + teamName(player.optString("team")) + ")";
    }
    /** Short enough for a chip: "10 Mbappé". */
    private String shortName(String id) {
        JSONObject player = playerById(id);
        return player == null ? id : player.optInt("number") + " " + player.optString("name");
    }
    private List<JSONObject> notes() throws Exception {
        LinkedHashMap<String, JSONObject> notes = new LinkedHashMap<>();
        Set<String> deleted = new HashSet<>();
        Map<String,String> comments = new HashMap<>();
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
            }
        }
        List<JSONObject> visible = new ArrayList<>();
        for (Map.Entry<String,JSONObject> entry : notes.entrySet()) {
            if (!deleted.contains(entry.getKey())) {
                entry.getValue().put("comment", comments.getOrDefault(entry.getKey(), ""));
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
    private void standings() {
        screen = "standings"; page("Bilan de mes notes");
        full("← Retour au match", this::showMatch);
        TextView caveat = label("Calculé sur mes seules notes : ce que j’ai remarqué, pas le match complet. "
            + "Base 6, une demi-note par point.");
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
                SpannableString text = new SpannableString(head + "\n"
                    + balanceText(cell[0]) + " de solde   ·   "
                    + cell[1] + " ↑   " + cell[2] + " ↓\n" + acts);
                text.setSpan(new RelativeSizeSpan(1.5f), 0, scoreText(cell[0]).length(), 0);
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
    private String previousComment(JSONArray ops, int before, String id) throws Exception {
        for (int i = before - 1; i >= 0; i--) {
            JSONObject op = ops.getJSONObject(i);
            if ("comment".equals(op.getString("kind")) && id.equals(op.getString("note_id")))
                return op.getString("text");
        }
        return "";
    }
    private void history() {
        screen = "history"; page("Mes observations"); full("← Retour au match", this::showMatch);
        try {
            List<JSONObject> notes = notes();
            int moments = notes.size(), acts = 0;
            for (JSONObject note : notes) acts += entriesOf(note).length();
            label(moments + (moments > 1 ? " notes · " : " note · ")
                + acts + (acts > 1 ? " actions relevées" : " action relevée")
                + " · observation non exhaustive");
            for (JSONObject note : notes) {
                List<JSONObject> list = ordered(entriesOf(note));
                StringBuilder text = new StringBuilder(note.optInt("minute") + "′");
                if (list.isEmpty()) text.append("  ·  Note générale");
                for (JSONObject entry : list) {
                    text.append("\n").append(icon(entry.optString("action"))).append("  ")
                        .append(actionName(entry.optString("action"))).append(" — ")
                        .append(playerName(entry.optString("player_id")));
                }
                if (!note.optString("comment").isEmpty()) text.append("\n").append(note.optString("comment"));
                full(text.toString(), () -> new AlertDialog.Builder(this).setTitle("Modifier la note")
                    .setItems(new String[]{"Commentaire", "Supprimer"}, (dialog, which) -> {
                        if (which == 0) comment(note);
                        else try { record(operation("delete", note.getString("note_id"))); history(); }
                        catch (Exception e) { error(e); }
                    }).show());
            }
        } catch (Exception e) { error(e); }
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
        new AlertDialog.Builder(this).setTitle("Chronomètre du match")
            .setItems(new String[]{
                "Coup d’envoi : démarrer à 0′",
                "Mi-temps : pause puis reprise auto à 45′",
                "Coup d’envoi 2e mi-temps : démarrer à 45′",
                clockRunning ? "Pause" : "Reprendre le chrono",
                "Ajuster à la minute de ma diffusion"}, (d,n) -> {
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
    private void settings() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        EditText url = new EditText(this); url.setHint("https://mon-serveur");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString("url", "http://10.0.2.2:8080")); box.addView(url);
        EditText token = new EditText(this); token.setHint("Jeton personnel");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(prefs.getString("token", "")); box.addView(token);
        new AlertDialog.Builder(this).setTitle("Serveur personnel").setView(box)
            .setPositiveButton("Enregistrer", (d,w) -> prefs.edit()
                .putString("url", url.getText().toString().trim().replaceAll("/+$", ""))
                .putString("token", token.getText().toString().trim()).apply())
            .setNegativeButton("Annuler", null).show();
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
