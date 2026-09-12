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
    /**
     * The touchline strip each club's bench sits on, and how far the grass gives way to it: the
     * pitch keeps a margin of its own outside its lines, so the bench takes that margin first and
     * only the rest out of the pitch. A seat is as tall as it can be up to {@code SEAT}; a long
     * bench on a short phone makes them shorter, never scrolls — a player hidden is a player lost.
     */
    private static final int BENCH = 40, YIELD = 30, BENCH_SHIRT = 26, SEAT = 48, BENCH_NAME_H = 13;
    /** Above every shirt, below the player being written about. */
    private static final int Z_NAME = 10, Z_HELD = 12, Z_SHIRT_ACTIVE = 14, Z_NAME_ACTIVE = 16;
    /** The grass, its lines and its light, drawn by the same code as the tactical board. */
    private final Pitch grass;
    private final JSONObject match;
    /** How far in from each side the grass starts; zero when nobody sits on a bench. */
    private final int yielded;
    private final android.graphics.Paint shade = new android.graphics.Paint();
    /** Which of the two looks the player picked in the options. */
    private final boolean glass;
    /** The two ends of the ring a player is lifted in while a note holds him. */
    private final int held, heldEnd;
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
    /** Who has played and is no longer on: back on the bench, but with his match behind him. */
    private final java.util.Set<String> replaced = new java.util.HashSet<>();
    /** Players, their touch targets and their labels, in step: the labels are added last so the
     *  whole of them sits above the whole of the shirts. */
    private final java.util.List<JSONObject> roster = new java.util.ArrayList<>();
    private final java.util.List<LinearLayout> markers = new java.util.ArrayList<>();
    private final java.util.List<TextView> labels = new java.util.ArrayList<>();
    PitchView(Context context, JSONObject match, boolean glass, int held, int heldEnd, int lawn,
              int lawnEnd, int minute, Consumer<String> select, Consumer<String> pull) {
        super(context);
        this.match = match; this.glass = glass; this.held = held; this.heldEnd = heldEnd;
        setWillNotDraw(false);
        grass = new Pitch(context);
        setBackground(Pitch.turf(lawn, lawnEnd, 20, getResources().getDisplayMetrics().density));
        setClipToOutline(true);
        // Team colours travel with the match: identity is data, not theme.
        JSONArray teams = match.optJSONArray("teams");
        for (int i = 0; i < teams.length(); i++) {
            JSONObject team = teams.optJSONObject(i);
            colours.put(team.optString("key"), Color.parseColor(team.optString("colour")));
        }
        JSONArray players = match.optJSONArray("players");
        boolean seated = false;
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            // Only a starter opens a spot; a substitute is drawn on the one he inherits.
            if (!player.has("x")) { seated = true; continue; }
            spots.put(player.optString("id"), new double[]{player.optDouble("x"), player.optDouble("y")});
        }
        // Eleven a side and nobody else — a lineup with no bench published — keeps the whole width.
        yielded = seated ? dp(YIELD) : 0;
        shade.setColor(Color.argb(46, 0, 12, 8));
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
            boolean coach = Lineup.coach(player);
            marker.setContentDescription((coach ? "Coach" : player.optString("name") + ", numéro "
                + player.optInt("number")) + ", " + player.optString("team")
                + ", ajouter à la note, appui long pour l’en retirer");
            marker.setFocusable(true);
            marker.setOnClickListener(v -> select.accept(player.optString("id")));
            // The pitch is the visual surface: taking someone out of a note belongs here too.
            marker.setOnLongClickListener(v -> { pull.accept(player.optString("id")); return true; });
            TextView shirt = new TextView(context);
            // A coach wears no number: the clipboard he stands with says who he is.
            shirt.setText(coach ? "📋" : String.valueOf(player.optInt("number"))); shirt.setTextSize(12.5f);
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
        replaced.clear();
        for (String starter : spots.keySet()) if (!standing.containsKey(starter)) replaced.add(starter);
        for (Lineup.Change change : changes)
            if (change.minute <= minute && !standing.containsKey(change.in)) replaced.add(change.in);
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
     *
     * <p>The grass and the shirts belong to the match, so no theme touches them; the ring that
     * lifts a player out of them belongs to the application, and carries the reader's accent —
     * paler again for the one player the palette is about to qualify.
     */
    static Drawable shirt(Context context, boolean glass, int base, boolean active, boolean lifted,
                          int held, int heldEnd) {
        float density = context.getResources().getDisplayMetrics().density;
        int inset = Math.round(density * FACE);
        int ring = active ? Skin.paler(held) : lifted ? held : base;
        // A skin that signs with a gradient spends it here and nowhere else.
        boolean signed = (active || lifted) && heldEnd != held;
        GradientDrawable front = new GradientDrawable();
        front.setShape(GradientDrawable.OVAL);
        if (!glass) {
            front.setColor(lifted ? Color.WHITE : base);
            front.setStroke(Math.round(density * (active ? 3 : lifted ? 2 : 1)),
                active || lifted ? ring : Color.argb(110, 255, 255, 255));
            return signed ? signature(front, density, inset, active, held, heldEnd)
                : new InsetDrawable(front, inset);
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
        if (signed) return signature(front, density, inset, active, held, heldEnd);
        LayerDrawable stack = new LayerDrawable(new Drawable[]{halo, front});
        stack.setLayerInset(1, inset, inset, inset, inset);
        return stack;
    }
    /**
     * The shirt inside a ring painted right round it, one colour turning into the other and back.
     * A band rather than a stroke, because a stroke carries one colour: the disc is set inside a
     * sweep-filled oval and what shows past its edge is the ring. It replaces the halo where
     * there was one — a gradient ring is already the light behind the player.
     */
    private static Drawable signature(GradientDrawable front, float density, int inset,
                                      boolean active, int held, int heldEnd) {
        GradientDrawable band = new GradientDrawable();
        band.setShape(GradientDrawable.OVAL);
        band.setGradientType(GradientDrawable.SWEEP_GRADIENT);
        band.setColors(new int[]{held, heldEnd, held});
        front.setStroke(0, Color.TRANSPARENT);
        int width = Math.round(density * (active ? 3.5f : 2.5f));
        LayerDrawable stack = new LayerDrawable(new Drawable[]{band, front});
        stack.setLayerInset(0, inset, inset, inset, inset);
        stack.setLayerInset(1, inset + width, inset + width, inset + width, inset + width);
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
            // Off the pitch is on the bench, as small as the strip it sits in, and as easy to note.
            boolean on = standing.containsKey(id), benched = !on && yielded > 0;
            marker.setVisibility(on || benched ? VISIBLE : GONE);
            name.setVisibility(on || benched ? VISIBLE : GONE);
            if (!on && !benched) continue;
            seat(marker, name, benched);
            boolean marked = marks.containsKey(id), active = selecting && id.equals(focus);
            boolean lifted = marked && selecting;
            String mark = marked ? marks.get(id) : "";
            Integer tint = tints.get(id);
            int base = colours.get(player.optString("team"));
            TextView shirt = (TextView)marker.getChildAt(0);
            shirt.setBackground(shirt(getContext(), glass, base, active, lifted, held, heldEnd));
            // Glass owes its depth to the halo; a painted disc still wants its shadow.
            shirt.setElevation(glass ? 0 : dp(2));
            shirt.setTextColor(glass && !lifted ? base : Color.rgb(15, 35, 33));
            // The mark rides on the shirt itself, so the pitch alone tells the story. A bench
            // strip has room for a surname and no more; the mark is already on the lifted shirt.
            String shown = benched ? seatName(player) : PlayerName.shorten(player.optString("name"));
            name.setText(mark.isEmpty() || benched ? shown : mark + " " + shown);
            // A player already replaced has had his match: he sits beside those still waiting for
            // theirs, set back a shade until a note picks him up again.
            float presence = benched && replaced.contains(id) && !marked ? .55f : 1f;
            marker.setAlpha(presence); name.setAlpha(presence);
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
    /** The same marker dressed for the grass or for the bench: only its size and its name change. */
    private void seat(LinearLayout marker, TextView name, boolean benched) {
        TextView shirt = (TextView)marker.getChildAt(0);
        int size = dp(benched ? BENCH_SHIRT : SHIRT);
        LinearLayout.LayoutParams fits = (LinearLayout.LayoutParams)shirt.getLayoutParams();
        if (fits.width != size) { fits.width = size; fits.height = size; shirt.setLayoutParams(fits); }
        shirt.setTextSize(benched ? 10 : 12.5f);
        // On the bench the name sits under the shirt inside the seat, so the shirt goes to the top.
        marker.setGravity(benched ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
        name.setTextSize(benched ? 9 : 10.5f);
        name.setMaxWidth(dp(benched ? BENCH : NAME_W));
        name.setPadding(dp(benched ? 3 : 5), 0, dp(benched ? 3 : 5), 0);
    }
    /** A surname, which is all a strip this narrow can carry; the coach is called what he is. */
    private static String seatName(JSONObject player) {
        if (Lineup.coach(player)) return "Coach";
        String shown = PlayerName.shorten(player.optString("name"));
        int initial = shown.indexOf(". ");
        return initial == 1 ? shown.substring(3) : shown;
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        int wide = r-l, high = b-t, touch = dp(TOUCH), tall = dp(NAME_H);
        // The grass between the two benches; spots are fractions of it, not of the whole view.
        int field = wide - 2 * yielded;
        java.util.Map<String,java.util.List<Integer>> benches = new java.util.HashMap<>();
        for (int i = 0; i < roster.size(); i++) {
            JSONObject player = roster.get(i);
            double[] spot = spots.get(standing.get(player.optString("id")));
            if (spot == null) {
                if (yielded > 0) benches.computeIfAbsent(player.optString("team"),
                    k -> new java.util.ArrayList<>()).add(i);
                continue;
            }
            // Shirt numbers repeat across teams, so each player carries its own spot.
            float x = (float)spot[0], y = (float)spot[1];
            // Spots are authored for the home side attacking downwards; mirror the away side.
            if (!"home".equals(player.optString("team"))) { x = 1-x; y = 1-y; }
            // The spot is the shirt itself, not a box holding shirt and name: the name hangs
            // below it and is free to reach into the next line, being drawn above it.
            int cx = yielded + Math.round(x * field), cy = Math.round(y * high);
            // Goalkeepers sit on the goal line: keep their marker whole instead of cropped by the edge.
            int left = Math.max(yielded, Math.min(yielded + field - touch, cx - touch/2));
            int top = Math.max(0, Math.min(high - touch, cy - touch/2));
            markers.get(i).layout(left, top, left+touch, top+touch);
            TextView name = labels.get(i);
            int span = Math.min(name.getMeasuredWidth(), dp(NAME_W));
            int nameLeft = Math.max(yielded, Math.min(yielded + field - span, cx - span/2));
            int nameTop = Math.max(0, Math.min(high - tall, top + touch - dp(4)));
            name.layout(nameLeft, nameTop, nameLeft+span, nameTop+tall);
        }
        for (java.util.Map.Entry<String,java.util.List<Integer>> bench : benches.entrySet())
            layBench(bench.getValue(), "home".equals(bench.getKey()), wide, high);
    }
    /**
     * One club's bench, down its own touchline: the home side's on the left from its own goal
     * down, the away side's on the right from its own goal up — the same half-turn the pitch gives
     * the away eleven. The coach sits at the end nearest his goal, the substitutes by shirt number.
     */
    private void layBench(java.util.List<Integer> seated, boolean home, int wide, int high) {
        seated.sort((a, b) -> {
            boolean first = Lineup.coach(roster.get(a)), second = Lineup.coach(roster.get(b));
            if (first != second) return first ? -1 : 1;
            return Lineup.SHIRT_ORDER.compare(roster.get(a), roster.get(b));
        });
        int strip = dp(BENCH), tall = dp(BENCH_NAME_H);
        // A seat is its shirt and the name under it. The last one must end on the goal line, not
        // past it, so the spacing is what is left once one whole seat has been set aside.
        // The ends stay clear of the rounded corners of the turf, which would clip them.
        int body = dp(BENCH_SHIRT) - dp(3) + tall, n = seated.size(), end = dp(4);
        int slot = n < 2 ? dp(SEAT) : Math.min(dp(SEAT), (high - 2 * end - body) / (n - 1));
        int left = home ? 0 : wide - strip;
        for (int k = 0; k < n; k++) {
            int i = seated.get(k), top = home ? end + k * slot : high - end - body - k * slot;
            // Never taller than the gap to the next seat: two touch targets must not overlap.
            markers.get(i).layout(left, top, left + strip, top + Math.min(slot, body));
            TextView name = labels.get(i);
            int span = Math.min(name.getMeasuredWidth(), strip);
            int nameLeft = left + (strip - span) / 2, nameTop = top + dp(BENCH_SHIRT) - dp(3);
            name.layout(nameLeft, nameTop, nameLeft + span, nameTop + tall);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // The benches sit on the turf outside the lines, a shade darker than the mown field.
        if (yielded > 0) {
            canvas.drawRect(0, 0, yielded, getHeight(), shade);
            canvas.drawRect(getWidth() - yielded, 0, getWidth(), getHeight(), shade);
        }
        canvas.save();
        canvas.translate(yielded, 0);
        grass.draw(canvas, getWidth() - 2 * yielded, getHeight());
        canvas.restore();
    }
}
