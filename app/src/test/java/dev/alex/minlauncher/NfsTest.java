package dev.alex.minlauncher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class NfsTest {

    @Test public void parsesHostAndPath() {
        Nfs.Spec s = Nfs.Spec.parse(" 10.10.4.2:/lake/media//shield-launcher/backgrounds/ ");
        assertEquals("10.10.4.2", s.host);
        assertEquals("/lake/media/shield-launcher/backgrounds", s.path);
        assertEquals("/", Nfs.Spec.parse("nas:/").path);
    }

    @Test public void parsesNfsUrl() {
        Nfs.Spec s = Nfs.Spec.parse("nfs://nas.lan/lake/media");
        assertEquals("nas.lan", s.host);
        assertEquals("/lake/media", s.path);
    }

    @Test public void rejectsBadSpecs() {
        for (String bad : new String[] { "", "nas", "nas:", "nas:lake", ":/lake", null }) {
            try {
                Nfs.Spec.parse(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void authSysCredential() throws Exception {
        byte[] cred = new Nfs.Auth(998, 997, new int[] { 44 }).credential();
        Nfs.Xdr.Reader r = new Nfs.Xdr.Reader(cred);
        assertEquals(0, r.u32());                                   // stamp
        assertEquals("shield-launcher", new String(r.opaque(), "UTF-8"));
        assertEquals(998, r.u32());
        assertEquals(997, r.u32());
        assertEquals(1, r.u32());
        assertEquals(44, r.u32());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeIds() { new Nfs.Auth(-1, 0, new int[0]); }

    @Test public void xdrPadsOpaques() throws Exception {
        byte[] b = new Nfs.Xdr.Writer().opaque(new byte[] { 1, 2, 3, 4, 5 }).u64(0x1_0000_0002L).bytes();
        assertEquals(4 + 8 + 8, b.length);
        Nfs.Xdr.Reader r = new Nfs.Xdr.Reader(b);
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, r.opaque());
        assertEquals(0x1_0000_0002L, r.u64());
    }
}
