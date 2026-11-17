package com.fixmylife.mobiletrainerlink.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class BridgeCoreTest {

    private static byte[] query(String host) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0xAB); o.write(0xCD); o.write(1); o.write(0);
        o.write(0); o.write(1); for (int i = 0; i < 6; i++) o.write(0);
        for (String label : host.split("\\.")) {
            o.write(label.length());
            o.write(label.getBytes(), 0, label.length());
        }
        o.write(0); o.write(0); o.write(1); o.write(0); o.write(1);
        return o.toByteArray();
    }

    @Test
    public void nintendoHostsGoToGameServer() {
        DnsResponder d = new DnsResponder();
        d.setUseSystemDns(false);
        d.setGameServer(new byte[]{(byte) 192, (byte) 168, 1, 20});
        byte[] q = query("gameboy.datacenter.ne.jp");
        byte[] r = d.answer(q);
        assertEquals(q.length + 16, r.length);
        assertEquals((byte) 0xAB, r[0]);
        assertEquals(1, r[7]);
        assertArrayEquals(new byte[]{(byte) 192, (byte) 168, 1, 20},
                java.util.Arrays.copyOfRange(r, r.length - 4, r.length));
    }

    @Test
    public void nintendoHostsWithoutServerAreNxdomain() {
        DnsResponder d = new DnsResponder();
        d.setResolver(h -> new byte[]{1, 2, 3, 4});
        assertNull(d.lookup("pop.d6.dion.ne.jp"));
        byte[] r = d.answer(query("pop.d6.dion.ne.jp"));
        assertEquals(3, r[3] & 0x0F);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, d.lookup("www.pokemon.co.jp"));
    }

    @Test
    public void overridesWin() {
        DnsResponder d = new DnsResponder();
        d.setGameServer(new byte[]{10, 0, 0, 1});
        d.setOverrides("# comment\nmgb.dion.ne.jp 10.0.0.9\n.example.jp=10.0.0.8\n");
        assertArrayEquals(new byte[]{10, 0, 0, 9}, d.lookup("MGB.dion.ne.jp."));
        assertArrayEquals(new byte[]{10, 0, 0, 8}, d.lookup("a.b.example.jp"));
        assertArrayEquals(new byte[]{10, 0, 0, 1}, d.lookup("gbaa.dion.ne.jp"));
    }

    @Test
    public void parserSplitsFramesAcrossPackets() {
        byte[] body = {GB.MA_OPEN, 0, 1, 1, 0, 0};
        byte[] frame = BridgeSession.frame(GB.CMD_DATA, 1, body);
        FrameParser p = new FrameParser();
        List<FrameParser.Frame> a = p.feed(frame, 0, 4);
        assertTrue(a.isEmpty());
        byte[] rest = new byte[frame.length - 4 + 1];
        System.arraycopy(frame, 4, rest, 0, frame.length - 4);
        rest[rest.length - 1] = (byte) 0xCA; // a one-byte ack right after
        List<FrameParser.Frame> b = p.feed(rest, 0, rest.length);
        assertEquals(2, b.size());
        assertEquals(GB.CMD_DATA, b.get(0).cmd);
        assertTrue(b.get(0).checksumOk);
        assertArrayEquals(body, b.get(0).data);
        assertEquals(0xCA, b.get(1).cmd);
    }

    @Test
    public void addrRoundTrip() throws Exception {
        java.net.InetSocketAddress a = new java.net.InetSocketAddress(
                java.net.InetAddress.getByAddress(new byte[]{(byte) 210, (byte) 196, 3, (byte) 183}), 53);
        byte[] w = AddrCodec.write(a);
        assertArrayEquals(new byte[]{1, 0, 53, (byte) 210, (byte) 196, 3, (byte) 183}, w);
        assertEquals(a, AddrCodec.read(w, 0));
        assertNull(AddrCodec.read(new byte[]{0}, 0));
    }
}
