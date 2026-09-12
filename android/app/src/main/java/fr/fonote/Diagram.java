package fr.fonote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What a tactical note draws: a few players placed on the grass, and the run of play between them.
 *
 * <p>Deliberately geometry and nothing else. Who a token is, what colour he wears and what he was
 * seen doing are the match's business and the note's business respectively; a schema that carried
 * a copy of them would drift the day a substitution or a correction changed one of them. A token
 * therefore names a player and stops there, and the board looks the rest up as it draws.
 *
 * <p>Coordinates are fractions of the pitch, home attacking downwards — the same frame
 * {@code match.json} authors its lineup in, so a full board opens on the real formation without
 * converting anything.
 */
final class Diagram {
    /** A pitch opened empty, and one opened with everyone on it. Only the first is written small. */
    static final String BLANK = "blank", FULL = "full";
    /**
     * What a line between two points can mean. Three of them are drawn, in the toolbar's order;
     * the fourth is never chosen but read off the ball, a run made by the player who holds it
     * being a carry. Old schemas that named one still parse, and still say the same thing.
     * Le tacle est le seul trait d'un joueur qui porte son sens : une course qui finit au contact
     * d'un autre joueur, ce que le ballon ne peut pas dire à sa place.
     */
    static final String PASS = "pass", RUN = "run", CARRY = "carry", SHOT = "shot", TACKLE = "tackle";
    /** Bounds the log is willing to carry, and the server to accept. */
    static final int TOKENS = 30, SHAPES = 40, POINTS = 32;

    /**
     * A disc on the grass. A real player carries his id and takes his number, name and colours
     * from the match; anyone the match does not name — an opponent in a schema drawn from
     * memory — carries a side and a label of his own instead.
     */
    static final class Token {
        String playerId = "", team = "neutral", label = "";
        String id = java.util.UUID.randomUUID().toString();
        final Track track = new Track();
        double x, y;
        Token(String playerId, String team, String label, double x, double y) {
            this.playerId = playerId == null ? "" : playerId;
            this.team = team == null ? "neutral" : team;
            this.label = label == null ? "" : label;
            this.x = x; this.y = y;
        }

        /** Every recorded position participates in placement, independently of the playhead. */
        double[] bounds() {
            double[] bounds = {x, y, x, y};
            for (Track.Key key : track.keys) {
                include(bounds, key.x, key.y);
                for (double[] point : key.path) include(bounds, point[0], point[1]);
            }
            return bounds;
        }

        private static void include(double[] bounds, double x, double y) {
            bounds[0] = Math.min(bounds[0], x); bounds[1] = Math.min(bounds[1], y);
            bounds[2] = Math.max(bounds[2], x); bounds[3] = Math.max(bounds[3], y);
        }

        void reposition(double dx, double dy) {
            x += dx; y += dy;
            for (Track.Key key : track.keys) {
                key.x += dx; key.y += dy;
                for (double[] point : key.path) { point[0] += dx; point[1] += dy; }
            }
        }
    }

    /** A stroke, from where the ball or the player left to where he arrived. */
    static final class Shape {
        String kind;
        boolean baked;
        final List<double[]> points = new ArrayList<>();
        Shape(String kind) { this.kind = kind; }
    }

    static final class Step {
        String id = java.util.UUID.randomUUID().toString(), name;
        int time;
        Step(String name, int time) { this.name = name; this.time = time; }
    }
    final List<Step> steps = new ArrayList<>();
    String board = BLANK;
    final Track ball = new Track();
    final List<Token> tokens = new ArrayList<>();
    final List<Shape> shapes = new ArrayList<>();

    boolean isEmpty() { return tokens.isEmpty() && shapes.isEmpty() && ball.keys.isEmpty(); }

    /** The player a token names, or "" for one the match does not know. */
    static boolean named(Token token) { return !token.playerId.isEmpty(); }

    /**
     * Three decimals is a tenth of a percent of the pitch — finer than a finger can aim and finer
     * than a shirt is wide. Writing the full double instead would triple the size of a stroke.
     */
    private static double round(double value) {
        double clamped = Math.max(0, Math.min(1, value));
        return Math.round(clamped * 1000) / 1000.0;
    }

    JSONObject toJson() throws JSONException {
        JSONArray placed = new JSONArray();
        for (Token token : tokens) {
            JSONObject one = new JSONObject().put("x", round(token.x)).put("y", round(token.y));
            one.put("id", token.id).put("keys", keysJson(token.track));
            if (named(token)) one.put("player_id", token.playerId);
            else {
                one.put("team", token.team);
                if (!token.label.isEmpty()) one.put("label", token.label);
            }
            placed.put(one);
        }
        JSONArray drawn = new JSONArray();
        for (Shape shape : shapes) {
            JSONArray points = new JSONArray();
            for (double[] point : shape.points)
                points.put(new JSONArray().put(round(point[0])).put(round(point[1])));
            drawn.put(new JSONObject().put("kind", shape.kind).put("points", points));
        }
        JSONArray marks = new JSONArray();
        for (Step step : steps) marks.put(new JSONObject().put("id", step.id)
            .put("name", step.name).put("t", step.time));
        return new JSONObject().put("version", 3).put("ball", keysJson(ball))
            .put("board", board).put("tokens", placed).put("shapes", drawn).put("steps", marks);
    }

