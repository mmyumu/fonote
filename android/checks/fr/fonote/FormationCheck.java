package fr.fonote;

/** Run with java -ea; covers the position vocabulary, real lineups and the fallback. */
public final class FormationCheck {
    /** AS Monaco, 4-2-3-1 at PSG on 4 September 2026, in ESPN's own order and wording. */
    private static final String[] MONACO = {
        "Goalkeeper", "Right Back", "Left Back", "Left Midfielder", "Center Right Defender",
        "Center Left Defender", "Attacking Midfielder Right", "Right Midfielder", "Forward",
        "Attacking Midfielder", "Attacking Midfielder Left"};
    /** Paris Saint-Germain, the 4-1-2-1-2 diamond of the same match. */
    private static final String[] PSG = {
        "Goalkeeper", "Right Back", "Left Back", "Defensive Midfielder", "Center Right Defender",
        "Center Left Defender", "Right Midfielder", "Attacking Midfielder", "Center Left Forward",
        "Center Right Forward", "Left Midfielder"};

    public static void main(String[] args) {
        // Depth: both midfield qualifiers must beat the bare word they contain.
        assert Formation.band("Goalkeeper") == 0;
        assert Formation.band("Center Left Defender") == 1 && Formation.band("Left Back") == 1;
        assert Formation.band("Defensive Midfielder") == 2;
        assert Formation.band("Left Midfielder") == 3 && Formation.band("Center Midfielder") == 3;
        assert Formation.band("Attacking Midfielder Left") == 4;
        assert Formation.band("Center Right Forward") == 5 && Formation.band("Forward") == 5;
        assert Formation.band("Sweeper") == -1 && Formation.band(null) == -1;
        // Lane: "Center Left" is inside the pair, not on the touchline.
        assert Formation.lane("Center Left Defender") == -1 && Formation.lane("Left Back") == -2;
        assert Formation.lane("Center Right Forward") == 1 && Formation.lane("Right Midfielder") == 2;
        assert Formation.lane("Attacking Midfielder") == 0 && Formation.lane(null) == 0;

        // Orientation, against the reference the project already had: match.json draws the 2018
        // final with Pavard (right back) at x=0.13 and Hernandez (left back) at x=0.87. This side
        // attacks downwards, so a player's own left is the screen's right.
        double[][] france = Formation.spots(new String[]{"Goalkeeper", "Right Back",
            "Center Right Defender", "Center Left Defender", "Left Back", "Right Midfielder",
            "Center Right Midfielder", "Center Left Midfielder", "Left Midfielder",
            "Center Right Forward", "Center Left Forward"}, "4-4-2");
        assert france[1][0] < .5 : "arriere droit a gauche de l'image";
        assert france[4][0] > .5 : "arriere gauche a droite de l'image";
        assert france[1][0] < france[2][0] && france[2][0] < france[3][0] && france[3][0] < france[4][0];
        assert france[5][0] < france[8][0] : "milieu droit a gauche du milieu gauche";
        assert france[0][0] == .5;

        // The lineup that was drawn wrong: a left attacking midfielder is not the striker.
        double[][] monaco = Formation.spots(MONACO, "4-2-3-1");
        int idumbo = 10, brunner = 8, golovin = 9, coulibaly = 6;
        assert monaco[idumbo][1] < monaco[brunner][1] : "Idumbo devant Brunner";
        assert monaco[idumbo][1] == monaco[golovin][1] : "Idumbo sur la ligne de Golovin";
        assert monaco[idumbo][0] > monaco[golovin][0] : "Idumbo, cote gauche, a droite de l'image";
        assert monaco[golovin][0] > monaco[coulibaly][0] : "Coulibaly, cote droit, a gauche de l'image";
        assert monaco[brunner][0] == .5 && shape(monaco).equals("4-2-3-1");
        // A defender stays in the defence however the sheet numbers him.
        assert monaco[5][1] == monaco[1][1] : "Dier avec la defense";

        // The diamond reads as a diamond: one holder, two shuttlers, one tip, two up front.
        double[][] psg = Formation.spots(PSG, "4-1-2-1-2");
        assert shape(psg).equals("4-1-2-1-2");
        assert psg[3][0] == .5 && psg[7][0] == .5 : "Neves et Akliouche dans l'axe";
        assert psg[10][0] > psg[6][0] : "Fabian Ruiz, cote gauche, a droite de l'image";

        // Nothing lands outside its own half, and no two players share a spot.
        for (double[][] side : new double[][][]{monaco, psg}) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (double[] spot : side) {
                assert spot[0] > 0 && spot[0] < 1 && spot[1] > 0 && spot[1] < .5;
                assert seen.add(spot[0] + "/" + spot[1]);
            }
        }

        // Wording we cannot read falls back on the published shape rather than guess a lane.
        String[] mystery = MONACO.clone();
        mystery[4] = "Libero";
        assert shape(Formation.spots(mystery, "4-2-3-1")).equals("4-2-3-1");
        assert shape(Formation.spots(mystery, "sans formation")).equals("4-4-2");
        // Two keepers is a broken sheet, not a shape.
        String[] twoKeepers = MONACO.clone();
        twoKeepers[4] = "Goalkeeper";
        assert shape(Formation.spots(twoKeepers, "4-3-3")).equals("4-3-3");
        assert java.util.Arrays.equals(Formation.lines("4-4-3"), new int[]{4, 4, 2});
        assert java.util.Arrays.equals(Formation.lines("3-4-2-1"), new int[]{3, 4, 2, 1});
        System.out.println("Formation: checks passed");
    }

    /** The shape actually drawn, read back off the spots: players grouped by depth. */
    private static String shape(double[][] spots) {
        java.util.TreeMap<Double,Integer> lines = new java.util.TreeMap<>();
        for (double[] spot : spots) lines.merge(spot[1], 1, Integer::sum);
        StringBuilder drawn = new StringBuilder();
        for (java.util.Map.Entry<Double,Integer> line : lines.entrySet())
            if (line.getKey() != lines.firstKey())
                drawn.append(drawn.length() > 0 ? "-" : "").append(line.getValue());
        return drawn.toString();
    }
}
