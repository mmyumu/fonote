package fr.fonote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which fixtures a followed club is read by: the next one it plays, the last one it played, and
 * whether a given fixture is one of its own at all. All of it independent of list order.
 */
final class FixtureSelection {
    static boolean unfinished(JSONObject fixture) {
        String status = fixture.optString("status");
        return "SCHEDULED".equals(status) || "TIMED".equals(status)
            || "IN_PLAY".equals(status) || "PAUSED".equals(status);
    }

    static boolean contains(String text, String query) {
        return normalized(text).contains(normalized(query).trim());
    }

    private static String normalized(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT);
    }

    static boolean matches(JSONObject fixture, String query) {
        StringBuilder text = new StringBuilder();
        for (String key : new String[]{"title", "date", "utcDate", "stage", "competition", "teams"})
            text.append(' ').append(fixture.optString(key));
        for (String side : new String[]{"homeTeam", "awayTeam"}) {
            JSONObject team = fixture.optJSONObject(side);
            if (team != null) for (String key : new String[]{"name", "shortName", "tla"})
                text.append(' ').append(team.optString(key));
        }
        return contains(text.toString(), query);
    }

    /** Whether a followed club is one of the two sides, whatever the competition around them. */
    static boolean involves(JSONObject fixture, Set<String> clubs) {
        for (String side : new String[]{"homeTeam", "awayTeam"}) {
            JSONObject team = fixture.optJSONObject(side);
            if (team != null && clubs.contains(team.optString("id"))) return true;
        }
        return false;
    }

    /** The next match each club plays: the earliest one still to be kicked off. */
    static Map<String, JSONObject> next(JSONArray fixtures, Set<String> clubs, Instant now) {
        return closest(fixtures, clubs, now, true);
    }

    /**
     * The last match each club played: the latest one that is over. Played means finished — a
     * postponed or cancelled fixture was never a match, and one under way is not one to catch up
     * on yet.
     */
    static Map<String, JSONObject> previous(JSONArray fixtures, Set<String> clubs, Instant now) {
        return closest(fixtures, clubs, now, false);
    }

    /**
     * The fixture nearest to now on one side of it, for each followed club: the same walk either
     * way, since what is ahead and what is behind are read from the same list and kept by the
     * same comparison, turned around.
     */
    private static Map<String, JSONObject> closest(JSONArray fixtures, Set<String> clubs,
                                                   Instant now, boolean ahead) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.optJSONObject(i);
            if (fixture == null) continue;
            String status = fixture.optString("status");
            if (ahead ? !"TIMED".equals(status) && !"SCHEDULED".equals(status)
                      : !"FINISHED".equals(status)) continue;
            Instant kickoff;
            try { kickoff = Instant.parse(fixture.optString("utcDate")); }
            catch (RuntimeException invalid) { continue; }
            if (ahead == kickoff.isBefore(now)) continue;
            for (String side : new String[]{"homeTeam", "awayTeam"}) {
                JSONObject team = fixture.optJSONObject(side);
                String id = team == null ? "" : team.optString("id");
                if (!clubs.contains(id)) continue;
                JSONObject kept = result.get(id);
                if (kept == null || kickoff.isBefore(Instant.parse(kept.optString("utcDate"))) == ahead)
                    result.put(id, fixture);
            }
        }
        return result;
    }
}
