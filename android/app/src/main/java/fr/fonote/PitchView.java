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
import android.view.MotionEvent;
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
    /** Where each player of the note did what he did, on the board's frame; drawn while composing. */
    private java.util.Map<String,double[]> places = new java.util.HashMap<>();
    /** Set while the next touch on the grass says where: the shirts step back and catch nothing. */
    private Consumer<double[]> placing;
    /** The point under the finger while it looks for the spot or carries one, null otherwise. */
    private double[] aim;
    /** Whose spot {@link #aim} stands for: the player in focus while placing, the one carried while dragging. */
    private String aimedAt = "";
    /** Takes a spot already given to where the finger lifted it; null while there is none to move. */
    private java.util.function.BiConsumer<String,double[]> moved;
    /** The player whose spot the finger came down on, and whether it has moved far enough to be a drag. */
    private String grabbed = "";
    private boolean dragging;
    private float grabX, grabY;
    private float[] grabFrom;
    /** How near a spot a finger may land to take it: a finger's width, though the dot is smaller. */
    private static final int GRAB = 20;
    private final Consumer<String> select;
    private final android.graphics.Paint dot = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint tie = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    PitchView(Context context, JSONObject match, boolean glass, int held, int heldEnd, int lawn,
              int lawnEnd, int minute, Consumer<String> select, Consumer<String> pull) {
        super(context);
        this.match = match; this.glass = glass; this.held = held; this.heldEnd = heldEnd;
        this.select = select;
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
        tie.setStyle(android.graphics.Paint.Style.STROKE); tie.setStrokeWidth(dp(2));
        tie.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(4), dp(4)}, 0));
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
        invalidate();
    }
    /** Where the players of the open note were, drawn in the tint of their action. */
    void setPlaces(java.util.Map<String,double[]> places) { this.places = places; invalidate(); }
    /**
     * The next touch on the grass says where the player in focus was; null gives the shirts back
     * their touch. The finger may slide before it lifts: the point follows it, and lifting is what counts.
     */
    void placing(Consumer<double[]> where) {
        if ((placing == null) == (where == null)) { placing = where; return; }
        placing = where; aim = null;
        setContentDescription(where == null ? null : "Toucher l’endroit de l’action sur le terrain");
        refresh();
        invalidate();
    }
    /**
     * A spot already given can be taken and dragged to where it belongs: going back through the
     * 📍 to correct a point that is right there on the grass cost two taps and a second aim.
     */
    void moving(java.util.function.BiConsumer<String,double[]> moved) { this.moved = moved; }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (placing != null) return true;
        // A spot is drawn above the shirts, so it is also touched before them.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && moved != null) {
            grabbed = spotUnder(event.getX(), event.getY());
            if (!grabbed.isEmpty()) return true;
        }
        return super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (placing == null) return grabbed.isEmpty() ? super.onTouchEvent(event) : drag(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // The finger is choosing a point, not turning the card nor pulling the page.
                getParent().requestDisallowInterceptTouchEvent(true);
                aimedAt = focus; aim = pointAt(event); invalidate(); break;
            case MotionEvent.ACTION_MOVE: aim = pointAt(event); invalidate(); break;
            case MotionEvent.ACTION_UP:
                double[] spot = pointAt(event); aim = null;
                performClick(); placing.accept(spot); break;
            case MotionEvent.ACTION_CANCEL: aim = null; invalidate(); break;
        }
        return true;
    }
    /**
     * A spot carried by the finger. It keeps the distance at which it was taken, so it does not
     * jump under the fingertip; it grows while carried, like a spot being aimed, to show round the
     * finger. A touch that never moves is a tap on the player the spot belongs to, as on his shirt.
     */
    private boolean drag(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // The finger carries a spot, it is not turning the card nor pulling the page.
                getParent().requestDisallowInterceptTouchEvent(true);
                grabX = event.getX(); grabY = event.getY();
                grabFrom = onGrass(places.get(grabbed)); dragging = false; break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.hypot(event.getX() - grabX, event.getY() - grabY)
                        < android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) break;
                dragging = true; aimedAt = grabbed;
                aim = pointAt(grabFrom[0] + event.getX() - grabX, grabFrom[1] + event.getY() - grabY);
                invalidate(); break;
            case MotionEvent.ACTION_UP: {
                String id = grabbed;
                double[] spot = aim;
                boolean carried = dragging && spot != null;
                grabbed = ""; dragging = false; aim = null;
                performClick();
                if (carried) moved.accept(id, spot); else { invalidate(); select.accept(id); }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                grabbed = ""; dragging = false; aim = null; invalidate(); break;
        }
        return true;
    }
    /** The player whose spot lies under a touch, the nearest when two are close; "" for none. */
    private String spotUnder(float x, float y) {
        String nearest = "";
        double best = dp(GRAB);
        for (java.util.Map.Entry<String,double[]> place : places.entrySet()) {
            float[] at = onGrass(place.getValue());
            double distance = Math.hypot(at[0] - x, at[1] - y);
            if (distance <= best) { best = distance; nearest = place.getKey(); }
        }
        return nearest;
    }
    @Override public boolean performClick() { return super.performClick(); }
    /** A touch as a point of the grass, in fractions of it; the benches count as its touchlines. */
    private double[] pointAt(MotionEvent event) { return pointAt(event.getX(), event.getY()); }
    private double[] pointAt(float touchX, float touchY) {
        double field = Math.max(1, getWidth() - 2 * yielded);
        double x = Math.max(0, Math.min(1, (touchX - yielded) / field));
        double y = Math.max(0, Math.min(1, touchY / Math.max(1, getHeight())));
        // A thousandth of the pitch is about ten centimetres: finer says nothing more.
        return new double[]{Math.round(x * 1000) / 1000.0, Math.round(y * 1000) / 1000.0};
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
            // Looking for a spot, the grass is what is being touched: only the player it is for stays.
            if (placing != null && !id.equals(focus)) presence = .3f;
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
        // The thread from a shirt to its spot runs under the shirts; the spot itself sits above them.
        for (String id : shownPlaces().keySet()) {
            float[] from = shirtCentre(id), to = onGrass(shownPlaces().get(id));
            if (from == null) continue;
            tie.setColor(tint(id)); tie.setAlpha(190);
            canvas.drawLine(from[0], from[1], to[0], to[1], tie);
        }
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        for (java.util.Map.Entry<String,double[]> place : shownPlaces().entrySet()) {
            float[] at = onGrass(place.getValue());
            boolean aimed = aim != null && place.getKey().equals(aimedAt);
            // Under a finger, a point must be wide enough to show round it.
            float radius = dp(aimed ? 16 : place.getKey().equals(focus) ? 8 : 6);
            dot.setStyle(android.graphics.Paint.Style.FILL);
            dot.setColor(tint(place.getKey())); dot.setAlpha(aimed ? 120 : 255);
            canvas.drawCircle(at[0], at[1], radius, dot);
            dot.setStyle(android.graphics.Paint.Style.STROKE); dot.setStrokeWidth(dp(2));
            dot.setColor(Color.WHITE);
            canvas.drawCircle(at[0], at[1], radius, dot);
        }
    }
    /** The places of the note, with the one being aimed or carried following the finger. */
    private java.util.Map<String,double[]> shownPlaces() {
        if (aim == null) return places;
        java.util.Map<String,double[]> shown = new java.util.HashMap<>(places);
        shown.put(aimedAt, aim);
        return shown;
    }
    private int tint(String id) {
        Integer tint = tints.get(id);
        return tint == null ? Color.WHITE : tint;
    }
    private float[] onGrass(double[] point) {
        return new float[]{yielded + (float)point[0] * (getWidth() - 2 * yielded), (float)point[1] * getHeight()};
    }
    /** Where a player's shirt is drawn right now, on the grass or on his bench; null if nowhere. */
    private float[] shirtCentre(String id) {
        for (int i = 0; i < roster.size(); i++) {
            if (!id.equals(roster.get(i).optString("id"))) continue;
            LinearLayout marker = markers.get(i);
            if (marker.getVisibility() != VISIBLE) return null;
            android.view.View shirt = marker.getChildAt(0);
            return new float[]{marker.getLeft() + shirt.getLeft() + shirt.getWidth() / 2f,
                marker.getTop() + shirt.getTop() + shirt.getHeight() / 2f};
        }
        return null;
    }
}
