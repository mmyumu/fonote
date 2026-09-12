package fr.fonote;

/**
 * The entrance: it opens on FOOT alone, set tight, and ends on the mark exactly as it stands
 * at rest — any gap between the two and the logo would jump when the animation stops.
 */
public final class KickoffCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static boolean near(float one, float other) { return Math.abs(one - other) < 1e-3f; }

    public static void main(String[] args) {
        Kickoff.Pose rest = new Kickoff.Pose(), end = Kickoff.pose(Kickoff.DURATION);
        require(near(end.foot, rest.foot) && near(end.n, rest.n) && near(end.e, rest.e), "À la fin, toutes les lettres sont là");
        require(near(end.shift, 0) && near(end.eShift, 0), "À la fin, chaque lettre est à sa place");
        require(near(end.nCentre, rest.nCentre) && near(end.nWidth, 1), "À la fin, le N a sa place et sa largeur");
        require(near(end.spot, 1), "À la fin, le point a sa taille");
        require(near(end.lineFrom, Logo.LINE[0]) && near(end.lineTo, Logo.LINE[2]), "À la fin, la ligne est entière");

        // A moment after the start, the reader sees FOOT and nothing else.
        Kickoff.Pose foot = Kickoff.pose(400);
        require(foot.foot > .99f, "FOOT est lisible");
        require(foot.n == 0 && foot.e == 0, "Ni N ni E pendant qu’on lit FOOT");
        require(foot.spot < 1e-3f && !(foot.lineTo > foot.lineFrom), "Ni point ni ligne pendant qu’on lit FOOT");
        float second = Logo.GLYPHS.get(Logo.SECOND_O).left - foot.shift, first = Logo.GLYPHS.get(Logo.FIRST_O).right;
        require(near(second - first, Kickoff.SNUG), "Les deux O de FOOT se suivent comme dans un mot");
        require(second - first < Logo.GLYPHS.get(Logo.N).left - first, "FOOT est serré avant de s’ouvrir");

        // The clock waits for the window's focus, then runs once and stays over.
        Kickoff played = new Kickoff(true);
        require(played.at(1000) == 0 && played.at(1900) == 0, "Sous l’écran de lancement, l’entrée attend");
        played.begin(2000);
        played.begin(2100);
        require(played.at(2600) == 600, "L’horloge part au premier focus, pas aux suivants");
        require(played.at(2000 + Kickoff.DURATION) == Kickoff.DURATION && played.at(0) == Kickoff.DURATION,
            "Une fois jouée, l’entrée ne revient pas");
        Kickoff unfocused = new Kickoff(true);
        unfocused.at(0);
        require(unfocused.at(Kickoff.PATIENCE - 1) == 0 && unfocused.at(Kickoff.PATIENCE + 100) == 0
            && unfocused.at(Kickoff.PATIENCE + 700) == 600, "Sans focus, l’entrée part quand même après l’attente");
        require(new Kickoff(false).at(5) == Kickoff.DURATION, "Sans entrée, le logo est au repos d’emblée");
        System.out.println("Kickoff: checks passed");
    }
}
