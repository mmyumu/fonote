package fr.fonote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.IntConsumer;

/**
 * The board a tactical note is drawn on: the same grass as the pitch, carrying only the players
 * the moment is about and the run of play between them.
 *
 * <p>Two tools rather than one clever gesture. A finger that drags is ambiguous — it could mean
 * "this player stood further left" or "the ball went there" — and guessing wrong either loses a
 * stroke or moves a player who was already in the right place. Saying which of the two is meant
 * costs one tap before a sequence of strokes and never costs a mistake.
 *
 * <p>Meaning is carried by the line and never by its colour: colour on this pitch already says
 * which team a shirt belongs to, and a second meaning laid over it would make both unreadable.
 */
final class BoardView extends View {
    /** What the next drag will do. */
    static final int MOVE = 0, DRAW = 1, ERASE = 2, BALL = 3;
    /**
     * A trip whose duration the drawing decides, rather than the toolbar. Noting an action as it
     * is remembered, a pass and the run that answered it are not timed by anybody — but a long
     * ball is slower than a square one and a thirty-metre run is not a two-metre one, and that
     * much the stroke already says. Choosing a duration by hand stays there for the sequence that
     * has to be exact.
     */
    static final int AUTO = 0;
    /**
     * A pitch is 105 metres by 68, so a fraction of the board is worth more lengthwise than it is
     * across, and the two axes are weighed apart before the length is turned into a time. The
     * paces are the ordinary ones of a match, in metres per second: a firm ground pass, a struck
     * ball, a player running.
     */
    private static final double LENGTH = 105, WIDTH = 68;
    private static final double PASSED = 15, STRUCK = 25, RUNNING = 6.5;
    /** Under three tenths nothing is legible, whatever the drawing says. */
    private static final int BRIEF = 3;
    private int time, travel = AUTO, actor = -1;
    int time() { return time; }
    void setTime(int value) { time = Math.max(0, Math.min(Track.END, value)); invalidate(); }
    int travel() { return travel; }
    void setTravel(int value) { travel = value; }
    private double[] spot(Diagram.Token token) { return diagram.position(token, time); }
    private void message(String text) { android.widget.Toast.makeText(getContext(), text, android.widget.Toast.LENGTH_SHORT).show(); }
    /**
     * A shirt on this board is a mark, not a mannequin. Two and twenty dp is still three times a
     * man on a pitch that would draw him at five, but a disc has a number to carry and the pitch
     * of a schema is read at arm's length; drawn at the true scale it would be a speck. Big
     * enough to read, small enough that four players in a corner stay four players and a pass
     * between neighbours stays a pass — which the old thirty-two, a good six metres of grass,
     * did not allow.
     */
    private static final int SHIRT = 22, NAME_H = 15, NAME_PAD = 5;
    /** How close a finger must land to take hold of a token, and a stroke's end to snap to one. */
    private static final int GRAB = 26, SNAP = 34, RUBBER = 26;
    /** Under this, a drag was a tap that wandered, and a stroke was not a stroke. */
    private static final int TAP = 8, STROKE = 14;
    /** A sampled point every few pixels: enough to keep a curve, few enough to write down. */
    private static final int SAMPLE = 6;

    private final Pitch grass;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF plate = new RectF();
    private final Path path = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float[] position = new float[2], tangent = new float[2];
    private final JSONObject match;
    private final boolean glass, editable;
    /** The ring the board selects in, both its ends, and the band it draws round a group. */
    private final int held, heldEnd;
    /** A thumbnail has no room for names, and nothing to gain from them. */
    private final boolean compact;
    private Diagram diagram = new Diagram();
    private int tool = MOVE;
    private String stroke = Diagram.PASS;
    /**
     * Who the next drag carries. A set rather than an index: a schema is very often a block —
     * a back four pushed up, a midfield slid across — and moving nine players one at a time is
     * how a drawing stops being worth making.
     */
    private final java.util.LinkedHashSet<Integer> chosen = new java.util.LinkedHashSet<>();
    private int holding = -1;
    private Diagram.Shape drawing;
    private float downX, downY, lastX, lastY;
    /** A drag on bare grass encircles instead of drawing: in Déplacer that gesture was free. */
    private boolean banding;
    private boolean wandered;
    /**
     * A long press on a player adds him to the group or takes him out of it. A rectangle is a
     * blunt instrument — it catches the holding midfielder standing between the lines it wanted —
     * and redrawing it to correct one man is worse than correcting the one man. The touch is
     * timed here rather than left to the platform: the view answers every event itself, so the
     * long press the framework would have scheduled is never scheduled.
     */
    private final Handler press = new Handler(Looper.getMainLooper());
    private Runnable pending;
    /** Set once a long press has spoken, so releasing the finger does not speak over it. */
    private boolean toggled;
    private IntConsumer onSelect;
    private Runnable onChange;

