package fr.fonote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the noted actions took place, read from both ways a note can say it.
 *
 * <p>A quick note says it outright: the 📍 puts a point under an action. A tactical note says it
 * by drawing: the player stands somewhere on the board, and the ball goes through his feet. Both
 * write in the same frame — fractions of the pitch, home attacking downwards — so they add up on
 * one map without converting anything.
 *
 * <p>Java without Android, like the diagram it reads, so it is checked off the device.
 */
final class Heat {
    /** Where a spot comes from, which is also how far it can be trusted. */
    enum Source {
        /** The 📍 of a quick note: said on purpose, where it happened. */
        PINNED,
        /** An action the note gives, set where the board has the player: as exact as the drawing. */
        PLACED,
        /** An action nobody gave, read off the drawing itself: never counted in the bilan. */
        DRAWN
    }

    /** One action, where it happened. */
    static final class Spot {
        final String playerId, action;
        final double x, y;
        final Source source;
        Spot(String playerId, String action, double x, double y, Source source) {
            this.playerId = playerId; this.action = action; this.x = x; this.y = y; this.source = source;
        }
    }

    /** What a drawing can be read as: the palette's own actions, so the map needs no key of its own. */
    static final String PASS = "pass", PASS_MISSED = "pass_missed", TACKLE = "tackle";

    private Heat() { }

    /**
     * The spots of one note. A point the note gives wins over the board: it was said on purpose.
     * An entry with neither — a quick note without a 📍 — has no spot and is left out.
     *
     * <p>A board also says things nobody wrote down, and those are read too (see {@link #drawn}),
     * but only for the players the note gives no action: an action given is what was meant, and
     * reading the drawing on top of it would count the same pass twice.
     *
     * @param sides the side of each match player, "home" or "away", to tell a pass from a gift
     */
    static List<Spot> of(JSONArray entries, JSONObject schema, Map<String,String> sides) {
        List<Spot> spots = new ArrayList<>();
        Set<String> given = new HashSet<>();
        Diagram drawn = null;
        if (schema != null) {
            // A schema this client cannot read has no place to give; the note still counts elsewhere.
            try { drawn = Diagram.from(schema); }
            catch (RuntimeException unreadable) { drawn = null; }
        }
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("player_id"), action = entry.optString("action");
            given.add(id);
            if (entry.has("x") && entry.has("y")) {
                spots.add(new Spot(id, action, entry.optDouble("x"), entry.optDouble("y"), Source.PINNED));
                continue;
            }
            double[] at = drawn == null ? null : where(drawn, id);
            if (at != null) spots.add(new Spot(id, action, at[0], at[1], Source.PLACED));
        }
        if (drawn != null)
            for (Spot spot : drawn(drawn, sides)) if (!given.contains(spot.playerId)) spots.add(spot);
        return spots;
    }

    /**
     * Where a board puts a player's action: where he last had the ball, since what a note credits
     * is nearly always done with it — the pass leaves from there, the shot is struck there, the
     * ball is lost there. A player the ball never reached is where his last move ended, or where
     * he was placed if he never moved. Null when the board does not carry him.
     */
    static double[] where(Diagram drawn, String playerId) {
        if (playerId.isEmpty()) return null;
        for (Diagram.Token token : drawn.tokens) {
            if (!playerId.equals(token.playerId)) continue;
            Track.Key touch = null;
            for (Track.Key key : drawn.ball.keys) if (token.id.equals(key.owner)) touch = key;
            return drawn.position(token, touch == null ? Track.END : touch.time);
        }
        return null;
    }

    /**
     * What a drawing says without anyone saying it, and only what it says for sure. A ball that
     * reaches a teammate is a good pass, one that reaches an opponent a missed one, both where it
     * left the passer's feet; a tackle is a good defensive gesture, where it landed. A shot says
     * nothing either way — the board does not know if it went in —, nor does a ball played into
     * space that nobody collects, nor a run. Only match players are credited: a nameless pion is
     * nobody.
     */
    static List<Spot> drawn(Diagram drawn, Map<String,String> sides) {
        List<Spot> spots = new ArrayList<>();
        List<Track.Key> keys = drawn.ball.keys;
        for (int i = 1; i < keys.size(); i++) {
            Track.Key from = keys.get(i - 1);
            // The kind is written on the arrival, the departure being where the ball was until then.
            if (!from.flight || Diagram.SHOT.equals(keys.get(i).kind)) continue;
            Diagram.Token passer = token(drawn, from.owner), target = receiver(drawn, i);
            if (passer == null || target == null || passer == target || !Diagram.named(passer)) continue;
            String mine = side(passer, sides), theirs = side(target, sides);
            if (!known(mine) || !known(theirs)) continue;
            double[] at = drawn.ballKey(from, from.time);
            spots.add(new Spot(passer.playerId, mine.equals(theirs) ? PASS : PASS_MISSED,
                at[0], at[1], Source.DRAWN));
        }
        for (Diagram.Token token : drawn.tokens) {
            if (!Diagram.named(token)) continue;
            for (Track.Key key : token.track.keys)
                if (Diagram.TACKLE.equals(key.kind))
                    spots.add(new Spot(token.playerId, TACKLE, key.x, key.y, Source.DRAWN));
        }
        return spots;
    }

    /**
     * Who a ball landing at key {@code arrival} ends up with: the first player it reaches. A ball
     * played into space is still a pass to whoever runs onto it, as long as it lies there
     * untouched; one struck again from where it lies has left the passer's story.
     */
    private static Diagram.Token receiver(Diagram drawn, int arrival) {
        List<Track.Key> keys = drawn.ball.keys;
        for (int i = arrival; i < keys.size(); i++) {
            Track.Key key = keys.get(i);
            if (!key.owner.isEmpty()) return token(drawn, key.owner);
            if (key.flight) return null;
        }
        return null;
    }
    private static Diagram.Token token(Diagram drawn, String id) {
        if (id.isEmpty()) return null;
        for (Diagram.Token token : drawn.tokens) if (token.id.equals(id)) return token;
        return null;
    }
    /** A player's side comes from the match; a pion carries its own. */
    private static String side(Diagram.Token token, Map<String,String> sides) {
        return Diagram.named(token) ? sides.getOrDefault(token.playerId, "") : token.team;
    }
    private static boolean known(String side) { return "home".equals(side) || "away".equals(side); }

    /** The players the spots are about, the most located first; ties keep the order they came in. */
    static List<String> players(List<Spot> spots) {
        Map<String,Integer> counts = new LinkedHashMap<>();
        for (Spot spot : spots) counts.merge(spot.playerId, 1, Integer::sum);
        List<String> ranked = new ArrayList<>(counts.keySet());
        ranked.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));
        return ranked;
    }
}
