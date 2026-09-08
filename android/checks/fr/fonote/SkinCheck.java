package fr.fonote;

import java.util.HashSet;
import java.util.Set;

/**
 * Every skin must be legible, not merely pretty. A palette is easy to admire in a swatch and
 * unreadable on a phone in daylight, so each one is measured here against the contrast ratios
 * of WCAG 2.1: the reading inks well past the AA bar, and every coloured pair a note relies on
 * — an action's tint on its ground, the label on the button that leads somewhere — past it.
 *
 * <p>The bar is set from the original skin, which clears it comfortably; a new one that does not
 * fails here rather than on a reader's screen.
 */
public final class SkinCheck {
    private static double contrast(int one, int other) { return Skin.contrast(one, other); }
    private static void legible(Skin skin, String what, int ink, int ground, double least) {
        double measured = contrast(ink, ground);
        if (measured < least) throw new AssertionError(String.format(
            "%s : %s à %.2f:1, sous le minimum de %.2f:1", skin.name, what, measured, least));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * Kit colours as the provider publishes them, from the palest a club plays in to the darkest:
     * white, a mustard yellow, a mid red, a navy, and near black.
     */
    private static final int[] CLUBS = {0xFFFFFFFF, 0xFFFFCC00, 0xFFDA291C, 0xFF132257, 0xFF0A0A0A};

    public static void main(String[] args) {
        require(Skin.ALL.size() >= 2, "Un choix de thèmes commence à deux");
        require(Skin.of(null) == Skin.ALL.get(0), "Sans préférence, le thème d’origine");
        require(Skin.of("thème-supprimé") == Skin.ALL.get(0), "Une clé inconnue doit retomber sur l’origine");
        require("terrain".equals(Skin.ALL.get(0).key), "Le thème d’origine doit rester le premier");
        Set<String> keys = new HashSet<>(), names = new HashSet<>();
        for (Skin skin : Skin.ALL) {
            require(keys.add(skin.key), "Deux thèmes partagent la clé " + skin.key);
            require(names.add(skin.name), "Deux thèmes partagent le nom " + skin.name);
            require(Skin.of(skin.key) == skin, "Clé non résolue : " + skin.key);
            require(!skin.blurb.isEmpty(), skin.name + " n’explique pas ce qu’il est");

            // The reading inks, on each of the three grounds they are ever set on.
            legible(skin, "texte sur le fond", skin.ink, skin.background, 7);
            legible(skin, "texte sur une carte", skin.ink, skin.surface, 7);
            legible(skin, "texte sur une pastille", skin.ink, skin.chip, 7);
            legible(skin, "texte secondaire sur le fond", skin.muted, skin.background, 4.5);
            legible(skin, "texte secondaire sur une carte", skin.muted, skin.surface, 4.5);
            // The accent leads, so it must be seen on every ground and read on itself.
            legible(skin, "accent sur le fond", skin.accent, skin.background, 4.5);
            legible(skin, "accent sur une pastille", skin.accent, skin.chip, 4.5);
            legible(skin, "libellé du bouton principal", skin.onAccent, skin.accent, 4.5);
            legible(skin, "libellé sur la fin du dégradé", skin.onAccent, skin.accentEnd, 4.5);
            legible(skin, "fin du dégradé sur le fond", skin.accentEnd, skin.background, 4.5);
            // Polarity: an action's tint on its own ground, and on the page it is listed on.
            legible(skin, "action réussie sur son fond", skin.good, skin.goodFill, 4.5);
            legible(skin, "action ratée sur son fond", skin.bad, skin.badFill, 4.5);
            legible(skin, "action réussie sur le fond", skin.good, skin.background, 4.5);
            legible(skin, "action ratée sur le fond", skin.bad, skin.background, 4.5);
            legible(skin, "libellé sur une action ratée", skin.onBad, skin.bad, 4.5);

            // The pitch is night green under every skin, and what it carries is lightened to
            // suit it: a dark skin's marks pass untouched, a light one's are brought up.
            for (int mark : new int[]{skin.accent, skin.good, skin.bad, skin.ring, skin.ringEnd})
                legible(skin, "marque sur le terrain", Skin.onGrass(mark), Skin.GRASS, 4.5);
            // Club colours are the match's, not the theme's: they are nudged, never replaced.
            for (int club : CLUBS) {
                legible(skin, "couleur de club sur la page",
                    Skin.readable(club, skin.background), skin.background, 4.5);
                legible(skin, "couleur de club sur le terrain", Skin.onGrass(club), Skin.GRASS, 4.5);
            }

            // A skin says which way round it is, and the bars and ripples follow that word.
            require(skin.light == Skin.luminance(skin.background) > .5,
                skin.name + " se dit " + (skin.light ? "clair" : "sombre") + " sans l’être");
            // Shapes are a dp radius, and a control style the drawing code knows.
            require(skin.card >= 0 && skin.control >= 0 && skin.pill >= 0,
                skin.name + " porte un rayon négatif");
            require(skin.controls == Skin.FILLED || skin.controls == Skin.SOFT
                || skin.controls == Skin.OUTLINED, skin.name + " dessine ses boutons autrement");
            // An outlined or bordered skin is drawn by its edge: an invisible one draws nothing.
            if (skin.controls != Skin.FILLED || skin.bordered)
                require((skin.hairline >>> 24) >= 20, skin.name + " borde d’un trait invisible");
            // A flat skin has nothing but that filet to separate one row from the next, so it
            // has to be opaque — a veil over an unknown ground is a filet of unknown strength —
            // and it has to be visible against the page it is drawn on.
            if (skin.flat) {
                require((skin.hairline >>> 24) == 255,
                    skin.name + " sépare ses lignes d’un filet translucide");
                require(Skin.contrast(skin.hairline, skin.background) >= 1.15,
                    skin.name + " sépare ses lignes d’un filet invisible");
            }
            require((skin.ripple >>> 24) >= 20, skin.name + " ne répond pas au doigt");
        }
        System.out.println("Skin: " + Skin.ALL.size() + " themes checked");
    }
}
