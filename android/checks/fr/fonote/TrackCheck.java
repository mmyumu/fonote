package fr.fonote;

public final class TrackCheck {
    private static void near(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-8) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        Track a = new Track(), b = new Track();
        a.put(new Track.Key(30, .8, .2));
        b.put(new Track.Key(10, .1, .4)); b.put(new Track.Key(40, .7, .4));
        near(a.position(20, .2, .2)[0], .6);
        near(b.position(20, .1, .4)[0], .3);
        near(a.position(90, .2, .2)[0], .8);
        near(a.position(0, .2, .2)[0], .2);
        a.put(new Track.Key(30, .5, .2));
        assert a.keys.size() == 1;
        near(a.position(20, .2, .2)[0], .4);
        Track curved = new Track();
        Track.Key end = new Track.Key(20, 1, 1);
        end.path.add(new double[]{0, 0}); end.path.add(new double[]{0, 1}); end.path.add(new double[]{1, 1});
        curved.put(end);
        // A corner is rounded off by the easing, never cut across: the route still turns
        // where it was drawn, and both ends stay exactly where they were anchored.
        double[] corner = curved.position(10, 0, 0);
        assert corner[0] < .03 && corner[1] > .97 : corner[0] + ", " + corner[1];
        near(curved.position(0, 0, 0)[0], 0); near(curved.position(20, 0, 0)[0], 1);
        // A straight run sampled by a trembling finger comes back straight.
        Track shaky = new Track();
        Track.Key across = new Track.Key(20, 1, 0);
        across.path.add(new double[]{0, 0});
        for (int i = 1; i < 5; i++) across.path.add(new double[]{i / 5.0, i % 2 == 0 ? .01 : -.01});
        across.path.add(new double[]{1, 0});
        shaky.put(across);
        for (int t = 1; t < 20; t++) {
            double drift = shaky.position(t, 0, 0)[1];
            assert Math.abs(drift) < .004 : drift;
        }
        near(shaky.position(0, 0, 0)[1], 0); near(shaky.position(20, 0, 0)[1], 0);
        Track full = new Track();
        for (int i = 0; i < Track.LIMIT; i++) assert full.put(new Track.Key(i, 0, 0));
        assert !full.put(new Track.Key(Track.LIMIT, 0, 0));
        assert full.put(new Track.Key(0, 1, 1));
        // A long gesture is brought down to the log's limit without losing its end or its shape.
        java.util.List<double[]> gesture = new java.util.ArrayList<>();
        for (int i = 0; i <= 200; i++) gesture.add(new double[]{i / 200.0, Math.sin(i / 200.0 * Math.PI) * .3});
        java.util.List<double[]> kept = Track.thinned(gesture, Diagram.POINTS);
        assert kept.size() == Diagram.POINTS : kept.size();
        near(kept.get(0)[0], 0); near(kept.get(kept.size()-1)[0], 1);
        for (double[] point : kept) assert Math.abs(point[1] - Math.sin(point[0] * Math.PI) * .3) < .01 : "Forme perdue";
        double top = 0;
        for (double[] point : kept) top = Math.max(top, point[1]);
        assert top > .29 : "Le sommet de la courbe est coupé";
        assert Track.thinned(kept.subList(0, 5), Diagram.POINTS).size() == 5;
        System.out.println("TrackCheck passed");
    }
}
