package fr.fonote;

/**
 * The wordmark's drawing: every piece inside the frame the view measures, a halfway line that
 * stays a hairline, and a kick-off spot that sits on it, in the middle of the first O, and can
 * be seen on every page — on both halves of one cut in two.
 */
public final class LogoCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean framed(float left, float top, float right, float bottom) {
        return left >= 0 && right <= Logo.WIDTH && top >= Logo.TOP && bottom <= Logo.BOTTOM;
    }

    public static void main(String[] args) {
        Logo.Piece first = null;
        int rings = 0;
        for (Logo.Glyph glyph : Logo.GLYPHS) for (Logo.Piece piece : glyph.pieces) {
            float[] at = piece.at;
            if (piece.shape == Logo.OUTLINE) {
                float left = Float.MAX_VALUE, right = -Float.MAX_VALUE, top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
                for (int i = 0; i < at.length; i += 2) {
                    left = Math.min(left, at[i]); right = Math.max(right, at[i]);
                    top = Math.min(top, at[i + 1]); bottom = Math.max(bottom, at[i + 1]);
                }
                require(framed(left, top, right, bottom), "Une lettre déborde du cadre du logo");
            } else {
                require(framed(at[0] - at[2], at[1] - at[2], at[0] + at[2], at[1] + at[2]), "Un O déborde du cadre du logo");
                if (rings++ == 0) first = piece;
            }
        }
        require(rings == 2, "Deux O");
        require(Logo.GLYPHS.size() == 6, "Six lettres : F, O, N, O, T, E");
        require(Logo.GLYPHS.get(Logo.FIRST_O).pieces.get(0) == first, "Le premier O est le deuxième glyphe");
        for (int i = 1; i < Logo.GLYPHS.size(); i++)
            require(Logo.GLYPHS.get(i).left > Logo.GLYPHS.get(i - 1).right, "Les lettres se suivent sans se toucher");

        float[] line = Logo.LINE, spot = Logo.SPOT;
        float thin = line[3] - line[1];
        require(framed(line[0], line[1], line[2], line[3]), "La ligne médiane déborde du cadre du logo");
        require(thin <= 6, "La ligne médiane doit rester un filet, pas une barre : un O barré devient un Θ");
        require(Logo.GAP > thin, "La ligne doit s’écarter des lettres plus qu’elle n’est épaisse, ou elle les touche");

        float hole = Math.min(first.at[3], first.at[4]);
        require(spot[0] == first.at[0] && spot[1] == first.at[1], "Le point d’engagement est au centre du premier O");
        require(spot[1] > line[1] && spot[1] < line[3], "Le point d’engagement est sur la ligne médiane");
        require(spot[2] * 2 > thin, "Le point doit dépasser de la ligne, ou il s’y confond");
        require(spot[2] + Logo.GAP < hole, "Le point d’engagement ne doit pas boucher le rond");

        // The spot is a shape that has to be seen: the 3:1 WCAG 2.1 asks of one, on every ground.
        for (Skin skin : Skin.ALL) {
            int[] grounds = skin.sash == 0 ? new int[] {skin.background} : new int[] {skin.background, skin.sash};
            for (int ground : grounds) {
                double measured = Skin.contrast(skin.accent, ground);
                require(measured >= 3, String.format("%s : point d’engagement à %.2f:1", skin.name, measured));
            }
        }
        System.out.println("Logo: " + Skin.ALL.size() + " themes checked");
    }
}
