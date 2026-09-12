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
    /**
     * The instant being drawn. It equals {@link #time} while editing; playback moves it forward
     * between two tenths, on every frame, so that players glide instead of jumping.
     */
    private double moment;
    int time() { return time; }
    double moment() { return moment; }
    void setTime(int value) { time = Math.max(0, Math.min(Track.END, value)); moment = time; invalidate(); }
    void setMoment(double value) {
        moment = Math.max(0, Math.min(Track.END, value)); time = (int)Math.floor(moment); invalidate();
    }
    int travel() { return travel; }
    void setTravel(int value) { travel = value; }
    private double[] spot(Diagram.Token token) { return diagram.position(token, moment); }
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
    /** What a finger can draw in a single gesture before it is reduced: several times the pitch. */
    private static final int GESTURE = 600;

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
    private Runnable onCancel;
    private java.util.function.Consumer<String> onMotion;
    private String selectedMotion = "";
    private boolean translateAll, replacing, redraw;
    private Track.Key handle;
    void editing(Runnable cancel, java.util.function.Consumer<String> motion) { onCancel = cancel; onMotion = motion; }
    /** The pitch's dialogs take the theme's colours, like the activity's. */
    private java.util.function.Supplier<android.app.AlertDialog.Builder> dialogs;
    void dialogs(java.util.function.Supplier<android.app.AlertDialog.Builder> made) { dialogs = made; }
    void selectMotion(String id) { selectedMotion = id; invalidate(); }
    String selectedMotion() { return selectedMotion; }
    void translateAll(boolean value) { translateAll = value; }
    boolean translatesAll() { return translateAll; }
    void redrawMotion() { redraw = true; tool = DRAW; }
    private Sequence sequence() { return new Sequence(diagram); }
    private void cancelGesture() {
        forget(); drawing = null; handle = null; redraw = false; banding = false;
        if (onCancel != null) onCancel.run();
    }
    private Sequence.Motion motionAt(float px, float py) {
        java.util.List<Sequence.Motion> motions = sequence().motions();
        for (int i = motions.size()-1; i >= 0; i--) {
            Sequence.Motion motion = motions.get(i);
            double[] a = sequence().point(motion, false), b = sequence().point(motion, true);
            if (nearRoute(px, py, a, b, motion.end.path)) return motion;
        }
        return null;
    }

    BoardView(Context context, JSONObject match, boolean glass, int held, int heldEnd, int lawn,
              int lawnEnd, boolean editable, boolean compact) {
        super(context);
        this.match = match; this.glass = glass; this.held = held; this.heldEnd = heldEnd;
        this.editable = editable; this.compact = compact;
        grass = new Pitch(context);
        setBackground(Pitch.turf(lawn, lawnEnd, compact ? 12 : 20,
            context.getResources().getDisplayMetrics().density));
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
    void setTool(int tool) { this.tool = tool; redraw = false; handle = null; invalidate(); }
    void setStroke(String kind) { this.stroke = kind; this.tool = DRAW; invalidate(); }
    /** The one player being written about, or -1 when nobody or a whole group is held. */
    int selected() { chosen(); return chosen.size() == 1 ? chosen.iterator().next() : -1; }
    /** Everyone held, in the order they stand on the board. */
    java.util.List<Integer> chosen() {
        chosen.removeIf(index -> index < 0 || index >= diagram.tokens.size());
        java.util.List<Integer> result = new java.util.ArrayList<>(chosen);
        java.util.Collections.sort(result);
        return result;
    }
    void selectIndices(java.util.List<Integer> indices) {
        chosen.clear(); for (int index : indices) if (index >= 0 && index < diagram.tokens.size()) chosen.add(index);
        invalidate();
    }
    void select(int index) {
        selectedMotion = "";
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
                Diagram.Shape route = new Diagram.Shape(Diagram.TACKLE.equals(key.kind) ? Diagram.TACKLE
                    : diagram.carrying(token, since, key.time) ? Diagram.CARRY : Diagram.RUN);
                route.points.add(previous);
                for (int j = 1; j < key.path.size()-1; j++) route.points.add(key.path.get(j));
                route.points.add(new double[]{key.x, key.y});
                route.baked = key.baked;
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
            route.baked = b.baked;
            paintShape(canvas, route);
        }
        if (drawing != null) paintShape(canvas, drawing);
        Sequence.Motion selected = sequence().motion(selectedMotion);
        if (selected != null && !compact) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3));
            paint.setPathEffect(null); paint.setColor(0xffff9800);
            for (boolean end : new boolean[]{false, true}) {
                double[] p = sequence().point(selected, end);
                canvas.drawCircle(x(p[0]), y(p[1]), dp(12), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        for (int i = 0; i < diagram.tokens.size(); i++) paintToken(canvas, diagram.tokens.get(i), i);
        // Names last and all together, so no shirt is ever painted over one — the rule the pitch
        // itself follows, kept here because a schema is read the same way.
        if (!compact) for (Diagram.Token token : diagram.tokens) paintName(canvas, token);
        double[] ball = diagram.ballPosition(moment);
        if (ball != null) {
            // At the feet of whoever holds it, on the side he is about to play it: a ball set
            // always to the right says nothing, and says it even where the play goes left. Never
            // below him, though — that is where his name is written, and a name half covered by
            // a ball is worse than a ball on the wrong side. A ball nobody holds sits where it is.
            float reach = dp(compact ? 9 : 14), ox = 0, oy = 0;
            if (diagram.ballHeld(moment)) {
                float[] side = feet(moment); ox = side[0] * reach; oy = side[1] * reach;
            } else {
                // In flight, the ball leaves the passer's feet and reaches the receiver's
                // gradually: the offset fades out at the start and comes back on arrival,
                // instead of jumping fourteen dp at once.
                Track.Key[] leg = diagram.ballLeg(moment);
                if (leg != null) {
                    double fade = Math.min(3, (leg[1].time - leg[0].time) / 3.0);
                    double leaving = 1 - (moment - leg[0].time) / fade;
                    double landing = 1 - (leg[1].time - moment) / fade;
                    if (!leg[0].owner.isEmpty() && leaving > 0) {
                        float[] side = feet(leg[0].time); float w = (float)(reach * leaving);
                        ox += side[0] * w; oy += side[1] * w;
                    }
                    if (!leg[1].owner.isEmpty() && landing > 0) {
                        float[] side = feet(leg[1].time); float w = (float)(reach * landing);
                        ox += side[0] * w; oy += side[1] * w;
                    }
                }
            }
            float bx = Math.max(dp(6), Math.min(getWidth()-dp(6), x(ball[0]) + ox));
            float by = Math.max(dp(6), Math.min(getHeight()-dp(6), y(ball[1]) + oy));
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
        Path line = trim(smooth(points, shape.baked),
            clearance(points.get(0)), clearance(points.get(points.size() - 1)));
        measure.setPath(line, false);
        measure.getPosTan(0, position, tangent);
        boolean tackle = Diagram.TACKLE.equals(shape.kind), run = tackle || Diagram.RUN.equals(shape.kind);
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
        if (tackle) cross(canvas, line, width); else head(canvas, line, width);
    }

    /** The end of a tackle: a cross at the contact, where a run would end in an arrowhead. */
    private void cross(Canvas canvas, Path line, float width) {
        measure.setPath(line, false);
        float length = measure.getLength();
        if (length <= 0 || !measure.getPosTan(length, position, tangent)) return;
        float size = dp(compact ? 4 : 6);
        path.reset();
        path.moveTo(position[0] - size, position[1] - size); path.lineTo(position[0] + size, position[1] + size);
        path.moveTo(position[0] + size, position[1] - size); path.lineTo(position[0] - size, position[1] + size);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(width + dp(3.4f)); paint.setColor(HALO); canvas.drawPath(path, paint);
        paint.setStrokeWidth(width + dp(1)); paint.setColor(STROKE_COLOUR); canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
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
    private Path smooth(java.util.List<double[]> source, boolean baked) {
        java.util.List<double[]> points = baked ? source : Track.eased(source);
        Path line = new Path(); line.moveTo(x(points.get(0)[0]), y(points.get(0)[1]));
        for (int i = 1; i < points.size(); i++) line.lineTo(x(points.get(i)[0]), y(points.get(i)[1]));
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

    /** The side of the feet the ball sits on at this instant: towards where it will be played, never under them. */
    private float[] feet(double at) {
        double[] here = diagram.ballPosition(at), next = diagram.ballNext(at);
        float dx = 0, dy = -1;
        if (next != null) {
            dx = x(next[0]) - x(here[0]);
            dy = Math.min(0, y(next[1]) - y(here[1]));
            if (dx == 0 && dy == 0) dy = -1;
        }
        float away = (float)Math.hypot(dx, dy);
        return new float[]{dx / away, dy / away};
    }

    private void paintToken(Canvas canvas, Diagram.Token token, int index) {
        int radius = Math.round(dp(compact ? SHIRT * .68f : SHIRT) / 2);
        // Sub-pixel: rounded to whole pixels, a slow player moves in jerks during playback.
        float cx = x(spot(token)[0]), cy = y(spot(token)[1]);
        boolean active = chosen.contains(index);
        Drawable shirt = PitchView.shirt(getContext(), glass, colour(token), active, false, held, heldEnd);
        shirt.setBounds(-radius, -radius, radius, radius);
        canvas.save(); canvas.translate(cx, cy); shirt.draw(canvas); canvas.restore();
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

    // ——— What the match knows about a counter ———

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
        // The shirt already carries the team's colour: the label only names the player.
        return player == null ? "" : PlayerName.shorten(player.optString("name"));
    }

    // ——— The finger ———

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!editable || getWidth() == 0) return false;
        float px = event.getX(), py = event.getY();
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { cancelGesture(); return true; }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                downX = px; downY = py; lastX = px; lastY = py; wandered = false;
                if (tool == ERASE) { rub(px, py); return true; }
                if (tool == BALL) {
                    int near = tokenAt(px, py, GRAB);
                    double[] p = anchor(px, py);
                    Track.Key key;
                    try { key = sequence().fixBall(time); }
                    catch (IllegalArgumentException failure) { message(failure.getMessage()); cancelGesture(); return true; }
                    key.x = p[0]; key.y = p[1]; key.owner = "";
                    if (near >= 0) key.owner = diagram.tokens.get(near).id;
                    Track.Key old = diagram.ball.at(time);
                    if (old != null && old != key) { key.path.addAll(old.path); key.baked = old.baked; key.kind = old.kind; }
                    if (!diagram.ball.put(key)) message("Limite de positions du ballon atteinte");
                    if (onChange != null) onChange.run();
                    invalidate(); return true;
                }
                if (tool == MOVE) {
                    handle = null;
                    Sequence.Motion selected = sequence().motion(selectedMotion);
                    if (selected != null) {
                        for (boolean end : new boolean[]{false, true}) {
                            double[] p = sequence().point(selected, end);
                            if (Math.hypot(x(p[0])-px, y(p[1])-py) <= dp(20)) {
                                handle = end ? selected.end : selected.start; return true;
                            }
                        }
                    }
                    holding = tokenAt(px, py, GRAB);
                    toggled = false;
                    // The group is left alone until the finger says what it wants: replacing it
                    // here would make a long press meant to drop one player drop all the others.
                    if (holding >= 0) await(holding); else { banding = true; invalidate(); }
                    return true;
                }
                if (redraw) {
                    Sequence.Motion selected = sequence().motion(selectedMotion);
                    if (selected != null) {
                        drawing = new Diagram.Shape(selected.actor.equals("ball") ? selected.end.kind : Diagram.RUN);
                        drawing.points.add(sequence().point(selected, false)); return true;
                    }
                    redraw = false;
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
                    if (handle != null) {
                        if (wandered) {
                            double[] p = point(px, py); handle.x = p[0]; handle.y = p[1];
                            Sequence.Motion selected = sequence().motion(selectedMotion);
                            if (selected != null) {
                                if (selected.track == diagram.ball) handle.owner = "";
                                if (!selected.track.keys.contains(handle) && !selected.track.put(handle)) {
                                    message("Limite de positions atteinte"); cancelGesture(); return true;
                                }
                                if (handle.time == 0 && !selected.actor.equals("ball")) {
                                    Diagram.Token token = sequence().token(selected.actor); token.x = handle.x; token.y = handle.y;
                                }
                            }
                            invalidate();
                        }
                        return true;
                    }
                    if (banding) { lastX = px; lastY = py; invalidate(); return true; }
                    if (!wandered || holding < 0 || toggled) return true;
                    // Dragging somebody from outside the group means the group was not the point.
                    if (!chosen.contains(holding)) select(holding);
                    try { shift(px - lastX, py - lastY); }
                    catch (IllegalArgumentException failure) { message(failure.getMessage()); cancelGesture(); holding = -1; return true; }
                    lastX = px; lastY = py;
                    invalidate();
                } else if (drawing != null && Math.hypot(px - lastX, py - lastY) >= dp(SAMPLE)) {
                    lastX = px; lastY = py;
                    // The whole gesture, not just its first thirty-two samples: it is brought
                    // down to the log's limit on release, without cutting anything off its end.
                    if (drawing.points.size() < GESTURE) drawing.points.add(point(px, py));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && drawing != null) { drawing = null; invalidate(); return true; }
                if (tool == MOVE) {
                    forget();
                    if (handle != null) {
                        handle = null;
                        if (wandered && onChange != null) onChange.run();
                        return true;
                    }
                    if (banding) {
                        banding = false;
                        // A rectangle nobody drew is a tap on bare grass: it lets everyone go.
                        if (wandered) gather(px, py);
                        else {
                            Sequence.Motion motion = motionAt(px, py);
                            if (motion != null) {
                                selectedMotion = motion.id();
                                if (onMotion != null) onMotion.accept(selectedMotion);
                            } else { chosen.clear(); selectedMotion = ""; }
                        }
                        describe(); performClick();
                    } else if (toggled) {
                        // The long press already said what this touch meant.
                    } else if (!wandered) {
                        // A tap took hold of nobody and moved nobody: one player, or none at all.
                        select(holding); performClick();
                    } else if (holding >= 0 && onChange != null) {
                        translateAll = false; onChange.run();
                    }
                    holding = -1; invalidate();
                } else {
                    if (redraw && drawing != null) {
                        Sequence.Motion selected = sequence().motion(selectedMotion);
                        if (selected != null) {
                            drawing.points.add(sequence().point(selected, true));
                            selected.end.path.clear();
                            selected.end.path.addAll(Track.thinned(drawing.points, Diagram.POINTS));
                            selected.end.baked = false;
                        }
                        drawing = null; redraw = false; tool = MOVE;
                        if (onChange != null) onChange.run();
                    } else finishSafely(px, py);
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
        if (actor < 0 && Diagram.TACKLE.equals(stroke)) {
            message("Commencez le tacle sur le joueur qui tacle"); return;
        }
        drawing = new Diagram.Shape(stroke);
        drawing.points.add(anchor(px, py));
    }

    private void finishSafely(float px, float py) {
        try { finish(px, py); }
        catch (IllegalArgumentException failure) { message(failure.getMessage()); cancelGesture(); }
    }
    private void finish(float px, float py) {
        Diagram.Shape shape = drawing;
        drawing = null;
        if (shape == null) return;
        // The stroke as the finger left it: a confirmation picks it up from here, without the
        // arrival already added for the preview.
        java.util.List<double[]> drawn = new java.util.ArrayList<>(shape.points);
        int receiver = tokenAt(px, py, SNAP);
        boolean tackle = Diagram.TACKLE.equals(shape.kind);
        boolean moving = tackle || Diagram.RUN.equals(shape.kind);
        if (tackle && (receiver < 0 || receiver == actor)) {
            message("Terminez le tacle sur le joueur visé"); invalidate(); return;
        }
        // A run ends where the finger let go, and nowhere else. Only the ball is aimed at
        // somebody: « vers Yassine » is a pass to Yassine, and it must leave from his feet at the
        // instant he receives it. A player running past a team-mate did not run into him, and
        // dropping him on the very spot puts two shirts on one blade of grass.
        double[] end = moving || Diagram.SHOT.equals(shape.kind) ? point(px, py) : anchor(px, py);
        double[] start = shape.points.get(0);
        if (Math.hypot((end[0] - start[0]) * getWidth(), (end[1] - start[1]) * getHeight()) < dp(STROKE)) {
            invalidate(); return;
        }
        // The finger's last sample is not where it let go: the end is written from the release.
        shape.points.add(end);
        // How long the trip lasts: what the toolbar was told, or what the stroke itself says.
        // Read before the end is snapped to a shirt, the snap being worth a dozen dp and the
        // arrival's whereabouts depending on the duration we are working out.
        int travel = this.travel == AUTO ? pace(shape) : this.travel;
        java.util.List<double[]> kept = Track.thinned(shape.points, Diagram.POINTS);
        shape.points.clear(); shape.points.addAll(kept);
        if (time + travel > Track.END) {
            message("La séquence est limitée à 120 secondes"); invalidate(); return;
        }
        if (receiver >= 0 && Diagram.PASS.equals(shape.kind)) {
            end = diagram.position(diagram.tokens.get(receiver), time + travel);
            shape.points.set(shape.points.size() - 1, end);
        }
        if (tackle) {
            // In contact with the targeted player where they will be, not on top of them: two
            // shirts touch, they do not overlap.
            double[] target = diagram.position(diagram.tokens.get(receiver), time + travel);
            double gx = (start[0] - target[0]) * getWidth(), gy = (start[1] - target[1]) * getHeight();
            double gap = Math.hypot(gx, gy), reach = Math.min(dp(SHIRT) * .8, gap / 2);
            end = gap == 0 ? target : new double[]{target[0] + gx / gap * reach / getWidth(),
                                                    target[1] + gy / gap * reach / getHeight()};
            shape.points.set(shape.points.size() - 1, end);
        }
        Track track = moving ? diagram.tokens.get(actor).track : diagram.ball;
        boolean overlaps = false;
        for (Sequence.Motion motion : sequence().motions())
            if (motion.track == track && motion.start.time < time+travel && motion.end.time > time) overlaps = true;
        if ((!room(track, time, time+travel) || overlaps) && !replacing) {
            drawing = shape; invalidate();
            (dialogs != null ? dialogs.get() : new android.app.AlertDialog.Builder(getContext())).setTitle("Remplacer ce mouvement ?")
                .setMessage("Le tracé affiché remplacera la portion de " + (time/10.0) + " à " + ((time+travel)/10.0) + " s.")
                .setNegativeButton("Annuler", (d, w) -> { drawing = null; invalidate(); })
                .setOnCancelListener(d -> { drawing = null; invalidate(); })
                .setPositiveButton("Remplacer", (d, w) -> {
                    shape.points.clear(); shape.points.addAll(drawn);
                    replacing = true; finishSafely(px, py); replacing = false;
                }).show();
            return;
        }
        if (moving) { sequence().fix(diagram.tokens.get(actor), time); sequence().fix(diagram.tokens.get(actor), time+travel); }
        else { sequence().fixBall(time); sequence().fixBall(time+travel); }
        for (Track.Key key : new java.util.ArrayList<>(track.keys))
            if (key.time > time && key.time < time+travel) sequence().remove(track, key);
        Track.Key from = new Track.Key(time, start[0], start[1]);
        Track.Key to = new Track.Key(time + travel, end[0], end[1]);
        to.path.addAll(shape.points);
        Track.Key existing = track.at(time);
        if (existing != null) { from.path.addAll(existing.path); from.baked = existing.baked; from.kind = existing.kind; }
        if (moving) {
            // Nothing is said about the ball: whoever held it holds it still, and follows.
            if (tackle) to.kind = Diagram.TACKLE;
            track.put(from); track.put(to);
            // A tackle on the carrier takes the ball from them: it follows the tackler from contact on.
            if (tackle && diagram.tokens.get(receiver).id.equals(diagram.holder(time + travel))) {
                Track.Key won = sequence().fixBall(time + travel);
                won.owner = diagram.tokens.get(actor).id; won.flight = false; won.x = end[0]; won.y = end[1];
            }
        } else {
            to.kind = shape.kind;
            if (actor >= 0) from.owner = diagram.tokens.get(actor).id;
            from.flight = true;
            if (receiver >= 0 && Diagram.PASS.equals(shape.kind)) to.owner = diagram.tokens.get(receiver).id;
            track.put(from); track.put(to);
        }
        selectedMotion = to.id;
        // The cursor moves to the arrival: the rest of the move starts from there. A simultaneous
        // run is drawn by going back to the start, with ‹ or with Trajet… → Aller au départ.
        setTime(time + travel);
        if (onMotion != null) onMotion.accept(selectedMotion);
        if (actor >= 0) select(actor);
        selectedMotion = to.id;
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
        // A tackle is a run: the player gets there at their own pace, not at a ball's.
        double speed = Diagram.SHOT.equals(shape.kind) ? STRUCK
            : Diagram.RUN.equals(shape.kind) || Diagram.TACKLE.equals(shape.kind) ? RUNNING : PASSED;
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
                a.flight = false; sequence().remove(diagram.ball, b); return true;
            }
        }
        for (Diagram.Token token : diagram.tokens) {
            for (int i = token.track.keys.size()-1; i >= 0; i--) {
                Track.Key b = token.track.keys.get(i);
                if (b.time == 0) continue;
                double[] a = i == 0 ? new double[]{token.x, token.y}
                    : new double[]{token.track.keys.get(i-1).x, token.track.keys.get(i-1).y};
                if (nearRoute(px, py, a, new double[]{b.x, b.y}, b.path)) {
                    sequence().remove(token.track, b); return true;
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
            double[] p = spot(token);
            double[] bounds = translateAll ? token.bounds() : new double[]{p[0], p[1], p[0], p[1]};
            mx = Math.max(-bounds[0], Math.min(1 - bounds[2], mx));
            my = Math.max(-bounds[1], Math.min(1 - bounds[3], my));
        }
        for (int index : moving) {
            Diagram.Token token = diagram.tokens.get(index);
            if (translateAll) token.reposition(mx, my);
            else { double[] p = spot(token); sequence().place(token, time, p[0]+mx, p[1]+my); }
        }
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
