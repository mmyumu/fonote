package fr.fonote;

/** Period-aware match clock. Wall time allows recovery after process death. */
final class MatchClock {
    static long seconds(long now, long anchor, long base, boolean running) {
        return Math.max(0, Math.min(150 * 60, base + (running ? Math.max(0, (now-anchor)/1000) : 0)));
    }
    /**
     * Time left before kickoff, said the way one would say it out loud: seconds only matter in
     * the last hour, minutes only until the match is a day away.
     */
    static String countdown(long secondsLeft) {
        long left = Math.max(0, secondsLeft);
        long days = left / 86400, hours = left / 3600 % 24, minutes = left / 60 % 60;
        if (days > 0) return String.format(java.util.Locale.FRANCE, "%dj %dh%02d", days, hours, minutes);
        if (hours > 0) return String.format(java.util.Locale.FRANCE, "%dh%02d", hours, minutes);
        return String.format(java.util.Locale.FRANCE, "%d:%02d", minutes, left % 60);
    }
    /**
     * The clock the provider's own marks describe — {anchor, base seconds, period, running} —
     * so a match that is over stops where it stopped instead of running on past its last minute.
     * Null when nothing has been published and the clock on screen is the only one there is.
     */
    static long[] state(long kickoff, long halftime, long secondHalf, long end, long endMinute) {
        if (kickoff <= 0) return null;
        // A whistled-off match stops on the minute the provider whistled — 90+6 as announced,
        // not as wall time recounts it. Half-time is taken from the whistle and not from 45',
        // so first-half stoppage is already inside it.
        if (end > 0) return new long[]{end, endMinute > 0 ? endMinute * 60
            : secondHalf > 0 ? 45 * 60 + Math.max(0, (end - secondHalf) / 1000) : 90 * 60, 2, 0};
        if (secondHalf > 0) return new long[]{secondHalf, 45 * 60, 2, 1};
        if (halftime > 0) return new long[]{halftime, Math.max(0, (halftime - kickoff) / 1000), 1, 0};
        return new long[]{kickoff, 0, 1, 1};
    }
    /** The minute as a referee would say it: 62, 45+2, 90+7. */
    static String stamp(long seconds, int period) {
        long minute = seconds / 60;
        int boundary = period == 1 ? 45 : 90;
        return minute >= boundary ? boundary + "+" + (minute-boundary)
            : String.format(java.util.Locale.FRANCE, "%02d", minute);
    }
    static String display(long seconds, int period) {
        return stamp(seconds, period) + String.format(java.util.Locale.FRANCE, ":%02d", seconds % 60);
    }
}
