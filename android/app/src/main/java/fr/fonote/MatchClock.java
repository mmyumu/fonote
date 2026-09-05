package fr.fonote;

/** Period-aware match clock. Wall time allows recovery after process death. */
final class MatchClock {
    static long seconds(long now, long anchor, long base, boolean running) {
        return Math.max(0, Math.min(150 * 60, base + (running ? Math.max(0, (now-anchor)/1000) : 0)));
    }
    static String display(long seconds, int period) {
        long minute = seconds / 60;
        int boundary = period == 1 ? 45 : 90;
        String prefix = minute >= boundary ? boundary + "+" + (minute-boundary) : String.format(java.util.Locale.FRANCE, "%02d", minute);
        return prefix + String.format(java.util.Locale.FRANCE, ":%02d", seconds % 60);
    }
}
