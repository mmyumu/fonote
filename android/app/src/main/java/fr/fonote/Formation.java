package fr.fonote;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Schematic spots for a lineup, for a side attacking downwards.
 *
 * <p>The placement reads each player's published position, never the order of the sheet.
 * ESPN numbers a lineup by the classic shirt convention — 2 is the right back, 9 the striker,
 * 11 a winger — so filling formation lines in that order puts a left-sided attacking midfielder
 * at centre forward. The position text says it outright instead: a depth band from
 * "Defender" / "Defensive Midfielder" / "Attacking Midfielder" / "Forward", and a lane from
 * "Left", "Center Left", "Center Right", "Right".
 *
 * <p>The bands that are actually occupied become the lines, so a lineup draws its own shape and
 * the published formation string is only a fallback. The drawing stays schematic: a line is
 * spread evenly across the pitch and no player is pinned to a real touchline.
 *
 * <p>Sides are drawn as they are seen, not as they are named. This side attacks downwards, so a
 * player facing his opponent's goal has his own left on the screen's right — the right back is
 * drawn on the left of the picture. {@code match.json} has always been authored that way (Pavard
 * at x=0.13, Hernandez at x=0.87), and PitchView turns the away side around to match.
 */
final class Formation {
    private Formation() {}

    /** Depth bands, back to front. */
    private static final int KEEPER = 0, DEFENCE = 1, HOLDING = 2, MIDFIELD = 3, ATTACKING = 4, ATTACK = 5;
    private static final int UNKNOWN = -1;
    /** The one shape every unusable lineup falls back to, and the one this file is authored on. */
    private static final int[] DEFAULT = {4, 4, 2};
    private static final double KEEPER_Y = .055;
    /** The band the outfield lines share. It reaches as far as the shirts allow and no further:
     *  past the last line a lone striker's shirt would touch its mirrored opposite across the
     *  halfway line, and before the first one it would touch the keeper's. */
    private static final double FIRST_Y = .15, LAST_Y = .46, LINE_GAP = .11;
    /** How far apart a line of n stands; wider lines fill the same width instead of overflowing. */
    private static final double[] SPREAD = {0, 0, .34, .26, .2467, .185};
    private static final double WIDTH = .74;

    /** How deep a published position stands, or {@link #UNKNOWN} for wording we do not know. */
    static int band(String position) {
        String text = position == null ? "" : position.toLowerCase(Locale.ROOT);
        // Both midfield qualifiers carry the word "midfielder", so they are read before it.
        if (text.contains("keeper")) return KEEPER;
        if (text.contains("defensive midfield")) return HOLDING;
        if (text.contains("attacking midfield")) return ATTACKING;
        if (text.contains("midfield")) return MIDFIELD;
        if (text.contains("defender") || text.contains("back")) return DEFENCE;
        if (text.contains("forward") || text.contains("striker") || text.contains("wing")) return ATTACK;
        return UNKNOWN;
    }

    /** Which side of his line a player stands on, from -2 on his own left to 2 on his own right. */
    static int lane(String position) {
        String text = position == null ? "" : position.toLowerCase(Locale.ROOT);
        // "Center Left Defender" is inside the pair, not on the touchline: read it before "left".
        if (text.contains("center left") || text.contains("centre left")) return -1;
        if (text.contains("center right") || text.contains("centre right")) return 1;
        if (text.contains("left")) return -2;
        if (text.contains("right")) return 2;
        return 0;
    }

    /** Outfield lines, back to front, read from a formation string. Not ten outfield: 4-4-2. */
    static int[] lines(String formation) {
        String[] parts = (formation == null ? "" : formation).trim().split("[^0-9]+");
        int[] lines = new int[parts.length];
        int count = 0, total = 0;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            // A line of six is already unheard of; anything longer is a misread string, not a shape.
            if (part.length() > 1) return DEFAULT;
            int size = part.charAt(0) - '0';
            if (size < 1 || size > 5) return DEFAULT;
            lines[count++] = size; total += size;
        }
        if (count < 2 || total != 10) return DEFAULT;
        return java.util.Arrays.copyOf(lines, count);
    }

    /**
     * A spot per player, in the order given. Positions decide it; the formation string is used
     * only when the eleven cannot be read — a missing keeper, or wording we have no band for.
     */
    static double[][] spots(String[] positions, String formation) {
        List<Integer> occupied = new ArrayList<>();
        int[] bands = new int[positions.length];
        int keeper = -1;
        for (int i = 0; i < positions.length; i++) {
            bands[i] = band(positions[i]);
            if (bands[i] == UNKNOWN) return byFormation(positions.length, formation);
            if (bands[i] == KEEPER) {
                if (keeper >= 0) return byFormation(positions.length, formation);
                keeper = i;
            } else if (!occupied.contains(bands[i])) occupied.add(bands[i]);
        }
        if (keeper < 0 || occupied.isEmpty()) return byFormation(positions.length, formation);
        java.util.Collections.sort(occupied);
        double[][] spots = new double[positions.length][];
        spots[keeper] = new double[]{.5, KEEPER_Y};
        double gap = occupied.size() < 2 ? 0
            : Math.min(LINE_GAP, (LAST_Y - FIRST_Y) / (occupied.size() - 1));
        for (int line = 0; line < occupied.size(); line++) {
            List<Integer> members = new ArrayList<>();
            for (int i = 0; i < bands.length; i++) if (bands[i] == occupied.get(line)) members.add(i);
            // Descending: this side attacks downwards, so a player's own left is the screen's
            // right. Stable, so two players sharing a lane keep the order of the sheet between them.
            members.sort((a, b) -> Integer.compare(lane(positions[b]), lane(positions[a])));
            double step = spread(members.size());
            for (int place = 0; place < members.size(); place++)
                spots[members.get(place)] = new double[]{
                    .5 + (place - (members.size() - 1) / 2.) * step, FIRST_Y + line * gap};
        }
        return spots;
    }

    /** The published shape, filled in the order given: no position to trust, so no lane either. */
    private static double[][] byFormation(int size, String formation) {
        int[] lines = lines(formation);
        double gap = Math.min(LINE_GAP, (LAST_Y - FIRST_Y) / (lines.length - 1));
        double[][] spots = new double[Math.max(size, 1)][];
        spots[0] = new double[]{.5, KEEPER_Y};
        int index = 1;
        for (int line = 0; line < lines.length && index < spots.length; line++) {
            double step = spread(lines[line]);
            for (int place = 0; place < lines[line] && index < spots.length; place++)
                spots[index++] = new double[]{
                    .5 + (place - (lines[line] - 1) / 2.) * step, FIRST_Y + line * gap};
        }
        // A sheet longer than the shape still gets a spot rather than a hole on the pitch.
        while (index < spots.length) spots[index++] = new double[]{.5, LAST_Y};
        return spots;
    }

    private static double spread(int size) {
        return size < SPREAD.length ? SPREAD[size] : WIDTH / (size - 1);
    }
}
