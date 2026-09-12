package fr.fonote;

/** Scénarios de montage indépendants d'Android. */
public final class SequenceCheck {
    private static void near(double a, double b) {
        if (Math.abs(a-b) > 1e-7) throw new AssertionError(a + " != " + b);
    }
    public static void main(String[] args) throws Exception {
        Diagram d = new Diagram();
        Diagram.Token a = new Diagram.Token("", "home", "A", .1, .2);
        Diagram.Token b = new Diagram.Token("", "home", "B", .4, .5);
        Diagram.Token c = new Diagram.Token("", "home", "C", .3, .7);
        d.tokens.add(a); d.tokens.add(b); d.tokens.add(c);
        Sequence s = new Sequence(d);
        s.place(a, 12, .7, .2);
        assert d.duration() == 12 : "La lecture doit finir à la dernière clé";
        near(d.position(a, 0)[0], .1); near(d.position(a, 6)[0], .4);
        s.place(a, 6, .5, .3);
        near(d.position(a, 0)[0], .1); near(d.position(a, 12)[0], .7);
        near(d.position(a, 6)[0], .5);
        s.remove(a.track, a.track.at(6)); near(d.position(a, 6)[0], .4);
        s.remove(a.track,a.track.at(0));
        assert s.motions().size()==1 : "Le mouvement depuis le placement initial a disparu";
        s.retime(s.motions().get(0),0,12); assert a.track.at(0)!=null;
        // Ajouter des repères dans une courbe ne change aucun point de l'animation.
        Track.Key end = a.track.at(12); end.path.add(new double[]{.1,.2});
        end.path.add(new double[]{.2,.6}); end.path.add(new double[]{.5,.7}); end.path.add(new double[]{.7,.2});
        double[][] before = new double[13][];
        for (int i=0; i<=12; i++) before[i] = d.position(a,i);
        s.fix(a, 5); s.fix(a, 8);
        for (int i=0; i<=12; i++) { near(d.position(a,i)[0], before[i][0]); near(d.position(a,i)[1], before[i][1]); }
        // Une attente, suivie d'un appel de trois secondes.
        s.fix(b, 0); s.place(b, 20, .4,.5); s.place(b, 50, .8,.5);
        near(d.position(b, 10)[0], .4); near(d.position(b, 35)[0], .6);
        s.place(c, 20, .7,.7);
        Sequence.Motion independent = s.motions().get(s.motions().size()-1);
        Track.Key ballStart = s.fixBall(0); ballStart.owner = a.id; ballStart.flight = true;
        Track.Key reception = s.fixBall(10); reception.owner = b.id; reception.flight = false; reception.kind = Diagram.PASS;
        Sequence.Motion pass = s.motion(reception.id);
        Sequence.Motion run = null;
        for (Sequence.Motion m : s.motions()) if (m.actor.equals(b.id)) run = m;
        assert run != null;
        s.link(run, reception, 10); s.retime(pass, 5, 10);
        assert reception.time == 15 && run.start.time == 25 && run.end.time == 55;
        assert independent.end.time == 20;
        // La réception reste attachée au joueur mobile.
        near(d.ballPosition(15)[0], d.position(b,15)[0]);
        String snapshot = d.toJson().toString();
        try { s.link(pass, run.end, 0); throw new AssertionError("Cycle accepté"); }
        catch (IllegalArgumentException expected) { }
        d = Diagram.from(new org.json.JSONObject(snapshot)); s = new Sequence(d); s.resolve();
        pass = s.motion(reception.id); run = s.motion(run.id());
        s.unlink(run); int fixed = run.start.time; s.retime(pass, 6,10); assert run.start.time == fixed;
        // Une suppression détache les références sans faire sauter leurs temps.
        s.link(run, pass.end, 10); int held = run.start.time;
        s.remove(pass); assert run.start.after.isEmpty() && run.start.time == held;
        d.steps.add(new Diagram.Step("Remise", 20));
        Diagram restored = Diagram.from(d.toJson()); new Sequence(restored).resolve();
        assert restored.steps.get(0).name.equals("Remise");
        assert restored.toJson().getInt("version") == 3;
        TacticalHistory history = new TacticalHistory(); java.util.Map<String,String> entries = new java.util.LinkedHashMap<>();
        entries.put("a","pass"); String initial = TacticalHistory.snapshot(restored,entries); history.reset(initial);
        entries.clear(); String changed = TacticalHistory.snapshot(restored,entries); history.record(changed);
        assert history.canUndo() && !history.canRedo();
        TacticalHistory.restore(history.target(false),entries); history.accept(false); assert entries.containsKey("a");
        assert history.canRedo(); history.record(initial); assert history.canRedo();
        history.record(changed); assert !history.canRedo();
        // Un tacle garde son sens à la relecture ; la lecture pose les joueurs entre deux dixièmes.
        Diagram t = new Diagram();
        Diagram.Token tackler = new Diagram.Token("", "home", "X", .2, .2), victim = new Diagram.Token("", "away", "Y", .6, .2);
        t.tokens.add(tackler); t.tokens.add(victim);
        Sequence ts = new Sequence(t); ts.place(tackler, 10, .6, .2);
        tackler.track.at(10).kind = Diagram.TACKLE;
        Track.Key carried = ts.fixBall(0); carried.owner = victim.id;
        assert victim.id.equals(t.holder(9.9)) : "Le porteur n'est pas reconnu";
        Diagram back = Diagram.from(t.toJson());
        assert Diagram.TACKLE.equals(back.tokens.get(0).track.at(10).kind) : "Le tacle perd son sens";
        assert Diagram.RUN.equals(back.tokens.get(0).track.at(0).kind) : "Une course devient un tacle";
        near(t.position(tackler, 5.5)[0], .42);
        new Sequence(back).resolve();
        if (args.length > 0) java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), restored.toJson().toString());
        System.out.println("SequenceCheck passed");
    }
}