    BoardView(Context context, JSONObject match, boolean glass, int held, int heldEnd,
              boolean editable, boolean compact) {
        super(context);
        this.match = match; this.glass = glass; this.held = held; this.heldEnd = heldEnd;
        this.editable = editable; this.compact = compact;
        grass = new Pitch(context);
        setBackground(Pitch.turf(compact ? 12 : 20, context.getResources().getDisplayMetrics().density));
        setClipToOutline(true);
        ink.setTextAlign(Paint.Align.CENTER);
        if (!editable) setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void watch(IntConsumer onSelect, Runnable onChange) {
        this.onSelect = onSelect; this.onChange = onChange;
    }

    Diagram diagram() { return diagram; }
    void setDiagram(Diagram diagram) {
        this.diagram = diagram;
        chosen.clear();
        describe();
        invalidate();
    }
    /**
     * A drawing cannot be read aloud, but what it holds can be counted: the board says how many
     * players stand on it and how many strokes cross it, and the panel below names whoever is
     * selected and offers every control by name.
     */
    private void describe() {
        if (!editable) return;
        int players = diagram.tokens.size(), strokes = diagram.shapes.size();
        setContentDescription("Terrain de la note tactique, "
            + (players == 0 ? "aucun joueur placé" : players + (players > 1 ? " joueurs placés" : " joueur placé"))
            + ", " + (strokes == 0 ? "aucun tracé" : strokes + (strokes > 1 ? " tracés" : " tracé"))
            + (chosen.size() > 1 ? ", " + chosen.size() + " sélectionnés, appui long sur un joueur "
                + "pour l’ajouter à la sélection ou l’en retirer" : ""));
    }
    int tool() { return tool; }
    String stroke() { return stroke; }
    /** The eraser and the two drags are one setting: only one of them can hold the next finger. */
    void setTool(int tool) { this.tool = tool; invalidate(); }
    void setStroke(String kind) { this.stroke = kind; this.tool = DRAW; invalidate(); }
    /** The one player being written about, or -1 when nobody or a whole group is held. */
    int selected() { return chosen.size() == 1 ? chosen.iterator().next() : -1; }
    /** Everyone held, in the order they stand on the board. */
    java.util.List<Integer> chosen() {
        java.util.List<Integer> result = new java.util.ArrayList<>(chosen);
        java.util.Collections.sort(result);
        return result;
    }
    void select(int index) {
        chosen.clear();
        if (index >= 0) chosen.add(index);
        describe(); invalidate();
    }

    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }

    /**
     * The edges of the board belong to the board. A full-back stands on the touchline and a group
     * is encircled from outside it, and both gestures start where the system reads a back swipe —
     * dragging Maronnier left would leave the note instead of moving him. The platform caps what
     * an application may claim at 200 dp an edge, so this covers the drags nearest the frame
     * rather than all of them; there is no way to ask for more.
     */
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (!editable || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return;
        setSystemGestureExclusionRects(java.util.Collections.singletonList(
            new android.graphics.Rect(0, 0, width, height)));
    }

