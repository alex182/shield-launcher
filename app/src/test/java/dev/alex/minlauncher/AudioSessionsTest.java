package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class AudioSessionsTest {

    /** Cut from the Office SHIELD's dump, Tidal playing (2026-11-01). */
    private static final String DUMP = String.join("\n",
            "Notification Clients:",
            "   pid    uid  name",
            "  3393   1041  audioserver",
            " 16487  10094  org.jellyfin.androidtv",
            " 27978  10095  com.aspiro.tidal",
            "Global session refs:",
            "  session  cnt     pid    uid  name",
            "       97    1   16487  10094  org.jellyfin.androidtv",
            "Output thread 0x2c9a4e5040, name AudioOut_15, tid 3627, type 0 (MIXER):",
            "  3 Tracks of which 0 are active",
            "    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT  G db",
            "       S    100     no   3702     129      86 S  0x604 00000001 00000003  48000  1   d  4   -58",
            "       S    101     no   3702     137      87 S  0x604 00000001 00000003  48000  1   d  4   -58",
            "  0 Effect Chains",
            "Output thread 0x2c9b28b040, name AudioOut_1D, tid 3652, type 0 (MIXER):",
            "  1 Tracks of which 1 are active",
            "    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT  G db",
            "            321    yes  27978     921     732 A  0x000 00000001 00000003  48000  3   1  2   -50",
            "  0 Effect Chains",
            "  Local log:",
            "   10-30 09:53:16.131 AT::add       (0x2e2cc54420)         309     no  16487     873     620 r",
            "Output thread 0x2c9c321040, name AudioOut_2D, tid 7508, type 0 (MIXER):",
            "    Type     Id Active Client Session Port Id S  Flags   Format Chn mask  SRate ST Usg CT  G db",
            "       P    265    yes   3393       0       0 A  0x000 00000001 00000003  44100 13   0  0     0",
            "  0 Effect Chains",
            "Input thread 0x2c9c3e1040, name AudioIn_2E, tid 7512, type 1 (RECORD):",
            "    Active     Id Client Session Port Id  S  Flags   Format Chn mask  SRate Source   Server",
            "       yes    264   3393     555       0  A  0x000 00000001 0000000C  44100      0 A293B9CB",
            "  0 Effect Chains");

    private static final AudioSessions.Playing TIDAL = new AudioSessions.Playing(921, 27978, "com.aspiro.tidal");

    @Test
    public void findsThePlayingAppAndItsSession() {
        assertEquals(Collections.singletonList(TIDAL), AudioSessions.active(DUMP, 29133));
    }

    @Test
    public void leavesOutOurOwnTracks() {
        assertEquals(Collections.emptyList(), AudioSessions.active(DUMP, 27978));
    }

    @Test
    public void listsEachActiveSessionOnce() {
        String two = DUMP.replace("     no   3702     129", "    yes   3702     129")
                .replace("     no   3702     137", "    yes   3702     921");
        assertEquals(Arrays.asList(new AudioSessions.Playing(129, 3702, ""), new AudioSessions.Playing(921, 3702, "")),
                AudioSessions.active(two, 1));
    }

    @Test
    public void noPermissionMeansNoSessions() {
        String denied = "Permission Denial: can't dump AudioFlinger from pid=29133, uid=10101";
        assertTrue(AudioSessions.denied(denied));
        assertEquals(Collections.emptyList(), AudioSessions.active(denied, 1));
    }
}
