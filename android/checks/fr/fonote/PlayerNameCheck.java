package fr.fonote;

/** Run with java -ea; covers mononyms, particles, spacing and already-shortened names. */
public final class PlayerNameCheck {
    public static void main(String[] args) {
        assert PlayerName.shorten("Zinedine Zidane").equals("Z. Zidane");
        assert PlayerName.shorten("Lloris").equals("Lloris");
        assert PlayerName.shorten("Virgil van Dijk").equals("V. van Dijk");
        assert PlayerName.shorten("N'Golo Kanté").equals("N. Kanté");
        assert PlayerName.shorten("  Kylian   Mbappé ").equals("K. Mbappé");
        assert PlayerName.shorten("J. Doe").equals("J. Doe");
        assert PlayerName.shorten("").equals("");
        assert PlayerName.shorten(null).equals("");
        System.out.println("PlayerName: 8 checks passed");
    }
}