    // ——— Dessin ———

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        grass.draw(canvas, getWidth(), getHeight());
        for (Diagram.Shape shape : diagram.shapes) paintShape(canvas, shape);
        if (!compact) for (Diagram.Token token : diagram.tokens) {
            double[] previous = new double[]{token.x, token.y};
            int since = 0;
            for (Track.Key key : token.track.keys) {
                if (key.time == 0) { previous = new double[]{key.x, key.y}; continue; }
                // Course or conduite is not a choice made at the toolbar: the ball decides.
                Diagram.Shape route = new Diagram.Shape(
                    diagram.carrying(token, since, key.time) ? Diagram.CARRY : Diagram.RUN);
                route.points.add(previous);
                for (int j = 1; j < key.path.size()-1; j++) route.points.add(key.path.get(j));
                route.points.add(new double[]{key.x, key.y});
                paintShape(canvas, route);
                previous = new double[]{key.x, key.y}; since = key.time;
            }
        }
        if (!compact) for (int i = 1; i < diagram.ball.keys.size(); i++) {
            Track.Key a = diagram.ball.keys.get(i-1), b = diagram.ball.keys.get(i);
            if (!a.flight) continue;
            Diagram.Shape route = new Diagram.Shape(b.kind);
            route.points.add(diagram.ballKey(a, a.time));
            for (int j = 1; j < b.path.size()-1; j++) route.points.add(b.path.get(j));
            route.points.add(diagram.ballKey(b, b.time));
            paintShape(canvas, route);
        }
        if (drawing != null) paintShape(canvas, drawing);
        for (int i = 0; i < diagram.tokens.size(); i++) paintToken(canvas, diagram.tokens.get(i), i);
        // Names last and all together, so no shirt is ever painted over one — the rule the pitch
        // itself follows, kept here because a schema is read the same way.
        if (!compact) for (Diagram.Token token : diagram.tokens) paintName(canvas, token);
        double[] ball = diagram.ballPosition(time);
        if (ball != null) {
            // At the feet of whoever holds it, on the side he is about to play it: a ball set
            // always to the right says nothing, and says it even where the play goes left. Never
            // below him, though — that is where his name is written, and a name half covered by
            // a ball is worse than a ball on the wrong side. A ball nobody holds sits where it is.
            float reach = diagram.ballHeld(time) ? dp(compact ? 9 : 14) : 0;
            float dx = 0, dy = -1;
            double[] next = reach == 0 ? null : diagram.ballNext(time);
            if (next != null) {
                dx = x(next[0]) - x(ball[0]);
                dy = Math.min(0, y(next[1]) - y(ball[1]));
                if (dx == 0 && dy == 0) dy = -1;
            }
            float away = (float)Math.hypot(dx, dy);
            float bx = Math.max(dp(6), Math.min(getWidth()-dp(6), x(ball[0]) + dx / away * reach));
            float by = Math.max(dp(6), Math.min(getHeight()-dp(6), y(ball[1]) + dy / away * reach));
            paint.setStyle(Paint.Style.FILL); paint.setPathEffect(null);
            paint.setColor(Color.BLACK); canvas.drawCircle(bx, by, dp(6), paint);
            paint.setColor(Color.WHITE); canvas.drawCircle(bx, by, dp(4.6f), paint);
            paint.setColor(Color.BLACK); canvas.drawCircle(bx, by, dp(1.7f), paint);
        }
        if (banding) paintBand(canvas);
    }

    /** The rectangle being drawn round a group, over everything so it is never half hidden. */
    private void paintBand(Canvas canvas) {
        plate.set(Math.min(downX, lastX), Math.min(downY, lastY),
                  Math.max(downX, lastX), Math.max(downY, lastY));
        paint.setPathEffect(null);
        int band = Skin.paler(held);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(34, Color.red(band), Color.green(band), Color.blue(band)));
        canvas.drawRoundRect(plate, dp(6), dp(6), paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f));
        paint.setColor(band);
        paint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
        canvas.drawRoundRect(plate, dp(6), dp(6), paint);
        paint.setPathEffect(null);
    }

    /** Bright and neutral: the shape of the line says what it means, its colour says nothing. */
    private static final int STROKE_COLOUR = Color.rgb(240, 250, 233),
        HALO = Color.argb(165, 2, 18, 12);

    /**
     * Every stroke is drawn twice, dark and wide underneath, bright and narrow on top. A shadow
     * layer would have been shorter to write and is ignored for anything but text on a
     * hardware-accelerated canvas before API 28; an outline drawn on purpose is legible on the
     * pale half of a mown stripe as well as on the dark one, on every version.
     */
    private void paintShape(Canvas canvas, Diagram.Shape shape) {
        java.util.List<double[]> points = shape.points;
        // Kept clear of the players it joins: an arrowhead hidden under the disc it points at
        // turns a pass into a line, and the ball would leave from under the passer.
        Path line = trim(smooth(points),
            clearance(points.get(0)), clearance(points.get(points.size() - 1)));
        measure.setPath(line, false);
        measure.getPosTan(0, position, tangent);
                boolean run = Diagram.RUN.equals(shape.kind);
        Path[] rails = Diagram.SHOT.equals(shape.kind)
            // Two rails rather than one thick line: a shot must not read as a firmer pass.
            ? new Path[]{offset(line, -dp(2.2f)), offset(line, dp(2.2f))}
            // Waved about its own course: a player carrying the ball rather than passing it.
            : new Path[]{Diagram.CARRY.equals(shape.kind) ? wavy(line) : line};
        // Dashed: the universal mark of a move made without the ball.
        DashPathEffect dashes = run ? new DashPathEffect(new float[]{dp(9), dp(6)}, 0) : null;
        float width = dp(compact ? 1.8f : 2.6f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        for (int pass = 0; pass < 2; pass++) {
            paint.setPathEffect(dashes);
            paint.setColor(pass == 0 ? HALO : STROKE_COLOUR);
            paint.setStrokeWidth(pass == 0 ? width + dp(2.4f) : width);
            for (Path rail : rails) canvas.drawPath(rail, paint);
        }
        paint.setPathEffect(null);
        head(canvas, line, width);

    }

    /** How far a stroke stands off a player it was snapped to, or nothing when it stands alone. */
    private float clearance(double[] point) {
        for (Diagram.Token token : diagram.tokens)
            if (Math.abs(spot(token)[0] - point[0]) < 1e-9 && Math.abs(spot(token)[1] - point[1]) < 1e-9)
                return dp(compact ? SHIRT * .34f + 2 : SHIRT * .5f + 3.5f);
        return 0;
    }

    /**
     * The same line with its ends cut back. Never past two fifths from either end, so a stroke
     * drawn between two players standing shoulder to shoulder still has a line left to draw.
     */
    private Path trim(Path line, float from, float to) {
        if (from <= 0 && to <= 0) return line;
        measure.setPath(line, false);
        float length = measure.getLength();
        float start = Math.min(from, length * .4f), end = Math.min(to, length * .4f);
        if (length - start - end <= dp(2)) return line;
        Path cut = new Path();
        measure.getSegment(start, length - end, cut, true);
        return cut;
    }

    /** The arrowhead, aimed along the very end of the line rather than at the last two points. */
    private void head(Canvas canvas, Path line, float width) {
        measure.setPath(line, false);
        float length = measure.getLength();
        if (length <= 0 || !measure.getPosTan(length, position, tangent)) return;
        double angle = Math.atan2(tangent[1], tangent[0]);
        float size = dp(compact ? 7 : 10), spread = (float)Math.toRadians(26);
        path.reset();
        path.moveTo(position[0], position[1]);
        path.lineTo(position[0] - size * (float)Math.cos(angle - spread),
                    position[1] - size * (float)Math.sin(angle - spread));
        path.lineTo(position[0] - size * (float)Math.cos(angle + spread),
                    position[1] - size * (float)Math.sin(angle + spread));
        path.close();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width + dp(2.4f));
        paint.setColor(HALO); canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(STROKE_COLOUR); canvas.drawPath(path, paint);
    }

    /**
     * The polyline a finger left, eased of its tremor and then rounded off. Each remaining point
     * becomes a control point and the curve runs through the midpoints between them: a straight
     * drag stays straight, a curved one keeps its curve, and neither shows the corners of the
     * sampling.
     */
    private Path smooth(java.util.List<double[]> source) {
        java.util.List<double[]> points = Track.eased(source);
        Path line = new Path();
        float px = x(points.get(0)[0]), py = y(points.get(0)[1]);
        line.moveTo(px, py);
        if (points.size() == 2) {
            line.lineTo(x(points.get(1)[0]), y(points.get(1)[1]));
            return line;
        }
        for (int i = 1; i < points.size() - 1; i++) {
            float cx = x(points.get(i)[0]), cy = y(points.get(i)[1]);
            float nx = x(points.get(i+1)[0]), ny = y(points.get(i+1)[1]);
            line.quadTo(cx, cy, (cx + nx) / 2, (cy + ny) / 2);
        }
        int last = points.size() - 1;
        line.lineTo(x(points.get(last)[0]), y(points.get(last)[1]));
        return line;
    }

    /** The same line, made to undulate about its own course. */
    private Path wavy(Path source) {
        measure.setPath(source, false);
        float length = measure.getLength(), step = dp(3.5f), wave = dp(14), amplitude = dp(2.6f);
        Path result = new Path();
        for (float at = 0; at <= length; at += step) {
            if (!measure.getPosTan(at, position, tangent)) break;
            // Fade the wave in and out, so both ends still meet the player they belong to.
            float ends = Math.min(1, Math.min(at, length - at) / dp(10));
            float swing = amplitude * ends * (float)Math.sin(at / wave * 2 * Math.PI);
            float px = position[0] - tangent[1] * swing, py = position[1] + tangent[0] * swing;
            if (at == 0) result.moveTo(px, py); else result.lineTo(px, py);
        }
        return result;
    }

    /** The same line, set aside by a constant distance: one of the two rails of a shot. */
    private Path offset(Path source, float distance) {
        measure.setPath(source, false);
        float length = measure.getLength(), step = dp(3.5f);
        Path result = new Path();
        for (float at = 0; at <= length; at += step) {
            if (!measure.getPosTan(at, position, tangent)) break;
            float px = position[0] - tangent[1] * distance, py = position[1] + tangent[0] * distance;
            if (at == 0) result.moveTo(px, py); else result.lineTo(px, py);
        }
        return result;
    }

    private void paintToken(Canvas canvas, Diagram.Token token, int index) {
        int radius = Math.round(dp(compact ? SHIRT * .68f : SHIRT) / 2);
        int cx = Math.round(x(spot(token)[0])), cy = Math.round(y(spot(token)[1]));
        boolean active = chosen.contains(index);
        Drawable shirt = PitchView.shirt(getContext(), glass, colour(token), active, false, held, heldEnd);
        shirt.setBounds(cx - radius, cy - radius, cx + radius, cy + radius);
        shirt.draw(canvas);
        String number = number(token);
        if (number.isEmpty()) return;
        ink.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        ink.setTextSize(dp(compact ? 6.5f : 9.5f));
        ink.setColor(glass ? colour(token) : Color.rgb(15, 35, 33));
        canvas.drawText(number, cx, cy - (ink.descent() + ink.ascent()) / 2, ink);
    }

    private void paintName(Canvas canvas, Diagram.Token token) {
        String name = name(token);
        if (name.isEmpty()) return;
        int radius = Math.round(dp(SHIRT) / 2);
        float cx = x(spot(token)[0]), cy = y(spot(token)[1]) + radius + dp(NAME_H) / 2f - dp(2);
        ink.setTypeface(Typeface.DEFAULT); ink.setTextSize(dp(10.5f));
        float half = ink.measureText(name) / 2 + dp(NAME_PAD);
        // Kept whole inside the board: a name pushed off the edge is a name nobody reads.
        cx = Math.max(half, Math.min(getWidth() - half, cx));
        plate.set(cx - half, cy - dp(NAME_H) / 2f, cx + half, cy + dp(NAME_H) / 2f);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(178, 6, 28, 22));
        canvas.drawRoundRect(plate, dp(7), dp(7), paint);
        ink.setColor(Color.WHITE);
        canvas.drawText(name, cx, cy - (ink.descent() + ink.ascent()) / 2, ink);
    }

    private float x(double fraction) { return (float)fraction * getWidth(); }
    private float y(double fraction) { return (float)fraction * getHeight(); }

    // ——— Ce que le match sait d'un pion ———

    private JSONObject player(Diagram.Token token) {
        JSONArray players = match == null ? null : match.optJSONArray("players");
        for (int i = 0; players != null && i < players.length(); i++)
            if (token.playerId.equals(players.optJSONObject(i).optString("id")))
                return players.optJSONObject(i);
        return null;
    }
    private int colour(Diagram.Token token) {
        JSONObject player = player(token);
        String side = player != null ? player.optString("team") : token.team;
        JSONArray teams = match == null ? null : match.optJSONArray("teams");
        for (int i = 0; teams != null && i < teams.length(); i++)
            if (side.equals(teams.optJSONObject(i).optString("key")))
                return Color.parseColor(teams.optJSONObject(i).optString("colour"));
        // Nobody the match names: a slate disc, which is exactly what it says about him.
        return Color.rgb(163, 178, 172);
    }
    private String number(Diagram.Token token) {
        JSONObject player = player(token);
        return player != null ? String.valueOf(player.optInt("number")) : token.label;
    }
    private String name(Diagram.Token token) {
        JSONObject player = player(token);
        // Le maillot porte déjà la couleur de l'équipe : l'étiquette ne dit que le joueur.
        return player == null ? "" : PlayerName.shorten(player.optString("name"));
    }

    // ——— Le doigt ———

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!editable || getWidth() == 0) return false;
        float px = event.getX(), py = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                downX = px; downY = py; lastX = px; lastY = py; wandered = false;
                if (tool == ERASE) { rub(px, py); return true; }
                if (tool == BALL) {
                    int near = tokenAt(px, py, GRAB);
                    double[] p = anchor(px, py);
                    Track.Key key = new Track.Key(time, p[0], p[1]);
                    if (near >= 0) key.owner = diagram.tokens.get(near).id;
                    Track.Key old = diagram.ball.at(time);
                    if (old != null) { key.path.addAll(old.path); key.kind = old.kind; }
                    if (!diagram.ball.put(key)) message("Limite de positions du ballon atteinte");
                    if (onChange != null) onChange.run();
                    invalidate(); return true;
                }
                if (tool == MOVE) {
                    holding = tokenAt(px, py, GRAB);
                    toggled = false;
                    // The group is left alone until the finger says what it wants: replacing it
                    // here would make a long press meant to drop one player drop all the others.
                    if (holding >= 0) await(holding); else { banding = true; invalidate(); }
                    return true;
                }
                begin(px, py);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!wandered && Math.hypot(px - downX, py - downY) > dp(TAP)) {
                    wandered = true; forget();
                    // The slop is not travel: the drag starts where the touch stopped being a tap,
                    // and nothing moves before then — a tremor under a held finger is not a drag.
                    lastX = px; lastY = py;
                }
                if (tool == MOVE) {
                    if (banding) { lastX = px; lastY = py; invalidate(); return true; }
                    if (!wandered || holding < 0 || toggled) return true;
                    // Dragging somebody from outside the group means the group was not the point.
                    if (!chosen.contains(holding)) select(holding);
                    shift(px - lastX, py - lastY);
                    lastX = px; lastY = py;
                    invalidate();
                } else if (drawing != null && Math.hypot(px - lastX, py - lastY) >= dp(SAMPLE)) {
                    lastX = px; lastY = py;
                    if (drawing.points.size() < Diagram.POINTS)
                        drawing.points.add(new double[]{px / getWidth(), py / getHeight()});
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && drawing != null) { drawing = null; invalidate(); return true; }
                if (tool == MOVE) {
                    forget();
                    if (banding) {
                        banding = false;
                        // A rectangle nobody drew is a tap on bare grass: it lets everyone go.
                        if (wandered) gather(px, py); else chosen.clear();
                        describe(); performClick();
                    } else if (toggled) {
                        // The long press already said what this touch meant.
                    } else if (!wandered) {
                        // A tap took hold of nobody and moved nobody: one player, or none at all.
                        select(holding); performClick();
                    } else if (holding >= 0 && onChange != null) {
                        onChange.run();
                    }
                    holding = -1; invalidate();
                } else {
                    finish(px, py);
                }
                return true;
        }
        return false;
    }

    /** Held still on a player long enough, the touch stops being a tap and becomes a correction. */
    private void await(int index) {
        forget();
        pending = () -> {
            pending = null; toggled = true; holding = -1;
            if (!chosen.remove(index)) chosen.add(index);
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            describe(); invalidate(); performClick();
        };
        press.postDelayed(pending, ViewConfiguration.getLongPressTimeout());
    }

    private void forget() {
        if (pending != null) press.removeCallbacks(pending);
        pending = null;
    }

    @Override protected void onDetachedFromWindow() {
        forget();
        super.onDetachedFromWindow();
    }

    /** A tap or a rectangle changed who is held; the panel below is told to say so. */
    @Override public boolean performClick() {
        super.performClick();
        if (onSelect != null) onSelect.accept(selected());
        return true;
    }

    private void begin(float px, float py) {
        if (time + (travel == AUTO ? BRIEF : travel) > Track.END) {
            message("La séquence est limitée à 120 secondes"); return;
        }
        actor = tokenAt(px, py, SNAP);
        if (actor < 0 && Diagram.RUN.equals(stroke)) {
            message("Commencez le déplacement sur un joueur"); return;
        }
        drawing = new Diagram.Shape(stroke);
        drawing.points.add(anchor(px, py));
    }

    private void finish(float px, float py) {
        Diagram.Shape shape = drawing;
        drawing = null;
        if (shape == null) return;
        int receiver = tokenAt(px, py, SNAP);
        boolean moving = Diagram.RUN.equals(shape.kind);
        // A run ends where the finger let go, and nowhere else. Only the ball is aimed at
        // somebody: « vers Yassine » is a pass to Yassine, and it must leave from his feet at the
        // instant he receives it. A player running past a team-mate did not run into him, and
        // dropping him on the very spot puts two shirts on one blade of grass.
        double[] end = moving ? point(px, py) : anchor(px, py);
        double[] start = shape.points.get(0);
        if (Math.hypot((end[0] - start[0]) * getWidth(), (end[1] - start[1]) * getHeight()) < dp(STROKE)) {
            invalidate(); return;
        }
        // The finger's last sample is not where it let go: the end is written from the release.
        if (shape.points.size() >= Diagram.POINTS) shape.points.remove(shape.points.size() - 1);
        shape.points.add(end);
        // How long the trip lasts: what the toolbar was told, or what the stroke itself says.
        // Read before the end is snapped to a shirt, the snap being worth a dozen dp and the
        // arrival's whereabouts depending on the duration we are working out.
        int travel = this.travel == AUTO ? pace(shape) : this.travel;
        if (time + travel > Track.END) {
            message("La séquence est limitée à 120 secondes"); invalidate(); return;
        }
        if (receiver >= 0 && (Diagram.PASS.equals(shape.kind) || Diagram.SHOT.equals(shape.kind))) {
            end = diagram.position(diagram.tokens.get(receiver), time + travel);
            shape.points.set(shape.points.size() - 1, end);
        }
        Track track = moving ? diagram.tokens.get(actor).track : diagram.ball;
        if (!room(track, time, time + travel)) {
            message("Des positions existent déjà sur cet intervalle, ou la piste est pleine"); invalidate(); return;
        }
        Track.Key from = new Track.Key(time, start[0], start[1]);
        Track.Key to = new Track.Key(time + travel, end[0], end[1]);
        to.path.addAll(shape.points);
        Track.Key existing = track.at(time);
        if (existing != null) { from.path.addAll(existing.path); from.kind = existing.kind; }
        if (moving) {
            // Nothing is said about the ball: whoever held it holds it still, and follows.
            track.put(from); track.put(to);
        } else {
            to.kind = shape.kind;
            if (actor >= 0) from.owner = diagram.tokens.get(actor).id;
            from.flight = true;
            if (receiver >= 0 && Diagram.PASS.equals(shape.kind)) to.owner = diagram.tokens.get(receiver).id;
            track.put(from); track.put(to);
        }
        setTime(time + travel);
        if (actor >= 0) select(actor);
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        describe(); invalidate();
        if (onChange != null) onChange.run();
    }

    private boolean room(Track track, int start, int end) {
        int added = (track.at(start) == null ? 1 : 0) + (track.at(end) == null ? 1 : 0);
        if (track.keys.size() + added > Track.LIMIT) return false;
        for (Track.Key key : track.keys) if (key.time > start && key.time < end) return false;
        return true;
    }

    /**
     * The duration the drawing asks for: its length on the grass, at the pace that kind of trip
     * is played at. The length is the one that will be travelled — the eased polyline, not the
     * straight line between the ends — so a run round an opponent takes the time it goes.
     */
    private int pace(Diagram.Shape shape) {
        java.util.List<double[]> points = Track.eased(shape.points);
        double metres = 0;
        for (int i = 1; i < points.size(); i++) {
            double across = (points.get(i)[0] - points.get(i-1)[0]) * WIDTH;
            double along = (points.get(i)[1] - points.get(i-1)[1]) * LENGTH;
            metres += Math.hypot(across, along);
        }
        double speed = Diagram.SHOT.equals(shape.kind) ? STRUCK
            : Diagram.RUN.equals(shape.kind) ? RUNNING : PASSED;
        return (int)Math.max(BRIEF, Math.min(Track.END, Math.round(metres / speed * 10)));
    }

    /** Where a stroke starts, and where a ball's stroke ends: on the player standing there. */
    private double[] anchor(float px, float py) {
        int near = tokenAt(px, py, SNAP);
        return near >= 0 ? spot(diagram.tokens.get(near)) : point(px, py);
    }

    /** The bare spot under the finger, on the grass and nobody's. */
    private double[] point(float px, float py) {
        return new double[]{Math.max(0, Math.min(1, px / getWidth())),
                            Math.max(0, Math.min(1, py / getHeight()))};
    }

    /** A token first, because it is what a finger aimed at; a stroke only if none was there. */
    private void rub(float px, float py) {
        int token = tokenAt(px, py, GRAB);
        if (token >= 0) {
            diagram.removeToken(token);
            // Removing one renumbers the rest: holding on to indices past it would hold the wrong men.
            chosen.clear();
        } else {
            int shape = shapeAt(px, py);
            if (shape >= 0) diagram.shapes.remove(shape);
            else if (!rubRoute(px, py)) return;
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        describe(); invalidate();
        if (onChange != null) onChange.run();
    }

    private boolean rubRoute(float px, float py) {
        for (int i = diagram.ball.keys.size()-1; i > 0; i--) {
            Track.Key a = diagram.ball.keys.get(i-1), b = diagram.ball.keys.get(i);
            if (a.flight && nearRoute(px, py, diagram.ballKey(a, a.time), diagram.ballKey(b, b.time), b.path)) {
                a.flight = false; diagram.ball.keys.remove(i); return true;
            }
        }
        for (Diagram.Token token : diagram.tokens) {
            for (int i = token.track.keys.size()-1; i >= 0; i--) {
                Track.Key b = token.track.keys.get(i);
                if (b.time == 0) continue;
                double[] a = i == 0 ? new double[]{token.x, token.y}
                    : new double[]{token.track.keys.get(i-1).x, token.track.keys.get(i-1).y};
                if (nearRoute(px, py, a, new double[]{b.x, b.y}, b.path)) {
                    token.track.keys.remove(i); return true;
                }
            }
        }
        return false;
    }

    private boolean nearRoute(float px, float py, double[] a, double[] b, java.util.List<double[]> route) {
        for (int i = 0; i <= 100; i++) {
            double[] p = Track.route(a[0], a[1], b[0], b[1], route, i / 100.0);
            if (Math.hypot(x(p[0])-px, y(p[1])-py) <= dp(RUBBER)) return true;
        }
        return false;
    }

    /**
     * Everyone held moves by the same amount. The travel is clamped once for the whole group
     * rather than each player against the touchline on his own: a back four pushed towards the
     * corner must keep its shape instead of being squeezed flat against the edge.
     */
    private void shift(float dx, float dy) {
        java.util.Collection<Integer> moving = chosen.isEmpty()
            ? java.util.Collections.singleton(holding) : chosen;
        double mx = dx / getWidth(), my = dy / getHeight();
        for (int index : moving) {
            Diagram.Token token = diagram.tokens.get(index);
            double[] bounds = token.bounds();
            mx = Math.max(-bounds[0], Math.min(1 - bounds[2], mx));
            my = Math.max(-bounds[1], Math.min(1 - bounds[3], my));
        }
        for (int index : moving) diagram.tokens.get(index).reposition(mx, my);
    }

    /** Everyone the rectangle closed on, by the spot he stands on rather than by his shirt. */
    private void gather(float px, float py) {
        chosen.clear();
        float left = Math.min(downX, px), right = Math.max(downX, px);
        float top = Math.min(downY, py), bottom = Math.max(downY, py);
        for (int i = 0; i < diagram.tokens.size(); i++) {
            Diagram.Token token = diagram.tokens.get(i);
            float tx = x(spot(token)[0]), ty = y(spot(token)[1]);
            if (tx >= left && tx <= right && ty >= top && ty <= bottom) chosen.add(i);
        }
    }

    /** The nearest token under the finger, latest first: the one drawn on top is the one grabbed. */
    private int tokenAt(float px, float py, int reach) {
        int found = -1;
        double best = dp(reach);
        for (int i = diagram.tokens.size() - 1; i >= 0; i--) {
            Diagram.Token token = diagram.tokens.get(i);
            double distance = Math.hypot(x(spot(token)[0]) - px, y(spot(token)[1]) - py);
            if (distance <= best) { best = distance; found = i; }
        }
        return found;
    }

    /** The stroke passing closest to the finger, measured on the points it was drawn from. */
    private int shapeAt(float px, float py) {
        int found = -1;
        double best = dp(RUBBER);
        for (int i = diagram.shapes.size() - 1; i >= 0; i--) {
            for (double[] point : diagram.shapes.get(i).points) {
                double distance = Math.hypot(x(point[0]) - px, y(point[1]) - py);
                if (distance <= best) { best = distance; found = i; }
            }
        }
        return found;
    }
}
