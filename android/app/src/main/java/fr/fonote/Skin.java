package fr.fonote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The look of everything drawn around the pitch: the colours, the corners they are cut with, and
 * the way a control carries them. A skin is not a recolouring — a button that is filled in one
 * skin is a hairline in another, a card is round here and square there, a heading whispers or
 * shouts — because a palette swapped under an unchanged shape reads as the same application in
 * a different mood, not as a different application.
 *
 * <p>The pitch itself is never skinned. Grass is green whichever look the reader chose and the
 * shirts on it are the colours the clubs play in; a tactical board in lavender would be a board
 * about nothing. What a skin dresses is the chrome: pages, cards, chips, controls and bars.
 *
 * <p>Plain Java, and deliberately: colours are packed here rather than through {@code Color} so
 * that the palettes can be read — and their contrast checked — without a device. See
 * {@code checks/fr/fonote/SkinCheck.java}.
 */
public final class Skin {
    /** How an ordinary control carries its colour: a fill, a fill under a hairline, or an edge. */
    public static final int FILLED = 0, SOFT = 1, OUTLINED = 2;

    public final String key, name, blurb;
    /** The four grounds and the two inks that sit on them. */
    public int background, surface, chip, ink, muted;
    /** The one colour that means "this way", its far end when it travels, and what reads on it. */
    public int accent, accentEnd, onAccent;
    /**
     * The two ends of the ring the pitch lifts a noted player in. Equal to the accent unless the
     * skin signs with a gradient there — the one place a skin is allowed to, because it is the
     * one thing on screen that is neither a control nor a surface.
     */
    public int ring, ringEnd;
    /** Polarity: what a good action is worth, what a bad one is, and the grounds they sit on. */
    public int good, goodFill, bad, badFill, onBad;
    /** The edge a card or an outlined control is drawn with, and the mark a finger leaves. */
    public int hairline, ripple;
    /** Corner radii in dp: cards, ordinary controls, round icon actions. */
    public int card = 16, control = 12, pill = 22;
    public int controls = FILLED;
    /** Cards carry an edge rather than standing on their own colour. */
    public boolean bordered;
    /**
     * No cards at all: rows sit straight on the page and a hairline separates them, the way a
     * feed does it. Removing the surfaces rather than recolouring them is what separates the
     * social applications from the tools — and it leaves the pitch as the one coloured block
     * on the screen, which suits a football notebook.
     */
    public boolean flat;
    /**
     * The half the page is cut in, corner to corner across the screen, or 0 for a page of one
     * colour. Only a pale one: what is written on the page runs across the cut, and has to read
     * on either side of it.
     */
    public int sash;
    /**
     * The turf, from its far end to its near one. The same night green under every skin but
     * one: a pale page can bring it up a shade, a pitch in daylight rather than under
     * floodlights, so that it does not sit on the screen as a dark hole. Green whatever the
     * skin, and never so pale that the white of the lines stops standing out on it.
     */
    public int lawn = rgb(31, 72, 57), lawnEnd = rgb(15, 43, 36);
    /** A light skin: the system bars want dark icons, and the dialogs a light sheet. */
    public boolean light;
    /** Headings: the family they are set in, whether they shout, and how far apart they stand. */
    public String face = "sans-serif";
    public boolean capitals;
    public float tracking;

    private Skin(String key, String name, String blurb) {
        this.key = key; this.name = name; this.blurb = blurb;
    }