    static JSONArray keysJson(Track track) throws JSONException {
        JSONArray result = new JSONArray();
        for (Track.Key key : track.keys) {
            JSONObject one = new JSONObject().put("id", key.id).put("t", key.time).put("x", round(key.x)).put("y", round(key.y));
            if (!key.after.isEmpty()) one.put("after", key.after).put("offset", key.offset);
            if (key.baked) one.put("baked", true);
            // The ball carries a kind; a player's stroke is read off the ball, save a tackle.
            if (!RUN.equals(key.kind)) one.put("kind", key.kind);
            if (!key.owner.isEmpty()) one.put("owner", key.owner);
            if (key.flight) one.put("flight", true);
            if (!key.path.isEmpty()) {
                JSONArray path = new JSONArray();
                for (double[] p : key.path) path.put(new JSONArray().put(round(p[0])).put(round(p[1])));
                one.put("path", path);
            }
            result.put(one);
        }
        return result;
    }

    /** A kind is the ball's, save a player's tackle; any other left on a player's track by an older client is dropped. */
    private static void readKeys(JSONArray source, Track track, boolean ball) {
        for (int i = 0; source != null && i < Math.min(source.length(), Track.LIMIT); i++) {
            JSONObject one = source.optJSONObject(i);
            if (one == null) continue;
            int time = one.optInt("t", -1);
            if (time < 0 || time > Track.END) continue;
            Track.Key key = new Track.Key(time, one.optDouble("x", .5), one.optDouble("y", .5));
            key.id = one.optString("id", key.id);
            key.after = one.optString("after"); key.offset = one.optInt("offset");
            key.baked = one.optBoolean("baked");
            if (ball) key.kind = one.optString("kind", RUN);
            else if (TACKLE.equals(one.optString("kind"))) key.kind = TACKLE;
            key.owner = one.optString("owner"); key.flight = one.optBoolean("flight");
            JSONArray path = one.optJSONArray("path");
            for (int j = 0; path != null && j < Math.min(path.length(), POINTS); j++) {
                JSONArray point = path.optJSONArray(j);
                if (point != null && point.length() == 2) key.path.add(new double[]{point.optDouble(0), point.optDouble(1)});
            }
            track.put(key);
        }
    }

    double[] position(Token token, double time) { return token.track.position(time, token.x, token.y); }

    double[] ballKey(Track.Key key, double time) {
        for (Token token : tokens) if (token.id.equals(key.owner)) return position(token, time);
        return new double[]{key.x, key.y};
    }

    double[] ballPosition(double time) {
        if (ball.keys.isEmpty() || time < ball.keys.get(0).time) return new double[]{.5, .5};
        Track.Key previous = ball.keys.get(0);
        for (Track.Key next : ball.keys) {
            if (next.time > time) {
                if (!previous.flight) return ballKey(previous, time);
                double[] a = ballKey(previous, previous.time), b = ballKey(next, next.time);
                return Track.route(a[0], a[1], b[0], b[1], next.path, next.baked,
                    (time - previous.time) / (double)(next.time - previous.time));
            }
            previous = next;
        }
        return ballKey(previous, previous.flight ? previous.time : time);
    }

    /**
     * Where the ball is going once it leaves the player who holds it, or null when it stays with
     * him — carried, or simply held to the end. The first key at somebody else's feet, never
     * simply the next one: a pass is written as a departure and an arrival, and the departure is
     * still the passer's.
     */
    double[] ballNext(double time) {
        int held = -1;
        for (int i = 0; i < ball.keys.size(); i++) if (ball.keys.get(i).time <= time) held = i;
        if (held < 0) return null;
        Track.Key holder = ball.keys.get(held);
        double[] here = ballKey(holder, time);
        for (int i = held + 1; i < ball.keys.size(); i++) {
            Track.Key key = ball.keys.get(i);
            // Still at the same feet: a departure is written at the passer — who may well have
            // run to it — and a ball carried never leaves him at all.
            if (key.owner.equals(holder.owner)) continue;
            double[] there = ballKey(key, key.time);
            if (Math.hypot(there[0] - here[0], there[1] - here[1]) > .01) return there;
        }
        return null;
    }

    boolean ballHeld(double time) {
        Track.Key previous = null;
        for (Track.Key key : ball.keys) if (key.time <= time) previous = key;
        return previous != null && !previous.flight && !previous.owner.isEmpty();
    }

