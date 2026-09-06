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
    /** The four things a line between two points can mean. Order is the order of the toolbar. */
    static final String PASS = "pass", RUN = "run", CARRY = "carry", SHOT = "shot";
    /** Bounds the log is willing to carry, and the server to accept. */
    static final int TOKENS = 30, SHAPES = 40, POINTS = 32;

    /**
     * A disc on the grass. A real player carries his id and takes his number, name and colours
     * from the match; anyone the match does not name — an opponent in a schema drawn from
     * memory — carries a side and a label of his own instead.
     */
    static final class Token {
        String playerId = "", team = "neutral", label = "";
        double x, y;
        Token(String playerId, String team, String label, double x, double y) {
            this.playerId = playerId == null ? "" : playerId;
            this.team = team == null ? "neutral" : team;
            this.label = label == null ? "" : label;
            this.x = x; this.y = y;
        }
    }

    /** A stroke, from where the ball or the player left to where he arrived. */
    static final class Shape {
        String kind;
        final List<double[]> points = new ArrayList<>();
        Shape(String kind) { this.kind = kind; }
    }

    String board = BLANK;
    final List<Token> tokens = new ArrayList<>();
    final List<Shape> shapes = new ArrayList<>();

    boolean isEmpty() { return tokens.isEmpty() && shapes.isEmpty(); }

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
        return new JSONObject().put("board", board).put("tokens", placed).put("shapes", drawn);
    }

    /** Reads back a schema, dropping anything it cannot make sense of rather than failing. */
    static Diagram from(JSONObject source) {
        Diagram diagram = new Diagram();
        if (source == null) return diagram;
        diagram.board = FULL.equals(source.optString("board")) ? FULL : BLANK;
        JSONArray placed = source.optJSONArray("tokens");
        for (int i = 0; placed != null && i < placed.length(); i++) {
            JSONObject one = placed.optJSONObject(i);
            if (one == null) continue;
            diagram.tokens.add(new Token(one.optString("player_id"), one.optString("team", "neutral"),
                one.optString("label"), one.optDouble("x", .5), one.optDouble("y", .5)));
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
        return diagram;
    }
}
