package fr.fonote;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Run with java -ea; covers the run of play, chained changes and a feed that contradicts itself. */
public final class LineupCheck {
    private static Lineup.Change change(int minute, String in, String out) {
        return new Lineup.Change(minute, in, out);
    }

    public static void main(String[] args) {
        List<String> eleven = Arrays.asList("a", "b", "c");
        // AS Monaco, 4 September 2026: Teze on for Vanderson at 52', Mawissa for Idumbo at 87'.
        List<Lineup.Change> monaco = Arrays.asList(
            change(52, "teze", "vanderson"), change(87, "mawissa", "idumbo"));
        List<String> starters = Arrays.asList("hradecky", "vanderson", "idumbo");
        assert Lineup.holders(starters, monaco, 0).get("vanderson").equals("vanderson");
        assert Lineup.holders(starters, monaco, 51).get("vanderson").equals("vanderson");
        assert Lineup.holders(starters, monaco, 52).get("vanderson").equals("teze");
        assert Lineup.holders(starters, monaco, 86).get("idumbo").equals("idumbo");
        assert Lineup.holders(starters, monaco, 87).get("idumbo").equals("mawissa");
        // The spot is kept, never emptied: a side is eleven at every minute of the match.
        for (int minute : new int[]{0, 52, 87, 200})
            assert Lineup.holders(starters, monaco, minute).size() == 3;

        // A spot passes on: whoever came on can go off again.
        List<Lineup.Change> chain = Arrays.asList(change(30, "b", "a"), change(70, "c", "b"));
        assert Lineup.holders(Arrays.asList("a"), chain, 29).get("a").equals("a");
        assert Lineup.holders(Arrays.asList("a"), chain, 30).get("a").equals("b");
        assert Lineup.holders(Arrays.asList("a"), chain, 70).get("a").equals("c");

        // A change naming someone who already left, or a stranger, changes nothing.
        assert Lineup.holders(Arrays.asList("a"), Arrays.asList(
            change(30, "b", "a"), change(40, "d", "a")), 90).get("a").equals("b");
        assert Lineup.holders(eleven, Arrays.asList(change(10, "x", "zzz")), 90).size() == 3;
        // Nobody is ever drawn twice: a man already on cannot come on again.
        Map<String,String> twice = Lineup.holders(eleven, Arrays.asList(change(10, "c", "a")), 90);
        assert twice.get("a").equals("a") && twice.get("c").equals("c");
        assert twice.size() == 3;

        // No changes at all is the ordinary case for a match without a published run of play.
        assert Lineup.holders(eleven, java.util.Collections.emptyList(), 90).size() == 3;
        assert Lineup.holders(java.util.Collections.emptyList(), monaco, 90).isEmpty();
        System.out.println("Lineup: checks passed");
    }
}
