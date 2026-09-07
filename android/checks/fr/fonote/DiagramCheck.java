package fr.fonote;

public final class DiagramCheck {
    private static void near(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-8) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) throws Exception {
        Diagram empty = new Diagram();
        assert empty.isEmpty();
        empty.ball.put(new Track.Key(0, .5, .5));
        assert !empty.isEmpty();
        Diagram placement = new Diagram();
        Diagram.Token placed = new Diagram.Token("", "home", "3", .2, .3);
        placement.tokens.add(placed);
        placed.reposition(.1, .1);
        assert placed.track.keys.isEmpty();
        near(placement.position(placed, 0)[0], .3);
        near(placement.position(placed, 70)[0], .3);
        Track.Key arrival = new Track.Key(30, .6, .7);
        arrival.path.add(new double[]{.3, .4});
        arrival.path.add(new double[]{.5, .4});
        arrival.path.add(new double[]{.6, .7});
        placed.track.put(arrival);
        // No ball anywhere is nobody's carry.
        assert !placement.carrying(placed, 0, 30);
        Track.Key possession = new Track.Key(0, .3, .4); possession.owner = placed.id;
        placement.ball.put(possession);
        assert placement.carrying(placed, 0, 30);
        double[] before = placement.position(placed, 15);
        placed.reposition(.1, -.1);
        assert placed.track.keys.size() == 1 && arrival.time == 30;
        near(placement.position(placed, 15)[0], before[0] + .1);
        near(placement.position(placed, 15)[1], before[1] - .1);
        near(placement.ballPosition(15)[0], before[0] + .1);
        near(placed.bounds()[0], .4); near(placed.bounds()[3], .6);
        Diagram d = new Diagram();
        Diagram.Token a = new Diagram.Token("", "home", "1", .1, .2);
        Diagram.Token b = new Diagram.Token("", "away", "2", .3, .5);
        d.tokens.add(a); d.tokens.add(b);
        a.track.put(new Track.Key(30, .7, .2));
        b.track.put(new Track.Key(10, .3, .5)); b.track.put(new Track.Key(40, .9, .5));
        Track.Key held = new Track.Key(0, .1, .2); held.owner = a.id; d.ball.put(held);
        Track.Key pass = new Track.Key(30, .7, .2); pass.owner = a.id; pass.flight = true; d.ball.put(pass);
        Track.Key received = new Track.Key(40, .9, .5); received.owner = b.id; d.ball.put(received);
        near(d.ballPosition(20)[0], .5);
        near(d.ballPosition(35)[0], .8); near(d.ballPosition(35)[1], .35);
        near(d.ballPosition(40)[0], .9);
        // Where the ball leaves for, so the board can set it at the passer's feet on that side.
        // The departure key sits on the passer himself, so it is the arrival that answers.
        near(d.ballNext(20)[0], .9); near(d.ballNext(20)[1], .5);
        assert d.ballNext(40) == null;
        b.track.put(new Track.Key(60, .5, .5));
        near(d.ballPosition(50)[0], .7);
        // Carried away by its holder, the ball is not going anywhere else: nothing to point to.
        assert d.ballNext(40) == null;
        // A receiver correction updates the pass target at arrival, not at departure.
        b.track.put(new Track.Key(40, .7, .5)); near(d.ballPosition(35)[0], .7);
        // What a stretch of a track means is read off the ball, never off the stroke that drew it.
        assert d.carrying(a, 0, 30);
        assert !d.carrying(b, 0, 10) && !d.carrying(b, 10, 40);
        assert d.carrying(b, 40, 60);
        // Struck as the run starts, the ball is already gone; struck at the arrival, it was
        // carried all the way there; struck on the way, the stretch is not one carry.
        assert !d.carrying(a, 30, 40);
        assert d.carrying(a, 20, 30);
        assert !d.carrying(a, 0, 40);
        d.removeToken(0);
        near(d.ballPosition(35)[0], .7);
        assert d.ball.keys.get(1).owner.isEmpty();
        if (args.length > 0) {
            Diagram restored = Diagram.from(d.toJson());
            near(restored.ballPosition(35)[0], .7);
            near(restored.ballPosition(50)[0], .6);
            assert restored.tokens.get(0).id.equals(b.id);
            Diagram old = Diagram.from(new org.json.JSONObject("{\"board\":\"blank\",\"tokens\":[{\"team\":\"home\",\"x\":0.2,\"y\":0.4}],\"shapes\":[]}"));
            near(old.position(old.tokens.get(0), 50)[0], .2);
            assert Diagram.from(old.toJson()).tokens.size() == 1;
            // A schema written when a carry was a stroke of its own still reads as one, from the
            // possession it recorded rather than from the word; and no stretch is written a kind.
            Diagram carried = Diagram.from(new org.json.JSONObject("{\"version\":2,\"board\":\"blank\","
                + "\"tokens\":[{\"id\":\"p\",\"x\":0.2,\"y\":0.4,\"keys\":[{\"t\":0,\"x\":0.2,\"y\":0.4},"
                + "{\"t\":20,\"x\":0.6,\"y\":0.4,\"kind\":\"carry\"}]}],"
                + "\"ball\":[{\"t\":0,\"x\":0.2,\"y\":0.4,\"owner\":\"p\"}],\"shapes\":[]}"));
            assert carried.carrying(carried.tokens.get(0), 0, 20);
            near(carried.ballPosition(10)[0], .4);
            assert !carried.toJson().getJSONArray("tokens").getJSONObject(0)
                .getJSONArray("keys").getJSONObject(1).has("kind");
        }
        System.out.println("DiagramCheck passed");
    }
}
