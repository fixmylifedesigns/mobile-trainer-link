package com.fixmylife.mobiletrainerlink.bridge;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/** libmobile's address wire format: [type][port hi][port lo][4 or 16 address bytes]. */
public final class AddrCodec {
    private AddrCodec() {}

    public static final int NONE = 0;
    public static final int IPV4 = 1;
    public static final int IPV6 = 2;

    /** Returns null for "no address" or anything malformed. */
    public static InetSocketAddress read(byte[] b, int off) {
        if (b == null || off >= b.length) return null;
        int type = b[off] & 0xFF;
        int hostLen;
        if (type == IPV4) hostLen = 4;
        else if (type == IPV6) hostLen = 16;
        else return null;
        if (b.length < off + 3 + hostLen) return null;
        int port = GB.be16(b, off + 1);
        byte[] host = new byte[hostLen];
        System.arraycopy(b, off + 3, host, 0, hostLen);
        try {
            return new InetSocketAddress(InetAddress.getByAddress(host), port);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public static byte[] write(InetSocketAddress a) {
        if (a == null || a.getAddress() == null) return new byte[]{NONE};
        InetAddress ip = a.getAddress();
        byte[] host = ip.getAddress();
        int type;
        if (ip instanceof Inet4Address) type = IPV4;
        else if (ip instanceof Inet6Address) type = IPV6;
        else return new byte[]{NONE};
        byte[] out = new byte[3 + host.length];
        out[0] = (byte) type;
        out[1] = (byte) (a.getPort() >> 8);
        out[2] = (byte) a.getPort();
        System.arraycopy(host, 0, out, 3, host.length);
        return out;
    }

    /** Parses "1.2.3.4" (IPv4 only, no DNS lookups). Returns null when it isn't a literal. */
    public static byte[] parseIpv4(String s) {
        if (s == null) return null;
        String[] parts = s.trim().split("\\.");
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) return null;
                out[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }
}
