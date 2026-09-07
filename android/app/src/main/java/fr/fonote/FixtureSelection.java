package fr.fonote;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The earliest future, playable fixture for each followed club, independent of list order. */
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

    static Map<String, JSONObject> next(JSONArray fixtures, Set<String> clubs, Instant now) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.optJSONObject(i);
            if (fixture == null) continue;
            String status = fixture.optString("status");
            if (!"TIMED".equals(status) && !"SCHEDULED".equals(status)) continue;
            Instant kickoff;
            try { kickoff = Instant.parse(fixture.optString("utcDate")); }
            catch (RuntimeException invalid) { continue; }
            if (kickoff.isBefore(now)) continue;
            for (String side : new String[]{"homeTeam", "awayTeam"}) {
                JSONObject team = fixture.optJSONObject(side);
                String id = team == null ? "" : team.optString("id");
                if (!clubs.contains(id)) continue;
                JSONObject previous = result.get(id);
                if (previous == null || kickoff.isBefore(Instant.parse(previous.optString("utcDate"))))
                    result.put(id, fixture);
            }
        }
        return result;
    }
}
