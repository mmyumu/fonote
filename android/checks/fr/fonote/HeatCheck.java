package fr.fonote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

public final class HeatCheck {
    private static void near(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-8) throw new AssertionError(actual + " != " + expected);
    }
    private static JSONObject entry(String player, String action) throws Exception {
        return new JSONObject().put("player_id", player).put("action", action);
    }
    private static Track.Key ball(int time, double x, double y, Diagram.Token owner, boolean flight) {
        Track.Key key = new Track.Key(time, x, y);
        if (owner != null) key.owner = owner.id;
        key.flight = flight;
        return key;
    }
    public static void main(String[] args) throws Exception {
        Map<String,String> sides = Map.of("a", "home", "b", "home", "c", "home", "d", "away", "p1", "home");

        // A quick note: the 📍 is the spot, and an entry without one has none.
        JSONArray quick = new JSONArray()
            .put(entry("p1", "pass").put("x", .25).put("y", .8))
            .put(entry("p2", "negative"));
        List<Heat.Spot> spots = Heat.of(quick, null, sides);
        assert spots.size() == 1;
        Heat.Spot pinned = spots.get(0);
        assert pinned.playerId.equals("p1") && pinned.action.equals("pass") && pinned.source == Heat.Source.PINNED;
        near(pinned.x, .25); near(pinned.y, .8);

        // A board: A passes to B, then runs on; B carries and shoots; C only runs; D stays put.
        Diagram board = new Diagram();
        Diagram.Token a = new Diagram.Token("a", "neutral", "", .2, .3);
        Diagram.Token b = new Diagram.Token("b", "neutral", "", .5, .5);
        Diagram.Token c = new Diagram.Token("c", "neutral", "", .8, .1);
        Diagram.Token d = new Diagram.Token("d", "neutral", "", .4, .9);
        Diagram.Token pion = new Diagram.Token("", "away", "7", .1, .1);
        board.tokens.add(a); board.tokens.add(b); board.tokens.add(c); board.tokens.add(d);
        board.tokens.add(pion);
        a.track.put(new Track.Key(20, .4, .5));
        b.track.put(new Track.Key(30, .6, .7));
        c.track.put(new Track.Key(15, .9, .2));
        board.ball.put(ball(0, .2, .3, a, true));
        board.ball.put(ball(10, .5, .5, b, false));
        board.ball.put(ball(30, .6, .7, b, true));
        Track.Key shot = ball(35, .5, 1, null, false); shot.kind = Diagram.SHOT;
        board.ball.put(shot);

        // Where he last had the ball, not where he ran to after playing it.
        double[] at = Heat.where(board, "a");
        near(at[0], .2); near(at[1], .3);
        // The last touch of several: the shot, struck where the carry ended.
        at = Heat.where(board, "b");
        near(at[0], .6); near(at[1], .7);
        // Never on the ball: where his run ended, or where he was put.
        at = Heat.where(board, "c");
        near(at[0], .9); near(at[1], .2);
        at = Heat.where(board, "d");
        near(at[0], .4); near(at[1], .9);
        // Nobody on the board by that name, and a nameless pion is nobody.
        assert Heat.where(board, "e") == null;
        assert Heat.where(board, "") == null;

        // Read off the drawing: the pass to a teammate is good, where it left; the shot says nothing.
        List<Heat.Spot> read = Heat.drawn(board, sides);
        assert read.size() == 1;
        assert read.get(0).playerId.equals("a") && read.get(0).action.equals(Heat.PASS)
            && read.get(0).source == Heat.Source.DRAWN;
        near(read.get(0).x, .2); near(read.get(0).y, .3);

        // Read through the log's own shape: a schema as written, next to the note's entries.
        JSONArray given = new JSONArray()
            .put(entry("b", "goal")).put(entry("e", "negative"))
            // A point the note gives wins over the board.
            .put(entry("c", "dribble").put("x", .3).put("y", .3));
        spots = Heat.of(given, board.toJson(), sides);
        assert spots.size() == 3 : spots.size();
        assert spots.get(0).playerId.equals("b") && spots.get(0).source == Heat.Source.PLACED;
        near(spots.get(0).x, .6); near(spots.get(0).y, .7);
        assert spots.get(1).playerId.equals("c") && spots.get(1).source == Heat.Source.PINNED;
        near(spots.get(1).x, .3);
        // A, given nothing, keeps the pass his drawing shows.
        assert spots.get(2).playerId.equals("a") && spots.get(2).source == Heat.Source.DRAWN;
        // Given an action, he has that one and the drawing adds nothing on top.
        spots = Heat.of(new JSONArray().put(entry("a", "assist")), board.toJson(), sides);
        assert spots.size() == 1 && spots.get(0).action.equals("assist");

        // A ball given away, a tackle, a ball into space, and a pion playing it: only the first two speak.
        Diagram gift = new Diagram();
        Diagram.Token home = new Diagram.Token("a", "neutral", "", .3, .3);
        Diagram.Token away = new Diagram.Token("d", "neutral", "", .6, .6);
        Diagram.Token nameless = new Diagram.Token("", "away", "", .7, .7);
        gift.tokens.add(home); gift.tokens.add(away); gift.tokens.add(nameless);
        gift.ball.put(ball(0, .3, .3, home, true));
        gift.ball.put(ball(10, .6, .6, away, true));
        gift.ball.put(ball(20, .9, .9, null, false));
        gift.ball.put(ball(30, .9, .9, null, true));
        gift.ball.put(ball(40, .7, .7, nameless, true));
        gift.ball.put(ball(50, .3, .3, home, false));
        Track.Key tackle = new Track.Key(60, .35, .4); tackle.kind = Diagram.TACKLE;
        away.track.put(tackle);
        read = Heat.drawn(gift, sides);
        assert read.size() == 2 : read.size();
        assert read.get(0).playerId.equals("a") && read.get(0).action.equals(Heat.PASS_MISSED);
        near(read.get(0).x, .3);
        assert read.get(1).playerId.equals("d") && read.get(1).action.equals(Heat.TACKLE);
        near(read.get(1).x, .35); near(read.get(1).y, .4);
        // A ball into space is a pass to whoever collects it, if nobody strikes it first.
        Diagram through = new Diagram();
        Diagram.Token runner = new Diagram.Token("b", "neutral", "", .5, .2);
        through.tokens.add(home); through.tokens.add(runner);
        through.ball.put(ball(0, .3, .3, home, true));
        through.ball.put(ball(20, .5, .1, null, false));
        through.ball.put(ball(30, .5, .1, runner, false));
        read = Heat.drawn(through, sides);
        assert read.size() == 1 && read.get(0).action.equals(Heat.PASS) : read.size();
        // A player the match does not place on a side is not judged.
        assert Heat.drawn(gift, Map.of()).size() == 1;

        // A schema this client cannot read gives no place, and fails nothing.
        assert Heat.of(new JSONArray().put(entry("a", "pass")),
            new JSONObject().put("version", 99), sides).isEmpty();
        assert Heat.of(null, null, sides).isEmpty();

        // The most located first; a tie keeps the order the spots came in.
        spots = Heat.of(given, board.toJson(), sides);
        spots.addAll(Heat.of(quick, null, sides));
        assert Heat.players(spots).equals(List.of("b", "c", "a", "p1")) : Heat.players(spots);
        spots.add(spots.get(spots.size() - 1));
        assert Heat.players(spots).equals(List.of("p1", "b", "c", "a")) : Heat.players(spots);
        System.out.println("HeatCheck passed");
    }
}
