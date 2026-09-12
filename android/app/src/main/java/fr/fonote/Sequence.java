package fr.fonote;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Timeline editing, independent of the pitch and its gestures. Keys are shared between
 * adjacent motions; an arrival identifies the motion that precedes it. */
final class Sequence {
    static final class Motion {
        final String actor;
        final Track track;
        final Track.Key start, end;
        Motion(String actor, Track track, Track.Key start, Track.Key end) {
            this.actor = actor; this.track = track; this.start = start; this.end = end;
        }
        String id() { return end.id; }
        int duration() { return end.time - start.time; }
    }
    final Diagram diagram;
    Sequence(Diagram diagram) { this.diagram = diagram; }
    List<Track> tracks() {
        List<Track> result = new ArrayList<>();
        for (Diagram.Token token : diagram.tokens) result.add(token.track);
        result.add(diagram.ball); return result;
    }
    Track.Key key(String id) {
        for (Track track : tracks()) for (Track.Key key : track.keys) if (key.id.equals(id)) return key;
        return null;
    }
    Diagram.Token token(String id) {
        for (Diagram.Token token : diagram.tokens) if (token.id.equals(id)) return token;
        return null;
    }
    List<Motion> motions() {
        List<Motion> result = new ArrayList<>();
        for (Diagram.Token token : diagram.tokens) collect(result, token.id, token.track, token.x, token.y);
        collect(result, "ball", diagram.ball, .5, .5); return result;
    }
    private void collect(List<Motion> result, String actor, Track track, double x, double y) {
        Track.Key previous = new Track.Key(0,x,y);
        previous.id = java.util.UUID.nameUUIDFromBytes((actor+"/origin").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        for (Track.Key next : track.keys) {
            Track.Key a = previous, b = next; previous = next;
            if (b.time == 0) continue;
            if (track == diagram.ball && !a.flight) continue;
            if (track != diagram.ball && Math.hypot(a.x-b.x, a.y-b.y) < .000001) {
                boolean stationary = true;
                for (double[] point : b.path) stationary &= Math.hypot(point[0]-a.x, point[1]-a.y) < .000001;
                if (stationary) continue;
            }
            result.add(new Motion(actor, track, a, b));
        }
    }
    /** The initial anchor stays implicit once the diamond at 0 s is deleted.
     * An explicit edit of that anchor gives it back a persisted key. */
    void materialize(Motion motion) {
        if (motion.track.at(motion.start.time) == null && !motion.track.put(motion.start))
            throw new IllegalArgumentException("Limite de positions atteinte");
    }
    Motion motion(String id) {
        if (id.isEmpty()) return null;
        for (Motion motion : motions()) if (motion.id().equals(id)) return motion;
        return null;
    }
    double[] point(Motion motion, boolean end) {
        Track.Key key = end ? motion.end : motion.start;
        return motion.track == diagram.ball ? diagram.ballKey(key, key.time) : new double[]{key.x, key.y};
    }
    /** A neutral key splits the already smoothed path, without changing the animation. */
    Track.Key insert(Track track, int time, double x, double y) {
        Track.Key existing = track.at(time);
        if (existing != null) return existing;
        if (track.keys.size() >= Track.LIMIT) throw new IllegalArgumentException("Limite de positions atteinte");
        Track.Key previous = null, next = null;
        for (Track.Key key : track.keys) {
            if (key.time < time) previous = key;
            else { next = key; break; }
        }
        Track.Key added = new Track.Key(time, x, y);
        if (track == diagram.ball && previous != null) {
            added.owner = previous.owner; added.flight = previous.flight;
            if (previous.flight) { added.owner = ""; added.kind = next == null ? Diagram.PASS : next.kind; }
        }
        if (previous != null && next != null && (track != diagram.ball || previous.flight)) {
            double[] a = track == diagram.ball ? diagram.ballKey(previous, previous.time) : new double[]{previous.x, previous.y};
            double[] b = track == diagram.ball ? diagram.ballKey(next, next.time) : new double[]{next.x, next.y};
            List<double[]> path = new ArrayList<>(); path.add(a);
            for (int i = 1; i < next.path.size()-1; i++) path.add(next.path.get(i));
            path.add(b); if (!next.baked) path = Track.eased(path);
            double fraction = (time-previous.time)/(double)(next.time-previous.time);
            double length = 0;
            for (int i = 1; i < path.size(); i++) length += distance(path.get(i-1), path.get(i));
            double remain = fraction*length;
            List<double[]> left = new ArrayList<>(), right = new ArrayList<>(); left.add(path.get(0));
            boolean cut = false;
            for (int i = 1; i < path.size(); i++) {
                double[] p = path.get(i-1), q = path.get(i);
                double span = distance(p, q);
                if (!cut && remain <= span) {
                    double f = span == 0 ? 0 : remain/span;
                    double[] middle = {p[0]+(q[0]-p[0])*f, p[1]+(q[1]-p[1])*f};
                    added.x = middle[0]; added.y = middle[1]; left.add(middle); right.add(middle); cut = true;
                }
                if (cut) right.add(q); else { left.add(q); remain -= span; }
            }
            if (cut) {
                added.path.addAll(left); added.baked = true;
                next.path.clear(); next.path.addAll(right); next.baked = true;
            }
        }
        track.put(added); return added;
    }
    private static double distance(double[] a, double[] b) { return Math.hypot(b[0]-a[0], b[1]-a[1]); }
    Track.Key fix(Diagram.Token token, int time) {
        double[] p = diagram.position(token, time);
        if (time > 0 && token.track.at(0) == null) {
            if (token.track.keys.size() > Track.LIMIT-2) throw new IllegalArgumentException("Limite de positions atteinte");
            token.track.put(new Track.Key(0, token.x, token.y));
        }
        return insert(token.track, time, p[0], p[1]);
    }
    Track.Key fixBall(int time) {
        double[] p = diagram.ballPosition(time);
        return insert(diagram.ball, time, p[0], p[1]);
    }
    void place(Diagram.Token token, int time, double x, double y) {
        Track.Key key = fix(token, time); key.x = clamp(x); key.y = clamp(y);
        if (time == 0) { token.x = key.x; token.y = key.y; }
    }
    void remove(Track track, Track.Key key) {
        int at = track.keys.indexOf(key);
        if (at < 0) return;
        if (at+1 < track.keys.size()) { track.keys.get(at+1).path.clear(); track.keys.get(at+1).baked = false; }
        if (track == diagram.ball && at > 0 && at == track.keys.size()-1) track.keys.get(at-1).flight = false;
        track.keys.remove(key); detachMissing();
    }
    void detachMissing() {
        Set<String> ids = new HashSet<>();
        for (Track track : tracks()) for (Track.Key key : track.keys) ids.add(key.id);
        for (Track track : tracks()) for (Track.Key key : track.keys)
            if (!key.after.isEmpty() && !ids.contains(key.after)) { key.after = ""; key.offset = 0; }
    }
    /** Full resolution before assignment: a cycle or conflict leaves no partial time behind. */
    void resolve() {
        validateGeometry();
        Map<String, Track.Key> all = new HashMap<>();
        for (Track track : tracks()) for (Track.Key key : track.keys) {
            if (all.put(key.id, key) != null) throw new IllegalArgumentException("Identifiant de position en double");
        }
        Map<String, Integer> times = new HashMap<>();
        for (Track.Key key : all.values()) resolve(key, all, times);
        for (Track track : tracks()) {
            int previous = -1;
            for (Track.Key key : track.keys) {
                int time = times.get(key.id);
                if (time <= previous) throw new IllegalArgumentException("Les mouvements se chevauchent ou les positions se croisent");
                previous = time;
            }
        }
        for (Track.Key key : all.values()) key.time = times.get(key.id);
    }
    private void validateGeometry() {
        if (diagram.tokens.size()>Diagram.TOKENS || diagram.steps.size()>120)
            throw new IllegalArgumentException("Limite de la séquence atteinte");
        Set<String> actors = new HashSet<>(), steps = new HashSet<>();
        for (Diagram.Token token : diagram.tokens) {
            if (token.id.isEmpty() || token.id.equals("ball") || !actors.add(token.id))
                throw new IllegalArgumentException("Identifiant de joueur invalide");
            coordinate(token.x); coordinate(token.y);
        }
        for (Track track : tracks()) {
            if (track.keys.size()>Track.LIMIT) throw new IllegalArgumentException("Limite de positions atteinte");
            for (Track.Key key : track.keys) {
                if (key.id.isEmpty()) throw new IllegalArgumentException("Identifiant de position invalide");
                coordinate(key.x); coordinate(key.y);
                if (!key.owner.isEmpty() && (track != diagram.ball || !actors.contains(key.owner)))
                    throw new IllegalArgumentException("Porteur inconnu");
                if (key.path.size()>Diagram.POINTS || key.path.size()==1)
                    throw new IllegalArgumentException("Parcours trop long ou incomplet");
                for (double[] point : key.path) { coordinate(point[0]); coordinate(point[1]); }
            }
        }
        for (Diagram.Step step : diagram.steps)
            if (step.name.isEmpty() || step.name.length()>80 || step.time<0 || step.time>Track.END || !steps.add(step.id))
                throw new IllegalArgumentException("Repère invalide");
    }
    private void coordinate(double value) {
        if (!Double.isFinite(value) || value < -.000000001 || value > 1.000000001)
            throw new IllegalArgumentException("Position hors du terrain");
    }
    private void resolve(Track.Key origin, Map<String, Track.Key> all, Map<String, Integer> times) {
        List<Track.Key> chain = new ArrayList<>(); Set<String> visiting = new HashSet<>();
        Track.Key current = origin;
        while (!times.containsKey(current.id)) {
            if (!visiting.add(current.id)) throw new IllegalArgumentException("Ce lien crée une boucle");
            chain.add(current);
            if (current.after.isEmpty()) break;
            current = all.get(current.after);
            if (current == null) throw new IllegalArgumentException("Position liée introuvable");
        }
        for (int i = chain.size()-1; i >= 0; i--) {
            Track.Key key = chain.get(i);
            long time = key.after.isEmpty() ? key.time : (long)times.get(key.after)+key.offset;
            if (time < 0 || time > Track.END) throw new IllegalArgumentException("Temps hors de la séquence (0 à 120 s)");
            times.put(key.id, (int)time);
        }
    }
    void time(Track.Key key, int time) {
        if (key.after.isEmpty()) key.time = time;
        else key.offset = time-key(key.after).time;
    }
    void retime(Motion motion, int start, int duration) {
        if (duration < 1) throw new IllegalArgumentException("La durée doit être positive");
        materialize(motion);
        time(motion.start, start);
        // A duration belongs to the motion, even when its start is tied to a pass.
        motion.end.after = motion.start.id; motion.end.offset = duration;
        resolve();
    }
    void link(Motion motion, Track.Key source, int offset) {
        materialize(motion);
        if (key(source.id) == null) for (Motion candidate : motions())
            if (candidate.start.id.equals(source.id)) { materialize(candidate); break; }
        int duration = motion.duration();
        motion.start.after = source.id; motion.start.offset = offset;
        motion.end.after = motion.start.id; motion.end.offset = duration;
        resolve();
    }
    void unlink(Motion motion) { motion.start.after = ""; motion.start.offset = 0; }
    void remove(Motion motion) {
        if (motion.track == diagram.ball) motion.start.flight = false;
        remove(motion.track, motion.end);
    }
    String warning() {
        for (Motion motion : motions()) if (motion.track == diagram.ball) {
            if (motion.start.owner.isEmpty()) continue;
            Track.Key previous = null;
            for (Track.Key key : diagram.ball.keys) { if (key == motion.start) break; previous = key; }
            if (previous != null && !previous.flight && !previous.owner.equals(motion.start.owner))
                return "Possession à vérifier à " + (motion.start.time/10.0) + " s";
        }
        return "";
    }
    static double clamp(double x) { return Math.max(0, Math.min(1, x)); }
}
