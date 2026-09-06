package fr.fonote;

/**
 * Display name for a crowded pitch: "Zinedine Zidane" reads as "Z. Zidane".
 * Only the given name is reduced, so particles and compound surnames survive
 * ("Virgil van Dijk" stays "V. van Dijk") and a mononym is left alone.
 */
public final class PlayerName {
    private PlayerName() {}

    public static String shorten(String full) {
        String name = full == null ? "" : full.trim().replaceAll("\\s+", " ");
        int space = name.indexOf(' ');
        if (space < 0) return name;
        String first = name.substring(0, space);
        // An initial only helps when it stands for a letter; anything else is kept as written.
        if (!Character.isLetter(first.charAt(0))) return name;
        return first.charAt(0) + ". " + name.substring(space + 1);
    }
}
