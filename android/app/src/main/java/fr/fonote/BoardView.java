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
 * <p>Both ends of a stroke snap to whoever is standing near them, so "from Pogba to Mbappé" is
 * exact without aiming at a pixel, and stays exact when either of them is dragged afterwards
 * is not claimed: a stroke keeps the coordinates it was drawn with, because a schema records a
 * moment rather than a live link.
 *
 * <p>Meaning is carried by the line and never by its colour: colour on this pitch already says
 * which team a shirt belongs to, and a second meaning laid over it would make both unreadable.
 */
final class BoardView extends View {
    /** What the next drag will do. */
    static final int MOVE = 0, DRAW = 1, ERASE = 2;
    private static final int SHIRT = 32, NAME_H = 15, NAME_PAD = 5;
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

    BoardView(Context context, JSONObject match, boolean glass, boolean editable, boolean compact) {
        super(context);
        this.match = match; this.glass = glass; this.editable = editable; this.compact = compact;
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
        if (drawing != null) paintShape(canvas, drawing);
        for (int i = 0; i < diagram.tokens.size(); i++) paintToken(canvas, diagram.tokens.get(i), i);
        // Names last and all together, so no shirt is ever painted over one — the rule the pitch
        // itself follows, kept here because a schema is read the same way.
        if (!compact) for (Diagram.Token token : diagram.tokens) paintName(canvas, token);
        if (banding) paintBand(canvas);
    }

    /** The rectangle being drawn round a group, over everything so it is never half hidden. */
    private void paintBand(Canvas canvas) {
        plate.set(Math.min(downX, lastX), Math.min(downY, lastY),
                  Math.max(downX, lastX), Math.max(downY, lastY));
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb(34, 213, 255, 170));
        canvas.drawRoundRect(plate, dp(6), dp(6), paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f));
        paint.setColor(Color.rgb(213, 255, 170));
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
        float ballX = position[0], ballY = position[1];
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
        // The ball leaves from somewhere: a run is the one stroke made without it.
        if (run) return;
        float ball = dp(compact ? 2.6f : 3.6f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(HALO); canvas.drawCircle(ballX, ballY, ball + dp(1.2f), paint);
        paint.setColor(STROKE_COLOUR); canvas.drawCircle(ballX, ballY, ball, paint);
    }

    /** How far a stroke stands off a player it was snapped to, or nothing when it stands alone. */
    private float clearance(double[] point) {
        for (Diagram.Token token : diagram.tokens)
            if (Math.abs(token.x - point[0]) < 1e-9 && Math.abs(token.y - point[1]) < 1e-9)
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
     * The polyline a finger left, rounded off. Each recorded point becomes a control point and
     * the curve runs through the midpoints between them: a straight drag stays straight, a
     * curved one keeps its curve, and neither shows the corners of the sampling.
     */
    private Path smooth(java.util.List<double[]> points) {
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
        int cx = Math.round(x(token.x)), cy = Math.round(y(token.y));
        boolean active = chosen.contains(index);
        Drawable shirt = PitchView.shirt(getContext(), glass, colour(token), active, false);
        shirt.setBounds(cx - radius, cy - radius, cx + radius, cy + radius);
        shirt.draw(canvas);
        String number = number(token);
        if (number.isEmpty()) return;
        ink.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        ink.setTextSize(dp(compact ? 8.5f : 12.5f));
        ink.setColor(glass ? colour(token) : Color.rgb(15, 35, 33));
        canvas.drawText(number, cx, cy - (ink.descent() + ink.ascent()) / 2, ink);
    }

    private void paintName(Canvas canvas, Diagram.Token token) {
        String name = name(token);
        if (name.isEmpty()) return;
        int radius = Math.round(dp(SHIRT) / 2);
        float cx = x(token.x), cy = y(token.y) + radius + dp(NAME_H) / 2f - dp(2);
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
        return player != null ? PlayerName.shorten(player.optString("name")) : "";
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
        if (diagram.shapes.size() >= Diagram.SHAPES) return;
        drawing = new Diagram.Shape(stroke);
        drawing.points.add(anchor(px, py));
    }

    private void finish(float px, float py) {
        Diagram.Shape shape = drawing;
        drawing = null;
        if (shape == null) return;
        double[] end = anchor(px, py);
        double[] start = shape.points.get(0);
        if (Math.hypot((end[0] - start[0]) * getWidth(), (end[1] - start[1]) * getHeight()) < dp(STROKE)) {
            invalidate(); return;
        }
        // The finger's last sample is not where it let go: the end is written from the release.
        if (shape.points.size() >= Diagram.POINTS) shape.points.remove(shape.points.size() - 1);
        shape.points.add(end);
        diagram.shapes.add(shape);
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        describe(); invalidate();
        if (onChange != null) onChange.run();
    }

    /** Where a stroke really starts and ends: on the player standing there, if one is. */
    private double[] anchor(float px, float py) {
        int near = tokenAt(px, py, SNAP);
        if (near >= 0) {
            Diagram.Token token = diagram.tokens.get(near);
            return new double[]{token.x, token.y};
        }
        return new double[]{Math.max(0, Math.min(1, px / getWidth())),
                            Math.max(0, Math.min(1, py / getHeight()))};
    }

    /** A token first, because it is what a finger aimed at; a stroke only if none was there. */
    private void rub(float px, float py) {
        int token = tokenAt(px, py, GRAB);
        if (token >= 0) {
            diagram.tokens.remove(token);
            // Removing one renumbers the rest: holding on to indices past it would hold the wrong men.
            chosen.clear();
        } else {
            int shape = shapeAt(px, py);
            if (shape < 0) return;
            diagram.shapes.remove(shape);
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        describe(); invalidate();
        if (onChange != null) onChange.run();
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
            mx = Math.max(-token.x, Math.min(1 - token.x, mx));
            my = Math.max(-token.y, Math.min(1 - token.y, my));
        }
        for (int index : moving) {
            Diagram.Token token = diagram.tokens.get(index);
            token.x += mx; token.y += my;
        }
    }

    /** Everyone the rectangle closed on, by the spot he stands on rather than by his shirt. */
    private void gather(float px, float py) {
        chosen.clear();
        float left = Math.min(downX, px), right = Math.max(downX, px);
        float top = Math.min(downY, py), bottom = Math.max(downY, py);
        for (int i = 0; i < diagram.tokens.size(); i++) {
            Diagram.Token token = diagram.tokens.get(i);
            float tx = x(token.x), ty = y(token.y);
            if (tx >= left && tx <= right && ty >= top && ty <= bottom) chosen.add(i);
        }
    }

    /** The nearest token under the finger, latest first: the one drawn on top is the one grabbed. */
    private int tokenAt(float px, float py, int reach) {
        int found = -1;
        double best = dp(reach);
        for (int i = diagram.tokens.size() - 1; i >= 0; i--) {
            Diagram.Token token = diagram.tokens.get(i);
            double distance = Math.hypot(x(token.x) - px, y(token.y) - py);
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
