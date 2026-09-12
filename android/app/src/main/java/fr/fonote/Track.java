package fr.fonote;

import java.util.ArrayList;
import java.util.List;

/** Independent key positions on the action's common clock (tenths of a second). */
final class Track {
    static final int END = 1200, LIMIT = 120;
    static final class Key {
        String id = java.util.UUID.randomUUID().toString();
        String after = "";
        int offset;
        boolean baked;
        int time;
        double x, y;
        String owner = "", kind = "run";
        boolean flight;
        // Optional route arriving at this key; endpoints are resolved from the keys themselves.
        final List<double[]> path = new ArrayList<>();
        Key(int time, double x, double y) { this.time = time; this.x = x; this.y = y; }
    }
    final List<Key> keys = new ArrayList<>();
    Key at(int time) {
        for (Key key : keys) if (key.time == time) return key;
        return null;
    }
    boolean put(Key key) {
        Key old = at(key.time);
        if (old == null && keys.size() >= LIMIT) return false;
        if (old != null && old != key) {
            key.id = old.id; key.after = old.after; key.offset = old.offset;
        }
        keys.remove(old); keys.add(key);
        keys.sort((a, b) -> Integer.compare(a.time, b.time));
        return true;
    }
    double[] position(double time, double x, double y) {
        Key previous = new Key(0, x, y);
        for (Key next : keys) {
            if (next.time > time) return route(previous.x, previous.y, next.x, next.y,
                next.path, next.baked, (time - previous.time) / (double)(next.time - previous.time));
            previous = next;
        }
        return new double[]{previous.x, previous.y};
    }
    static double[] route(double x, double y, double endX, double endY, List<double[]> path, double progress) {
        return route(x, y, endX, endY, path, false, progress);
    }
    static double[] route(double x, double y, double endX, double endY, List<double[]> path,
                          boolean baked, double progress) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{x, y});
        for (int i = 1; i < path.size() - 1; i++) points.add(path.get(i));
        points.add(new double[]{endX, endY});
        if (!baked) points = eased(points);
        double length = 0;
        for (int i = 1; i < points.size(); i++) length += distance(points.get(i-1), points.get(i));
        double remaining = Math.max(0, Math.min(1, progress)) * length;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i-1), b = points.get(i);
            double segment = distance(a, b);
            if (remaining <= segment && segment > 0) {
                double f = remaining / segment;
                return new double[]{a[0] + (b[0]-a[0])*f, a[1] + (b[1]-a[1])*f};
            }
            remaining -= segment;
        }
        return new double[]{endX, endY};
    }

    /** How far one pass may pull a point: the width of a finger's tremor, and nothing wider. */
    private static final double TREMOR = .01;

    /**
     * The polyline a finger left, with its wobble taken out and its course left in. Each point
     * between the ends is pulled halfway towards the middle of its neighbours, twice: that is
     * exactly enough to erase what alternates from one sample to the next, which is what a
     * tremor is. The pull is capped at {@link #TREMOR}, so a corner somebody meant to draw is
     * rounded off rather than cut across. The ends never move — they are where the stroke was
     * anchored, on the players it joins.
     *
     * <p>Smoothed here rather than when the stroke is written down, so the ball and the player
     * travel along the very line the board draws, and so notes taken before this read the same
     * way as notes taken after it.
     */
    static List<double[]> eased(List<double[]> points) {
        if (points.size() < 3) return points;
        List<double[]> line = new ArrayList<>();
        for (double[] point : points) line.add(new double[]{point[0], point[1]});
        for (int pass = 0; pass < 2; pass++) {
            double[] previous = line.get(0);
            for (int i = 1; i < line.size() - 1; i++) {
                double[] current = line.get(i), next = line.get(i + 1);
                double dx = ((previous[0] + next[0]) / 2 - current[0]) / 2;
                double dy = ((previous[1] + next[1]) / 2 - current[1]) / 2;
                double pull = Math.hypot(dx, dy), share = pull > TREMOR ? TREMOR / pull : 1;
                previous = current;
                line.set(i, new double[]{current[0] + dx * share, current[1] + dy * share});
            }
        }
        return line;
    }

    /**
     * A stroke reduced to the number of points the log accepts, spread at equal intervals
     * along the gesture. The finger is no longer cut off after thirty-two samples: the whole
     * gesture is kept while it is being drawn, then brought down to that limit. Both ends stay
     * exactly where they were.
     */
    static List<double[]> thinned(List<double[]> points, int limit) {
        List<double[]> result = new ArrayList<>();
        if (points.size() <= limit || limit < 2) {
            for (double[] point : points) result.add(new double[]{point[0], point[1]});
            return result;
        }
        double[] along = new double[points.size()];
        for (int i = 1; i < points.size(); i++) along[i] = along[i-1] + distance(points.get(i-1), points.get(i));
        double total = along[along.length - 1];
        int segment = 1;
        for (int k = 0; k < limit; k++) {
            if (k == limit - 1) { double[] last = points.get(points.size() - 1); result.add(new double[]{last[0], last[1]}); break; }
            double target = total * k / (limit - 1);
            while (segment < points.size() - 1 && along[segment] < target) segment++;
            double[] a = points.get(segment - 1), b = points.get(segment);
            double span = along[segment] - along[segment - 1], f = span == 0 ? 0 : (target - along[segment - 1]) / span;
            result.add(new double[]{a[0] + (b[0]-a[0])*f, a[1] + (b[1]-a[1])*f});
        }
        return result;
    }

    private static double distance(double[] a, double[] b) { return Math.hypot(b[0]-a[0], b[1]-a[1]); }
}
