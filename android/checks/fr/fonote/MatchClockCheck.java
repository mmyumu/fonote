package fr.fonote;

/** Run with java -ea; covers clock recovery, pauses, TV offset, injury time and the countdown. */
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
        // Countdown: seconds under the hour, then hours, then days.
        assert MatchClock.countdown(396).equals("6:36");
        assert MatchClock.countdown(59).equals("0:59");
        assert MatchClock.countdown(3996).equals("1h06");
        assert MatchClock.countdown(3600).equals("1h00");
        assert MatchClock.countdown(3599).equals("59:59");
        assert MatchClock.countdown(86400 + 15*3600 + 3*60 + 40).equals("1j 15h03");
        assert MatchClock.countdown(0).equals("0:00") && MatchClock.countdown(-5).equals("0:00");
        // Marks: the provider's own run of play, and what the clock does with it.
        assert MatchClock.state(0, 0, 0, 0, 0) == null;
        long kickoff = 1_000_000L;
        assert java.util.Arrays.equals(MatchClock.state(kickoff, 0, 0, 0, 0), new long[]{kickoff, 0, 1, 1});
        assert java.util.Arrays.equals(MatchClock.state(kickoff, kickoff + 2760_000L, 0, 0, 0),
            new long[]{kickoff + 2760_000L, 2760, 1, 0});
        long second = kickoff + 3600_000L;
        assert java.util.Arrays.equals(MatchClock.state(kickoff, kickoff + 2760_000L, second, 0, 0),
            new long[]{second, 2700, 2, 1});
        // Whistled off at the announced 90+7, whatever the wall says: the provider's minute wins.
        long end = second + 3127_000L;
        long[] over = MatchClock.state(kickoff, kickoff + 2760_000L, second, end, 97);
        assert java.util.Arrays.equals(over, new long[]{end, 97 * 60, 2, 0});
        assert MatchClock.stamp(over[1], (int)over[2]).equals("90+7");
        // Without a published minute the second half is counted from its own kickoff.
        assert java.util.Arrays.equals(MatchClock.state(kickoff, kickoff + 2760_000L, second, end, 0),
            new long[]{end, 2700 + 3127, 2, 0});
        assert MatchClock.stamp(2760, 1).equals("45+1") && MatchClock.stamp(3720, 2).equals("62");
        System.out.println("MatchClock: 27 checks passed");
    }
}