    /** Qui a le ballon dans les pieds à cet instant, ou rien s'il est libre ou en l'air. */
    String holder(double time) {
        Track.Key previous = null;
        for (Track.Key key : ball.keys) if (key.time <= time) previous = key;
        return previous == null || previous.flight ? "" : previous.owner;
    }

    /** Le départ et l'arrivée du vol en cours, ou null quand le ballon ne vole pas. */
    Track.Key[] ballLeg(double time) {
        for (int i = 1; i < ball.keys.size(); i++) {
            Track.Key a = ball.keys.get(i-1), b = ball.keys.get(i);
            if (a.flight && a.time <= time && time < b.time) return new Track.Key[]{a, b};
        }
        return null;
    }

    /**
     * Whether a player has the ball at his feet for a whole stretch of his track: what makes a
     * movement a carry rather than a run. Read off the ball instead of being recorded on the
     * stroke, because the ball already follows whoever owns it — a run drawn by the player
     * holding it moves the ball with him, and the drawing has no business saying otherwise.
     *
     * <p>The bounds are read the way possession changes hands. A ball leaving at {@code from} —
     * a pass struck as the run starts — is already gone, so the stretch is a run; one leaving at
     * {@code to} was carried the whole way there, so it is not.
     */
    boolean carrying(Token token, int from, int to) {
        Track.Key holder = null;
        for (Track.Key key : ball.keys) if (key.time <= from) holder = key;
        if (holder == null || holder.flight || !token.id.equals(holder.owner)) return false;
        for (Track.Key key : ball.keys)
            if (key.time > from && key.time < to && (key.flight || !token.id.equals(key.owner)))
                return false;
        return true;
    }

    int duration() {
        int end = 0;
        for (Step step : steps) end = Math.max(end, step.time);
        for (Token token : tokens) for (Track.Key key : token.track.keys) end = Math.max(end, key.time);
        for (Track.Key key : ball.keys) end = Math.max(end, key.time);
        return end;
    }

    void removeToken(int index) {
        Token token = tokens.get(index);
        // Preserve the ball's recorded location when its owner is removed.
        for (Track.Key key : ball.keys) if (token.id.equals(key.owner)) {
            double[] p = position(token, key.time); key.x = p[0]; key.y = p[1]; key.owner = "";
        }
        tokens.remove(index);
    }

    /** Reads back a schema, dropping anything it cannot make sense of rather than failing. */
    static Diagram from(JSONObject source) {
        Diagram diagram = new Diagram();
        if (source == null) return diagram;
        int version = source.optInt("version", 1);
        if (version < 1 || version > 3) throw new IllegalArgumentException("Version de séquence non prise en charge");
        diagram.board = FULL.equals(source.optString("board")) ? FULL : BLANK;
        JSONArray placed = source.optJSONArray("tokens");
        for (int i = 0; placed != null && i < placed.length(); i++) {
            JSONObject one = placed.optJSONObject(i);
            if (one == null) continue;
            Token token = new Token(one.optString("player_id"), one.optString("team", "neutral"),
                one.optString("label"), one.optDouble("x", .5), one.optDouble("y", .5));
            token.id = one.optString("id", token.id);
            readKeys(one.optJSONArray("keys"), token.track, false);
            diagram.tokens.add(token);
        }
        JSONArray drawn = source.optJSONArray("shapes");
        for (int i = 0; drawn != null && i < drawn.length(); i++) {
            JSONObject one = drawn.optJSONObject(i);
            JSONArray points = one == null ? null : one.optJSONArray("points");
            if (points == null || points.length() < 2) continue;
            Shape shape = new Shape(one.optString("kind", PASS));
            for (int j = 0; j < points.length(); j++) {
                JSONArray point = points.optJSONArray(j);
                if (point != null && point.length() >= 2)
                    shape.points.add(new double[]{point.optDouble(0), point.optDouble(1)});
            }
            if (shape.points.size() >= 2) diagram.shapes.add(shape);
        }
        readKeys(source.optJSONArray("ball"), diagram.ball, true);
        JSONArray marks = source.optJSONArray("steps");
        for (int i = 0; marks != null && i < marks.length(); i++) {
            JSONObject mark = marks.optJSONObject(i);
            if (mark == null) continue;
            Step step = new Step(mark.optString("name"), mark.optInt("t"));
            step.id = mark.optString("id", step.id); diagram.steps.add(step);
        }
        if (version == 3) {
            Sequence sequence = new Sequence(diagram);
            java.util.Map<String, Integer> times = new java.util.HashMap<>();
            for (Track track : sequence.tracks()) for (Track.Key key : track.keys) times.put(key.id, key.time);
            sequence.resolve();
            for (Track track : sequence.tracks()) for (Track.Key key : track.keys)
                if (times.get(key.id) != key.time) throw new IllegalArgumentException("Temps liés incohérents");
        }
        return diagram;
    }
}
