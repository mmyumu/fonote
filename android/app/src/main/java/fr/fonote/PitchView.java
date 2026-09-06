package fr.fonote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.Consumer;

/** Field graphics underneath accessible, individually clickable player controls. */
final class PitchView extends FrameLayout {
    /**
     * The shirt and its touch target; the name is laid out on its own, on a layer above every
     * shirt. Crowded formations make markers overlap whatever their size — five lines share one
     * half of the pitch — so what matters is not avoiding the overlap but deciding who wins it:
     * a name is always readable, and a number half-covered still has its colour and its place.
     */
    private static final int SHIRT = 32, FACE = 3, TOUCH = 44, NAME_W = 68, NAME_H = 16;
    /** Above every shirt, below the player being written about. */
    private static final int Z_NAME = 10, Z_HELD = 12, Z_SHIRT_ACTIVE = 14, Z_NAME_ACTIVE = 16;
    /** The grass, its lines and its light, drawn by the same code as the tactical board. */
    private final Pitch grass;
    private final JSONObject match;
    /** Which of the two looks the player picked in the options. */
    private final boolean glass;
    private final java.util.Map<String,Integer> colours = new java.util.HashMap<>();
    /** What each player carries right now: an action symbol while composing, a mark otherwise. */
    private java.util.Map<String,String> marks = new java.util.HashMap<>();
    private java.util.Map<String,Integer> tints = new java.util.HashMap<>();
    private String focus = "";
    private boolean selecting;
    /** The spot each starter opened, and who holds it right now. Substitutes inherit a spot. */
    private final java.util.Map<String,double[]> spots = new java.util.LinkedHashMap<>();
    private final java.util.List<Lineup.Change> changes = new java.util.ArrayList<>();
    private java.util.Map<String,String> standing = new java.util.HashMap<>();
    /** Players, their touch targets and their labels, in step: the labels are added last so the
     *  whole of them sits above the whole of the shirts. */
    private final java.util.List<JSONObject> roster = new java.util.ArrayList<>();
    private final java.util.List<LinearLayout> markers = new java.util.ArrayList<>();
    private final java.util.List<TextView> labels = new java.util.ArrayList<>();
    PitchView(Context context, JSONObject match, boolean glass, int minute,
              Consumer<String> select, Consumer<String> pull) {
        super(context);
        this.match = match; this.glass = glass;
        setWillNotDraw(false);
        grass = new Pitch(context);
        setBackground(Pitch.turf(20, getResources().getDisplayMetrics().density));
        setClipToOutline(true);
        // Team colours travel with the match: identity is data, not theme.
        JSONArray teams = match.optJSONArray("teams");
        for (int i = 0; i < teams.length(); i++) {
            JSONObject team = teams.optJSONObject(i);
            colours.put(team.optString("key"), Color.parseColor(team.optString("colour")));
        }
        JSONArray players = match.optJSONArray("players");
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            // Only a starter opens a spot; a substitute is drawn on the one he inherits.
            if (!player.has("x")) continue;
            spots.put(player.optString("id"), new double[]{player.optDouble("x"), player.optDouble("y")});
        }
        JSONArray published = match.optJSONArray("changes");
        for (int i = 0; published != null && i < published.length(); i++) {
            JSONObject change = published.optJSONObject(i);
            changes.add(new Lineup.Change(change.optInt("minute"),
                change.optString("in"), change.optString("out")));
        }
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            roster.add(player);
            LinearLayout marker = new LinearLayout(context);
            marker.setGravity(Gravity.CENTER);
            // The shirt casts a shadow past its own square; nothing here needs cropping.
            marker.setClipChildren(false); marker.setClipToPadding(false);
            marker.setContentDescription(player.optString("name") + ", numéro " + player.optInt("number")
                + ", " + player.optString("team")
                + ", ajouter à la note, appui long pour l’en retirer");
            marker.setFocusable(true);
            marker.setOnClickListener(v -> select.accept(player.optString("id")));
            // The pitch is the visual surface: taking someone out of a note belongs here too.
            marker.setOnLongClickListener(v -> { pull.accept(player.optString("id")); return true; });
            TextView shirt = new TextView(context);
            shirt.setText(String.valueOf(player.optInt("number"))); shirt.setTextSize(12.5f);
            shirt.setTypeface(Typeface.DEFAULT, Typeface.BOLD); shirt.setGravity(Gravity.CENTER);
            marker.addView(shirt, new LinearLayout.LayoutParams(dp(SHIRT), dp(SHIRT)));
            markers.add(marker);
            addView(marker, new FrameLayout.LayoutParams(dp(TOUCH), dp(TOUCH)));
        }
        // Second pass, and the whole point: every label is added after every shirt, so no shirt
        // can ever be painted over a name.
        for (JSONObject player : roster) {
            TextView name = new TextView(context);
            name.setText(PlayerName.shorten(player.optString("name"))); name.setTextSize(10.5f);
            name.setTextColor(Color.WHITE); name.setGravity(Gravity.CENTER);
            // A marked name is longer than a bare one and must not spill onto its neighbour.
            name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setMaxWidth(dp(NAME_W));
            name.setPadding(dp(5), 0, dp(5), 0);
            // The name carries its own patch of shade: pitch lines never run through it.
            GradientDrawable plate = new GradientDrawable();
            plate.setCornerRadius(dp(8)); name.setBackground(plate);
            name.setShadowLayer(2, 0, 1, Color.argb(170, 0, 0, 0));
            // Raised for the draw order alone: an outline here would cast a shadow on the shirts.
            name.setOutlineProvider(null);
            // A label is read, never tapped: the touch belongs to the shirt underneath it.
            name.setClickable(false); name.setFocusable(false);
            labels.add(name);
            addView(name, new FrameLayout.LayoutParams(-2, dp(NAME_H)));
        }
        setMinute(minute);
    }
    /**
     * The pitch follows the match: at this minute, whoever came on stands where the player he
     * replaced stood, and whoever went off is no longer there to be tapped. A note already
     * written keeps its players regardless — the journal is not redrawn.
     */
    /** A newer run of play: the same players, one more change to honour. */
    void setChanges(JSONArray published, int minute) {
        changes.clear();
        for (int i = 0; published != null && i < published.length(); i++) {
            JSONObject change = published.optJSONObject(i);
            changes.add(new Lineup.Change(change.optInt("minute"),
                change.optString("in"), change.optString("out")));
        }
        setMinute(minute);
    }
    void setMinute(int minute) {
        java.util.Map<String,String> holders = Lineup.holders(
            new java.util.ArrayList<>(spots.keySet()), changes, minute);
        java.util.Map<String,String> next = new java.util.HashMap<>();
        for (java.util.Map.Entry<String,String> held : holders.entrySet())
            next.put(held.getValue(), held.getKey());
        if (next.equals(standing)) return;
        standing = next;
        refresh();
        requestLayout();
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
    /**
     * The shirt, in either of the two looks the options offer. Glass is a dark disc lit from behind
     * by a halo of the team colour, which also draws the ring and writes the number: the team reads
     * from the light rather than from a block of paint. Solid is the plain painted disc. Either way
     * holding a player in the note takes the shirt over — white face, accent ring — unmistakably.
     *
     * <p>Static because the options screen shows the same shirt it is offering, drawn by this code
     * rather than by a picture of it that could drift.
     */
    static Drawable shirt(Context context, boolean glass, int base, boolean active, boolean lifted) {
        float density = context.getResources().getDisplayMetrics().density;
        int inset = Math.round(density * FACE);
        int ring = active ? Color.rgb(213, 255, 170) : lifted ? Color.rgb(207, 240, 160) : base;
        GradientDrawable front = new GradientDrawable();
        front.setShape(GradientDrawable.OVAL);
        if (!glass) {
            front.setColor(lifted ? Color.WHITE : base);
            front.setStroke(Math.round(density * (active ? 3 : lifted ? 2 : 1)),
                active || lifted ? ring : Color.argb(110, 255, 255, 255));
            return new InsetDrawable(front, inset);
        }
        if (lifted) front.setColor(Color.WHITE);
        else front.setColors(new int[]{Color.argb(234, 22, 48, 40), Color.argb(234, 10, 26, 22)});
        front.setStroke(Math.round(density * (active ? 3 : 2)), ring);
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        halo.setGradientRadius(density * SHIRT * .5f);
        halo.setColors(new int[]{Color.argb(active || lifted ? 150 : 120,
            Color.red(ring), Color.green(ring), Color.blue(ring)), Color.TRANSPARENT});
        LayerDrawable stack = new LayerDrawable(new Drawable[]{halo, front});
        stack.setLayerInset(1, inset, inset, inset, inset);
        return stack;
    }
    /**
     * While composing, the pitch shows the note being built and lifts its players out of their
     * team colour. At rest it only badges them, so the standings never masquerade as a selection.
     */
    void setMarks(java.util.Map<String,String> marks, java.util.Map<String,Integer> tints,
                  String focus, boolean selecting) {
        this.marks = marks; this.tints = tints; this.focus = focus; this.selecting = selecting;
        refresh();
    }
    private void refresh() {
        for (int i = 0; i < roster.size(); i++) {
            JSONObject player = roster.get(i);
            LinearLayout marker = markers.get(i);
            TextView name = labels.get(i);
            String id = player.optString("id");
            // Off the pitch is off the pitch: no marker to tap, and none to crowd the others.
            boolean on = standing.containsKey(id);
            marker.setVisibility(on ? VISIBLE : GONE);
            name.setVisibility(on ? VISIBLE : GONE);
            if (!on) continue;
            boolean marked = marks.containsKey(id), active = selecting && id.equals(focus);
            boolean lifted = marked && selecting;
            String mark = marked ? marks.get(id) : "";
            Integer tint = tints.get(id);
            int base = colours.get(player.optString("team"));
            TextView shirt = (TextView)marker.getChildAt(0);
            shirt.setBackground(shirt(getContext(), glass, base, active, lifted));
            // Glass owes its depth to the halo; a painted disc still wants its shadow.
            shirt.setElevation(glass ? 0 : dp(2));
            shirt.setTextColor(glass && !lifted ? base : Color.rgb(15, 35, 33));
            // The mark rides on the shirt itself, so the pitch alone tells the story.
            String shown = PlayerName.shorten(player.optString("name"));
            name.setText(mark.isEmpty() ? shown : mark + " " + shown);
            name.setTextColor(marked && tint != null ? tint : Color.WHITE);
            ((GradientDrawable)name.getBackground()).setColor(
                marked ? Color.argb(178, 6, 28, 22) : Color.argb(105, 6, 24, 19));
            // Names sit above shirts, and the player being written about sits above the names:
            // reading a neighbour must never cost the number of the one being noted.
            marker.setTranslationZ(active ? dp(Z_SHIRT_ACTIVE) : lifted ? dp(4) : 0);
            name.setTranslationZ(dp(active ? Z_NAME_ACTIVE : lifted ? Z_HELD : Z_NAME));
            marker.setSelected(lifted);
        }
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        int wide = r-l, high = b-t, touch = dp(TOUCH), tall = dp(NAME_H);
        for (int i = 0; i < roster.size(); i++) {
            JSONObject player = roster.get(i);
            double[] spot = spots.get(standing.get(player.optString("id")));
            if (spot == null) continue;
            // Shirt numbers repeat across teams, so each player carries its own spot.
            float x = (float)spot[0], y = (float)spot[1];
            // Spots are authored for the home side attacking downwards; mirror the away side.
            if (!"home".equals(player.optString("team"))) { x = 1-x; y = 1-y; }
            // The spot is the shirt itself, not a box holding shirt and name: the name hangs
            // below it and is free to reach into the next line, being drawn above it.
            int cx = Math.round(x * wide), cy = Math.round(y * high);
            // Goalkeepers sit on the goal line: keep their marker whole instead of cropped by the edge.
            int left = Math.max(0, Math.min(wide - touch, cx - touch/2));
            int top = Math.max(0, Math.min(high - touch, cy - touch/2));
            markers.get(i).layout(left, top, left+touch, top+touch);
            TextView name = labels.get(i);
            int span = Math.min(name.getMeasuredWidth(), dp(NAME_W));
            int nameLeft = Math.max(0, Math.min(wide - span, cx - span/2));
            int nameTop = Math.max(0, Math.min(high - tall, top + touch - dp(4)));
            name.layout(nameLeft, nameTop, nameLeft+span, nameTop+tall);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        grass.draw(canvas, getWidth(), getHeight());
    }
}
