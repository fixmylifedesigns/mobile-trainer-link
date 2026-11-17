package com.fixmylife.mobiletrainerlink.bridge;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Handles the DNS lookups the cartridge makes. Mobile Trainer ships with the old DION DNS
 * servers (210.196.3.183 / 210.141.112.163), which are long gone, so every UDP packet to
 * port 53 is caught here instead of being sent out.
 *
 * LOCAL:   answer on the phone. Nintendo's Mobile System GB hosts (*.dion.ne.jp,
 *          *.datacenter.ne.jp) go to the configured game server (e.g. a REON server);
 *          per-host overrides win over everything; other names use the phone's own DNS.
 * FORWARD: relay the query to a DNS server you run (e.g. the one REON's docker setup
 *          provides) and make the reply look like it came from the address the game asked.
 * OFF:     pass the packet through untouched.
 */
public final class DnsResponder {

    public enum Mode { LOCAL, FORWARD, OFF }

    public interface Resolver {
        /** Returns 4 IPv4 bytes or null. */
        byte[] resolveA(String host);
    }

    public static final String[] NINTENDO_SUFFIXES = {"dion.ne.jp", "datacenter.ne.jp"};

    private volatile Mode mode = Mode.LOCAL;
    private volatile byte[] gameServer;          // null = not configured
    private volatile InetSocketAddress upstream; // FORWARD mode
    private volatile Map<String, byte[]> overrides = Collections.emptyMap();
    private volatile boolean useSystemDns = true;
    private volatile Resolver resolver = DnsResponder::systemResolve;
    private volatile SocketBridge.Log log;

    public void setMode(Mode m) { mode = m; }
    public Mode getMode() { return mode; }
    public void setGameServer(byte[] ip) { gameServer = ip; }
    public void setUpstream(InetSocketAddress a) { upstream = a; }
    public InetSocketAddress getUpstream() { return upstream; }
    public void setUseSystemDns(boolean v) { useSystemDns = v; }
    public void setResolver(Resolver r) { resolver = r; }
    public void setLog(SocketBridge.Log l) { log = l; }

    /** Lines like "gameboy.datacenter.ne.jp 192.168.1.20". A leading dot matches subdomains. */
    public void setOverrides(String text) {
        Map<String, byte[]> m = new HashMap<>();
        if (text != null) {
            for (String line : text.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("[\\s=]+");
                if (parts.length < 2) continue;
                byte[] ip = AddrCodec.parseIpv4(parts[1]);
                if (ip != null) m.put(normalize(parts[0]), ip);
            }
        }
        overrides = m;
    }

    private static String normalize(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".") && h.length() > 1 && !h.equals(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    static boolean matchesSuffix(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }

    public static boolean isNintendoHost(String host) {
        for (String s : NINTENDO_SUFFIXES) if (matchesSuffix(host, s)) return true;
        return false;
    }

    /** Resolves a host name using the overrides / game server / phone DNS rules. */
    public byte[] lookup(String rawHost) {
        String host = normalize(rawHost);
        Map<String, byte[]> ov = overrides;
        byte[] hit = ov.get(host);
        if (hit != null) return hit;
        for (Map.Entry<String, byte[]> e : ov.entrySet()) {
            String k = e.getKey();
            if (k.startsWith(".") && (host.endsWith(k) || host.equals(k.substring(1)))) return e.getValue();
        }
        if (isNintendoHost(host)) return gameServer; // never leak these to the real internet
        if (!useSystemDns) return null;
        Resolver r = resolver;
        return r == null ? null : r.resolveA(host);
    }

    static byte[] systemResolve(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a instanceof Inet4Address) return a.getAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Builds a reply for a DNS query packet, or returns null if the packet is not a query
     * we can parse (then the caller simply drops it, like a lost UDP packet).
     */
    public byte[] answer(byte[] q) {
        if (q == null || q.length < 12) return null;
        if ((q[2] & 0x80) != 0) return null;                 // not a query
        int qd = GB.be16(q, 4);
        if (qd < 1) return null;
        StringBuilder name = new StringBuilder();
        int p = 12;
        while (true) {
            if (p >= q.length) return null;
            int l = q[p] & 0xFF;
            if (l == 0) { p++; break; }
            if ((l & 0xC0) != 0 || p + 1 + l > q.length) return null;
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < l; i++) name.append((char) (q[p + 1 + i] & 0xFF));
            p += 1 + l;
        }
        if (p + 4 > q.length) return null;
        int qtype = GB.be16(q, p);
        int qEnd = p + 4;

        String host = name.toString();
        byte[] ip = null;
        boolean wantsA = qtype == 1 || qtype == 255;
        if (wantsA) ip = lookup(host);

        SocketBridge.Log l = log;
        if (l != null) {
            l.log("DNS " + host + " -> " + (ip == null ? (wantsA ? "not found" : "type " + qtype) : ipString(ip)));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(q[0]);
        out.write(q[1]);
        out.write(0x80 | (q[2] & 0x01));                     // QR=1, opcode 0, RD copied
        int rcode = (wantsA && ip == null) ? 3 : 0;          // NXDOMAIN when we have nothing
        out.write(0x80 | rcode);                             // RA=1
        out.write(0); out.write(1);                          // QDCOUNT
        out.write(0); out.write(ip != null ? 1 : 0);         // ANCOUNT
        out.write(0); out.write(0);                          // NSCOUNT
        out.write(0); out.write(0);                          // ARCOUNT
        out.write(q, 12, qEnd - 12);                         // question, verbatim
        if (ip != null) {
            out.write(0xC0); out.write(0x0C);                // pointer to the question name
            out.write(0); out.write(1);                      // TYPE A
            out.write(0); out.write(1);                      // CLASS IN
            out.write(0); out.write(0); out.write(0); out.write(60); // TTL
            out.write(0); out.write(4);
            out.write(ip, 0, 4);
        }
        return out.toByteArray();
    }

    static String ipString(byte[] ip) {
        return (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
    }
}
