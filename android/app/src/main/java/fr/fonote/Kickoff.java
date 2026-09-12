package fr.fonote;

/**
 * The wordmark's entrance, played once when the application starts: FOOT first, set tight, then
 * the word opens into FONOTE — the second O and the T slide right, the N grows in the gap it
 * pushes open, the E comes in behind the T — and the spot gives the kick-off, the halfway line
 * running out from it both ways.
 *
 * <p>It blocks nothing: the page is built and usable underneath from the first frame, only the
 * mark moves. The home page is rebuilt when the fixtures arrive, and the view with it, so the
 * clock is kept here, by the activity, and not by the view: a view built halfway through picks
 * the entrance up where the previous one left it.
 *
 * <p>The clock starts when the window is given focus, not at the first frame: the first frames
 * are drawn under the system's splash screen, which stays up a second longer and would have
 * hidden most of the entrance. Until then the mark is at the entrance's first pose — nothing.
 *
 * <p>Plain Java, like {@link Logo}: the timeline is a function of time, read off device.
 */
final class Kickoff {
    /** How long the whole entrance lasts, in milliseconds. */
    static final long DURATION = 1500;
    /** How far apart the two O stand while they spell FOOT: as two rounds of the same word. */
    static final float SNUG = 10;

    /**
     * How long the mark waits for the window's focus before starting anyway: beside another
     * application, in a split screen, the window may never be given it.
     */
    static final long PATIENCE = 2000;

    private long start = -1, firstSeen = -1;
    private boolean over;

    /** An entrance to play, or one already played — or that the reader asked not to see. */
    Kickoff(boolean play) { over = !play; }

    /** The window is in front of the reader: the entrance can start. Only the first call counts. */
    void begin(long now) { if (!over && start < 0) start = now; }

    /** How far into the entrance the mark is at {@code now}. */
    long at(long now) {
        if (over) return DURATION;
        if (start < 0) {
            if (firstSeen < 0) firstSeen = now;
            if (now - firstSeen < PATIENCE) return 0;
            start = now;
        }
        long elapsed = now - start;
        if (elapsed >= DURATION) over = true;
        return Math.min(elapsed, DURATION);
    }

    /** Where everything stands at a moment: the mark at rest once the entrance is over. */
    static final class Pose {
        /** Opacity of F, O, O and T; of N, and of E. */
        float foot = 1, n = 1, e = 1;
        /** How far left the second O and the T stand of their place. */
        float shift;
        /** Where the N is centred, and how wide it is drawn against its full width. */
        float nCentre = Logo.GLYPHS.get(Logo.N).centre(), nWidth = 1;
        /** How far left the E stands of its place. */
        float eShift;
        /** The spot's radius against its own. */
        float spot = 1;
        /** How much of the halfway line is drawn, from where to where. */
        float lineFrom = Logo.LINE[0], lineTo = Logo.LINE[2];
    }

    /** The shift that sets the second O against the first as FOOT has it. */
    static float shut() {
        return Logo.GLYPHS.get(Logo.SECOND_O).left - Logo.GLYPHS.get(Logo.FIRST_O).right - SNUG;
    }

    static Pose pose(long elapsed) {
        float t = elapsed;
        Pose pose = new Pose();
        float open = inOutSine(clamp((t - 560) / 420)), e = outCubic(clamp((t - 760) / 320));
        float spot = clamp((t - 1080) / 200), line = outCubic(clamp((t - 1150) / 350));
        pose.foot = outCubic(clamp(t / 240));
        pose.shift = shut() * (1 - open);
        // The N grows with the gap it opens, and stays in its middle.
        float gapFrom = Logo.GLYPHS.get(Logo.FIRST_O).right, gapTo = Logo.GLYPHS.get(Logo.SECOND_O).left - pose.shift;
        pose.nCentre = (gapFrom + gapTo) / 2;
        pose.nWidth = .35f + .65f * open;
        pose.n = open * open;
        pose.eShift = 18 * (1 - e);
        pose.e = e;
        pose.spot = outBack(spot);
        float centre = Logo.SPOT[0];
        pose.lineFrom = centre + (Logo.LINE[0] - centre) * line;
        pose.lineTo = centre + (Logo.LINE[2] - centre) * line;
        return pose;
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static float outCubic(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }
    private static float inOutSine(float t) { return (float) (1 - Math.cos(Math.PI * t)) / 2; }
    /** Past the mark and back: the spot lands rather than appears. */
    private static float outBack(float t) {
        float c = 1.9f, u = t - 1;
        return 1 + (c + 1) * u * u * u + c * u * u;
    }
}
