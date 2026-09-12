package fr.fonote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The wordmark at the head of the home page: FONOTE, drawn rather than typeset, because a
 * heading is set in the skin's own face and a name has to look the same in every one.
 *
 * <p>The name hides the game it notes: F, O, O and T spell FOOT once N and E step aside. They
 * step aside by their cut, not by their colour: their outer corners are bevelled where the
 * others are square, so the word still reads as one word and the second one only shows to
 * whoever looks.
 *
 * <p>Behind the word runs the halfway line, a hairline in the letters' ink. It passes under
 * them: it stops short of each letter and shows only where there is room — across both O,
 * between the second O and the T, between the T and the E. The first O is the centre circle,
 * with the kick-off spot on the line, and the spot is the one thing in the skin's accent. On the
 * second O the line is only passing through, which is why it has no spot: two spots side by side
 * read as a pattern rather than as a pitch.
 *
 * <p>The letters are geometric — bars, perfect rounds, one diagonal — with the verticals a touch
 * heavier than the horizontals, as any heavy sans draws them, and the rounds a touch taller than
 * the flats, or they would look smaller. Spacing is by eye, not by metrics: an O sits closer to
 * the open side of an F or under the arm of a T than to a stem.
 *
 * <p>Plain Java, like {@link Skin}: the drawing is data, measured and previewed off device; the
 * view only paints it. Coordinates are in units of a cap height of 100.
 */
public final class Logo {
    /** Shapes: an outline (x, y pairs, clockwise), a round with a hole (centre, outer radius,
     *  the hole's two radii). */
    public static final int OUTLINE = 0, RING = 1;

    public static final class Piece {
        public final int shape;
        public final float[] at;
        Piece(int shape, float... at) { this.shape = shape; this.at = at; }
    }

    /** A letter: its pieces, and how far it reaches left and right. */
    public static final class Glyph {
        public final List<Piece> pieces;
        public final float left, right;
        Glyph(float left, float right, Piece... pieces) {
            this.left = left; this.right = right; this.pieces = Collections.unmodifiableList(Arrays.asList(pieces));
        }
        public float centre() { return (left + right) / 2; }
    }

    /** Where each letter is in {@link #GLYPHS}. */
    public static final int F = 0, FIRST_O = 1, N = 2, SECOND_O = 3, T = 4, E = 5;

    /** Vertical and horizontal strokes, and how far a round overshoots the flats. */
    private static final float STEM = 22, BAR = 19, OVER = 2;
    /** The extent of the drawing: the rounds overshoot the cap height at both ends. */
    public static final float TOP = -OVER, BOTTOM = 100 + OVER;
    /** How much of each outer corner N and E lose. */
    private static final float BEVEL = 10;

    /** The letters, F to E, in the skin's ink. */
    public static final List<Glyph> GLYPHS;
    /** The halfway line, as a box (left, top, right, bottom), in the letters' ink. */
    public static final float[] LINE;
    /** How far the line stops short of a letter, on every side of it. */
    public static final float GAP = 8;
    /** The kick-off spot (centre, radius), in the skin's accent. */
    public static final float[] SPOT;
    public static final float WIDTH;

    static {
        List<Glyph> letters = new ArrayList<>();
        float x = 0;
        letters.add(new Glyph(x, x + 62,
            new Piece(OUTLINE, box(x, 0, x + STEM, 100)),
            new Piece(OUTLINE, box(x, 0, x + 62, BAR)),
            new Piece(OUTLINE, box(x, 42, x + 54, 42 + BAR))));
        x += 62 + 9;
        float radius = 50 + OVER, start = x;
        letters.add(ring(x, radius));
        SPOT = new float[] {x + radius, 50, 5.5f};
        x += 2 * radius;
        // N, as one outline so that its corners can be cut: the diagonal runs from the top of
        // the left stem to the foot of the right one, a little heavier than they are.
        x += 13;
        float wide = 80, diagonal = STEM + 2, run = wide - diagonal;
        float[] n = {0, 0, diagonal, 0, wide - STEM, 100 * (wide - STEM - diagonal) / run, wide - STEM, 0,
            wide, 0, wide, 100, run, 100, STEM, 100 * STEM / run, STEM, 100, 0, 100};
        letters.add(new Glyph(x, x + wide, new Piece(OUTLINE, cut(along(n, x), BEVEL, 0, 4, 5, 9))));
        x += wide + 13;
        letters.add(ring(x, radius));
        x += 2 * radius;
        // T: its arm reaches over the round, so it starts closer.
        x += 6;
        letters.add(new Glyph(x, x + 68,
            new Piece(OUTLINE, box(x, 0, x + 68, BAR)),
            new Piece(OUTLINE, box(x + 34 - STEM / 2, 0, x + 34 + STEM / 2, 100))));
        x += 68 + 12;
        // E
        float[] e = {0, 0, 56, 0, 56, BAR, STEM, BAR, STEM, 41, 50, 41, 50, 41 + BAR, STEM, 41 + BAR,
            STEM, 100 - BAR, 56, 100 - BAR, 56, 100, 0, 100};
        letters.add(new Glyph(x, x + 56, new Piece(OUTLINE, cut(along(e, x), BEVEL, 0, 1, 10, 11))));
        // From the first O's edge to the end of the E's middle bar: both ends are hidden in a
        // letter, so the line only ever shows between them. Not from the F: between its middle
        // bar and the O, the room kept from both leaves a sliver that reads as a speck.
        float thin = 3;
        LINE = new float[] {start, 50 - thin / 2, x + 50, 50 + thin / 2};
        WIDTH = x + 56;
        GLYPHS = Collections.unmodifiableList(letters);
    }

    /** An O from {@code left}. */
    private static Glyph ring(float left, float radius) {
        return new Glyph(left, left + 2 * radius, new Piece(RING, left + radius, 50, radius, radius - STEM, radius - BAR));
    }

    private static float[] box(float left, float top, float right, float bottom) {
        return new float[] {left, top, right, top, right, bottom, left, bottom};
    }

    private static float[] along(float[] outline, float x) {
        float[] moved = outline.clone();
        for (int i = 0; i < moved.length; i += 2) moved[i] += x;
        return moved;
    }

    /** Cuts the listed corners of an outline by {@code size} along both of their edges. */
    private static float[] cut(float[] outline, float size, int... corners) {
        int count = outline.length / 2;
        List<Float> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float x = outline[2 * i], y = outline[2 * i + 1];
            boolean cutHere = false;
            for (int corner : corners) cutHere |= corner == i;
            if (!cutHere) { points.add(x); points.add(y); continue; }
            int before = (i + count - 1) % count, after = (i + 1) % count;
            for (int end : new int[] {before, after}) {
                float dx = outline[2 * end] - x, dy = outline[2 * end + 1] - y;
                float length = (float) Math.hypot(dx, dy);
                points.add(x + dx / length * size); points.add(y + dy / length * size);
            }
        }
        float[] cutOutline = new float[points.size()];
        for (int i = 0; i < cutOutline.length; i++) cutOutline[i] = points.get(i);
        return cutOutline;
    }

    private Logo() {}
}
