package fr.fonote;

/** Run with java -ea; covers clock recovery, pauses, TV offset and injury time. */
public final class MatchClockCheck {
    public static void main(String[] args) {
        assert MatchClock.seconds(121000,1000,0,true)==120;
        assert MatchClock.seconds(121000,1000,75,false)==75;
        assert MatchClock.seconds(1000,121000,0,true)==0;
        assert MatchClock.seconds(61000,1000,2700,true)==2760;
        assert MatchClock.seconds(61000,1000,1200,true)==1260;
        assert MatchClock.seconds(Long.MAX_VALUE,0,0,true)==9000;
        assert MatchClock.display(2793,1).equals("45+1:33");
        assert MatchClock.display(5521,2).equals("90+2:01");
        assert MatchClock.display(2700,2).equals("45:00");
        System.out.println("MatchClock: 9 checks passed");
    }
}
