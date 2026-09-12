package fr.fonote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who stands on the pitch at a given minute of the match.
 *
 * <p>A pitch frozen on the eleven who started is wrong from the hour mark on, and noting a player
 * who left twenty minutes ago is worse than not noting at all. ESPN publishes each change as a
 * minute and a pair of players, so the eleven can follow the match.
 *
 * <p>A substitute takes the spot of the player he replaces. That is a drawing convention, not a
 * tactical claim — the feed gives a substitute no position of his own — but it keeps the shape of
 * the side stable, and a second change on the same spot simply passes it on.
 *
 * <p>Nothing here touches a written note: the journal keeps whoever was on it, by id, whether or
 * not he is still playing.
 */
final class Lineup {
    private Lineup() {}

    /** Shirt numbers upwards, and anyone without one last, by name. */
    static final java.util.Comparator<org.json.JSONObject> SHIRT_ORDER =
        java.util.Comparator.comparingInt((org.json.JSONObject player) -> {
            int number = player.optInt("number", 0);
            return number > 0 ? number : Integer.MAX_VALUE;
        }).thenComparing(player -> player.optString("name"), String.CASE_INSENSITIVE_ORDER);

    /** The one person of a side who is on the sheet without a shirt: noted, never placed. */
    static boolean coach(org.json.JSONObject person) {
        return "coach".equals(person.optString("role"));
    }

    /** A published change: the minute it happened, who came on, who went off. */
    static final class Change {
        final int minute;
        final String in, out;
        Change(int minute, String in, String out) { this.minute = minute; this.in = in; this.out = out; }
    }

    /**
     * The player holding each starting spot at {@code minute}, keyed by the starter who opened it.
     *
     * <p>A change naming a player who is not on the pitch — already replaced, or unknown to this
     * composition — is ignored rather than trusted: a feed that contradicts itself must not be
     * able to empty a spot or to put the same man in two places.
     */
    static Map<String,String> holders(List<String> starters, List<Change> changes, int minute) {
        Map<String,String> spots = new LinkedHashMap<>();
        Map<String,String> held = new java.util.HashMap<>();
        for (String starter : starters) {
            if (starter == null || starter.isEmpty() || held.containsKey(starter)) continue;
            spots.put(starter, starter);
            held.put(starter, starter);
        }
        List<Change> ordered = new ArrayList<>(changes);
        // Stable, so two changes in the same minute stay in the order they were published.
        ordered.sort((left, right) -> Integer.compare(left.minute, right.minute));
        for (Change change : ordered) {
            if (change.minute > minute) break;
            String spot = held.remove(change.out);
            if (spot == null || change.in == null || change.in.isEmpty() || held.containsKey(change.in)) {
                if (spot != null) held.put(change.out, spot);
                continue;
            }
            spots.put(spot, change.in);
            held.put(change.in, spot);
        }
        return spots;
    }
}