    private static int rgb(int red, int green, int blue) {
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }
    private static int veil(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private Skin grounds(int background, int surface, int chip, int ink, int muted) {
        this.background = background; this.surface = surface; this.chip = chip;
        this.ink = ink; this.muted = muted; return this;
    }
    private Skin leading(int accent, int accentEnd, int onAccent) {
        this.accent = accent; this.accentEnd = accentEnd; this.onAccent = onAccent;
        this.ring = accent; this.ringEnd = accent; return this;
    }
    /** A gradient kept for the ring alone, never spent on a button. */
    private Skin signature(int ring, int ringEnd) {
        this.ring = ring; this.ringEnd = ringEnd; return this;
    }
    private Skin rows() { this.flat = true; return this; }
    private Skin cut(int sash) { this.sash = sash; return this; }
    private Skin lawn(int lawn, int lawnEnd) { this.lawn = lawn; this.lawnEnd = lawnEnd; return this; }
    private Skin polarity(int good, int goodFill, int bad, int badFill, int onBad) {
        this.good = good; this.goodFill = goodFill;
        this.bad = bad; this.badFill = badFill; this.onBad = onBad; return this;
    }
    private Skin shape(int card, int control, int pill, int controls, boolean bordered) {
        this.card = card; this.control = control; this.pill = pill;
        this.controls = controls; this.bordered = bordered; return this;
    }
    private Skin edges(int hairline, int ripple, boolean light) {
        this.hairline = hairline; this.ripple = ripple; this.light = light; return this;
    }
    private Skin headings(String face, boolean capitals, float tracking) {
        this.face = face; this.capitals = capitals; this.tracking = tracking; return this;
    }

    /**
     * The ground a colour written on the page has to clear: the page itself, or, where it is cut
     * in two, whichever half the ink reads worse on. What reads there reads on the other half.
     */
    public int page() {
        return sash != 0 && contrast(ink, sash) < contrast(ink, background) ? sash : background;
    }

    /** The accent travels rather than sitting still: a button is painted end to end. */
    public boolean travelling() { return accentEnd != accent; }

    /** Relative luminance, as WCAG 2.1 defines it, of a colour packed 0xAARRGGBB. */
    public static double luminance(int colour) {
        double[] channel = new double[3];
        for (int i = 0; i < 3; i++) {
            double value = ((colour >> (16 - 8 * i)) & 0xFF) / 255.0;
            channel[i] = value <= .03928 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
        }
        return .2126 * channel[0] + .7152 * channel[1] + .0722 * channel[2];
    }

    /** A fifth of the way to white: enough to separate two states, never enough to wash out. */
    public static int paler(int colour) {
        int red = (colour >> 16) & 0xFF, green = (colour >> 8) & 0xFF, blue = colour & 0xFF;
        return (colour & 0xFF000000) | ((red + (255 - red) / 5) << 16)
            | ((green + (255 - green) / 5) << 8) | (blue + (255 - blue) / 5);
    }

    /** A fifth of the way to black, the mirror of {@link #paler}. */
    public static int deeper(int colour) {
        int red = (colour >> 16) & 0xFF, green = (colour >> 8) & 0xFF, blue = colour & 0xFF;
        return (colour & 0xFF000000) | ((red * 4 / 5) << 16) | ((green * 4 / 5) << 8) | (blue * 4 / 5);
    }

    /** Contrast ratio between two colours, as WCAG 2.1 defines it. */
    public static double contrast(int one, int other) {
        double first = luminance(one), second = luminance(other);
        return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
    }

    /** The bar every coloured thing that carries meaning has to clear against its ground. */
    public static final double READABLE = 4.5;

    /**
     * The same colour, moved away from a ground until it can be read on it — up on a dark one,
     * down on a light one — and returned untouched when it already reads.
     *
     * <p>Two things arrive already coloured and cannot simply be repainted: the clubs' own
     * colours, which are the match's identity rather than the theme's, and the palette of a light
     * skin, whose inks are dark by construction because they have to carry on a pale page. Put
     * either on the wrong ground — a club's yellow on white paper, a navy mark on the night green
     * of the pitch — and it is a colour nobody can read. Nudged rather than replaced: a club stays
     * recognisably itself, and an accent stays recognisably the accent.
     */
    public static int readable(int colour, int ground) {
        boolean up = luminance(ground) < .5;
        for (int step = 0; step < 12 && contrast(colour, ground) < READABLE; step++)
            colour = up ? paler(colour) : deeper(colour);
        return colour;
    }

    /** The darkest the pitch gets: the plate a player's name is written on. */
    public static final int GRASS = 0xFF0A1E19;

    /** A colour as the pitch must carry it. The grass is dark under every skin. */
    public static int onGrass(int colour) { return readable(colour, GRASS); }

    private static final int DARK_RIPPLE = veil(60, 255, 255, 255), LIGHT_RIPPLE = veil(38, 0, 0, 0);

    /** Lawn and lemon: Fonote's original colours. */
    private static Skin terrain() {
        return new Skin("terrain", "Terrain", "Vert de pelouse, accent citron. Le thème d’origine.")
            .grounds(rgb(16, 27, 32), rgb(20, 34, 31), rgb(33, 48, 45), rgb(229, 238, 231), rgb(156, 179, 164))
            .leading(rgb(207, 240, 160), rgb(207, 240, 160), rgb(25, 48, 28))
            .polarity(rgb(178, 230, 168), rgb(30, 56, 42), rgb(240, 186, 120), rgb(58, 40, 28), rgb(48, 28, 12))
            .shape(16, 12, 22, FILLED, false)
            .edges(veil(60, 255, 255, 255), DARK_RIPPLE, false)
            .headings("sans-serif", false, 0f);
    }

    /** Slate and lavender: the dark look of modern tools, rounded and edged with a hairline. */
    private static Skin minuit() {
        return new Skin("minuit", "Minuit", "Ardoise profonde, lavande en dégradé, coins larges.")
            .grounds(rgb(11, 14, 20), rgb(20, 25, 35), rgb(31, 38, 52), rgb(230, 234, 242), rgb(140, 150, 170))
            .leading(rgb(167, 139, 250), rgb(125, 211, 252), rgb(23, 16, 54))
            .polarity(rgb(74, 222, 128), rgb(20, 44, 33), rgb(251, 146, 60), rgb(54, 34, 22), rgb(36, 18, 6))
            .shape(20, 14, 22, SOFT, true)
            .edges(veil(46, 150, 165, 200), DARK_RIPPLE, false)
            .headings("sans-serif-medium", false, -.015f);
    }

    /** Paper: the light one, for broad daylight and sunny stands. */
    private static Skin papier() {
        return new Skin("papier", "Papier", "Fond clair, cartes blanches, bleu franc.")
            .grounds(rgb(245, 246, 248), rgb(255, 255, 255), rgb(234, 238, 244), rgb(16, 20, 26), rgb(95, 105, 120))
            .leading(rgb(29, 78, 216), rgb(29, 78, 216), rgb(255, 255, 255))
            .polarity(rgb(13, 116, 84), rgb(219, 244, 234), rgb(166, 74, 10), rgb(253, 238, 220), rgb(255, 255, 255))
            .shape(18, 12, 22, SOFT, true)
            .edges(veil(28, 16, 24, 40), LIGHT_RIPPLE, true)
            .headings("sans-serif", false, -.01f);
    }

    /** Stadium: the TV gallery — black, neon, sharp corners and titles in capitals. */
    private static Skin stade() {
        return new Skin("stade", "Stade", "Noir de régie, néon menthe, angles vifs.")
            .grounds(rgb(6, 8, 12), rgb(14, 18, 24), rgb(23, 29, 38), rgb(233, 242, 246), rgb(130, 145, 158))
            .leading(rgb(0, 240, 181), rgb(0, 240, 181), rgb(0, 32, 24))
            .polarity(rgb(0, 240, 181), rgb(5, 42, 34), rgb(255, 58, 110), rgb(48, 10, 25), rgb(32, 0, 10))
            .shape(6, 6, 10, OUTLINED, true)
            .edges(veil(80, 0, 240, 181), DARK_RIPPLE, false)
            .headings("sans-serif-condensed", true, .1f);
    }

    /** Clay: warm paper, olive and fully round pills. */
    private static Skin argile() {
        return new Skin("argile", "Argile", "Papier chaud, olive, tout en pastilles rondes.")
            .grounds(rgb(248, 244, 237), rgb(255, 253, 249), rgb(238, 230, 219), rgb(33, 28, 22), rgb(118, 104, 88))
            .leading(rgb(73, 105, 58), rgb(73, 105, 58), rgb(255, 255, 255))
            .polarity(rgb(47, 109, 73), rgb(226, 239, 227), rgb(172, 64, 30), rgb(250, 232, 222), rgb(255, 255, 255))
            .shape(22, 22, 22, FILLED, false)
            .edges(veil(34, 60, 45, 30), LIGHT_RIPPLE, true)
            .headings("sans-serif", false, 0f);
    }

    /**
     * The feed: monochrome chrome on a neutral grey, and the app's only gradient, around the
     * player being noted — the story ring, in the one place where it actually means something.
     *
     * <p>Grey and not pure black, although pure black is what the model does. It only holds up
     * on an OLED panel, where it really turns its pixels off; elsewhere it gains nothing and
     * hardens everything laid on it. Grey leaves the pitch to be the only block of colour,
     * which was the point.
     */
    private static Skin fil() {
        return new Skin("fil", "Fil", "Gris neutre, chrome monochrome, anneau en dégradé.")
            .grounds(rgb(18, 18, 18), rgb(18, 18, 18), rgb(38, 38, 38), rgb(250, 250, 250), rgb(168, 168, 168))
            .leading(rgb(46, 144, 255), rgb(46, 144, 255), rgb(0, 24, 46))
            .signature(rgb(249, 168, 37), rgb(214, 41, 118))
            .polarity(rgb(45, 211, 111), rgb(13, 44, 26), rgb(242, 96, 107), rgb(42, 17, 22), rgb(46, 3, 8))
            .shape(0, 10, 22, FILLED, false).rows()
            .edges(rgb(44, 44, 44), DARK_RIPPLE, false)
            .headings("sans-serif-medium", false, -.01f);
    }

    /** Midnight blue and green, with the green on three things only: the messaging app. */
    private static Skin vert() {
        return new Skin("vert", "Vert", "Bleu-nuit, vert sur le bouton et la pastille.")
            .grounds(rgb(11, 20, 26), rgb(11, 20, 26), rgb(32, 44, 51), rgb(233, 237, 239), rgb(134, 150, 160))
            .leading(rgb(33, 192, 99), rgb(33, 192, 99), rgb(5, 41, 27))
            .polarity(rgb(33, 192, 99), rgb(16, 40, 31), rgb(241, 92, 109), rgb(51, 22, 27), rgb(42, 5, 9))
            .shape(0, 10, 22, FILLED, false).rows()
            .edges(rgb(34, 45, 52), DARK_RIPPLE, false)
            .headings("sans-serif", false, 0f);
    }

    /** A single blue, all pills, hairlines between rows: the news feed. */
    private static Skin bleu() {
        return new Skin("bleu", "Bleu", "Un seul bleu, tout en pilules, lignes au filet.")
            .grounds(rgb(21, 32, 43), rgb(21, 32, 43), rgb(30, 39, 50), rgb(247, 249, 249), rgb(139, 152, 165))
            .leading(rgb(29, 155, 240), rgb(29, 155, 240), rgb(4, 18, 28))
            .polarity(rgb(0, 186, 124), rgb(8, 40, 31), rgb(246, 96, 107), rgb(44, 18, 24), rgb(43, 4, 9))
            .shape(0, 22, 22, FILLED, false).rows()
            .edges(rgb(56, 68, 77), DARK_RIPPLE, false)
            .headings("sans-serif-black", false, -.02f);
    }

    /**
     * The Diagonal: the AS Monaco shirt, red and white split from the right shoulder to the left
     * hip. Seen from the front, the split runs from the top-left corner of the screen to the
     * bottom-right one, once, in the background, with the red at the top right, on the heart's
     * side. Cards stay white: with each one split, a list of matches became a pile of shirts.
     *
     * <p>Plain red is kept for what leads somewhere: the main button, links, and the ring of the
     * player being noted, which fades from red to white. On the page, the red half is only a
     * veil, because titles and lists cross the split. A miss takes burnt orange: in red, it
     * would be mistaken for the accent. The lawn is a lighter green than anywhere else: the
     * night green made a hole in the page.
     */
    private static Skin diagonale() {
        return new Skin("diagonale", "Diagonale", "Rouge et blanc coupés en biais, hommage à l’AS Monaco.")
            .grounds(rgb(246, 243, 243), rgb(255, 255, 255), rgb(243, 236, 237), rgb(28, 16, 18), rgb(110, 86, 90))
            .leading(rgb(204, 0, 18), rgb(204, 0, 18), rgb(255, 255, 255))
            .signature(rgb(204, 0, 18), rgb(255, 255, 255))
            .polarity(rgb(22, 116, 70), rgb(222, 242, 230), rgb(176, 72, 0), rgb(253, 236, 222), rgb(255, 255, 255))
            .shape(8, 8, 22, SOFT, true).cut(rgb(251, 226, 229)).lawn(rgb(52, 112, 80), rgb(32, 84, 60))
            .edges(veil(52, 204, 0, 18), LIGHT_RIPPLE, true)
            .headings("sans-serif-condensed", true, .06f);
    }

    /** In the order they are offered; the first is what a reader who never chose ever sees. */
    public static final List<Skin> ALL = new ArrayList<>(Arrays.asList(
        terrain(), minuit(), papier(), stade(), argile(), fil(), vert(), bleu(), diagonale()));

    /** The skin a saved preference names, or the original one — including for a key since dropped. */
    public static Skin of(String key) {
        for (Skin skin : ALL) if (skin.key.equals(key)) return skin;
        return ALL.get(0);
    }
}
