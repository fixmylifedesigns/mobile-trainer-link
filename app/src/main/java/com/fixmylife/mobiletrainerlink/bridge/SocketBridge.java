package com.fixmylife.mobiletrainerlink.bridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;

/**
 * The phone's side of libmobile's socket callbacks (port of GBridgeSocket in gbridge.py).
 * libmobile on the Pico asks for at most two sockets; everything here is non-blocking and is
 * driven from the single USB loop thread.
 *
 * Differences from the Python reference, all in line with libmobile's documented contract:
 *  - connect() returns 0 ("in progress") instead of blocking the whole USB loop;
 *  - a recv() without a buffer reports 0 for "still alive" rather than a byte count;
 *  - the bind port is used as-is (the Python script byte-swaps it with htons()).
 */
public final class SocketBridge {

    public interface Log {
        void log(String line);
    }

    public static final int SOCK_TCP = 0;
    public static final int SOCK_UDP = 1;
    public static final int MAX_CONNECTIONS = 2;
    private static final long CONNECT_TIMEOUT_MS = 20_000;
    private static final int MAX_QUEUED_DATAGRAMS = 32;

    private static final class Datagram {
        final byte[] data;
        final InetSocketAddress from;

        Datagram(byte[] data, InetSocketAddress from) {
            this.data = data;
            this.from = from;
        }
    }

    private static final class Conn {
        int type;
        int addrType;
        int bindPort;
        SocketChannel tcp;
        ServerSocketChannel server;
        DatagramChannel udp;
        long connectStarted;
        byte[] rx = new byte[0];          // TCP bytes read ahead (e.g. by an "alive?" check)
        boolean eof;
        InetSocketAddress udpDefault;     // set by connect() on a UDP socket
        final ArrayDeque<Datagram> udpRx = new ArrayDeque<>();
        InetSocketAddress dnsRealServer;  // FORWARD mode: where we really sent the query
        InetSocketAddress dnsAskedServer; // ...and who the game thinks it asked
    }

    /** Result of recv(): code is a byte count, 0 (nothing yet), -1 (error) or -2 (closed). */
    public static final class Recv {
        public final int code;
        public final byte[] data;
        public final InetSocketAddress from;

        Recv(int code, byte[] data, InetSocketAddress from) {
            this.code = code;
            this.data = data;
            this.from = from;
        }
    }

    private final Conn[] conns = new Conn[MAX_CONNECTIONS];
    private final DnsResponder dns;
    private final Log log;
    public volatile long bytesOut;
    public volatile long bytesIn;

    public SocketBridge(DnsResponder dns, Log log) {
        this.dns = dns;
        this.log = log;
    }

    private void log(String s) {
        if (log != null) log.log(s);
    }

    private Conn get(int c) {
        return (c >= 0 && c < MAX_CONNECTIONS) ? conns[c] : null;
    }

    public int openCount() {
        int n = 0;
        for (Conn c : conns) if (c != null) n++;
        return n;
    }

    // ---- operations, args are the DATA frame body after the operation byte ----

    public boolean open(byte[] a) {
        if (a.length < 5) return false;
        int c = a[0] & 0xFF;
        int type = a[1] & 0xFF;
        int addrType = a[2] & 0xFF;
        int port = GB.be16(a, 3);
        if (c >= MAX_CONNECTIONS || conns[c] != null) return false;
        if (type != SOCK_TCP && type != SOCK_UDP) return false;
        if (addrType != AddrCodec.IPV4 && addrType != AddrCodec.IPV6) return false;
        Conn k = new Conn();
        k.type = type;
        k.addrType = addrType;
        k.bindPort = port;
        if (type == SOCK_UDP) {
            try {
                k.udp = DatagramChannel.open(addrType == AddrCodec.IPV6
                        ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
                k.udp.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                k.udp.bind(new InetSocketAddress(port));
                k.udp.configureBlocking(false);
            } catch (IOException | RuntimeException e) {
                log("UDP open failed: " + e);
                closeQuietly(k);
                return false;
            }
        }
        conns[c] = k;
        log("open #" + c + " " + (type == SOCK_TCP ? "TCP" : "UDP") + (port != 0 ? " port " + port : ""));
        return true;
    }

    public void close(byte[] a) {
        if (a.length < 1) return;
        int c = a[0] & 0xFF;
        Conn k = get(c);
        if (k == null) return;
        closeQuietly(k);
        conns[c] = null;
        log("close #" + c);
    }

    public void closeAll() {
        for (int i = 0; i < MAX_CONNECTIONS; i++) {
            if (conns[i] != null) closeQuietly(conns[i]);
            conns[i] = null;
        }
    }

    private static void closeQuietly(Conn k) {
        try { if (k.tcp != null) k.tcp.close(); } catch (IOException ignored) {}
        try { if (k.server != null) k.server.close(); } catch (IOException ignored) {}
        try { if (k.udp != null) k.udp.close(); } catch (IOException ignored) {}
    }

    /** 1 = connected, 0 = in progress (libmobile calls again), -1 = error. */
    public int connect(byte[] a) {
        if (a.length < 2) return -1;
        int c = a[0] & 0xFF;
        Conn k = get(c);
        InetSocketAddress addr = AddrCodec.read(a, 1);
        if (k == null || addr == null) return -1;
        if (k.type == SOCK_UDP) {
            k.udpDefault = addr;
            return 1;
        }
        try {
            if (k.tcp == null) {
                log("connect #" + c + " " + addr.getAddress().getHostAddress() + ":" + addr.getPort());
                k.tcp = SocketChannel.open();
                k.tcp.configureBlocking(false);
                k.tcp.setOption(StandardSocketOptions.TCP_NODELAY, true);
                if (k.bindPort != 0) {
                    k.tcp.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                    k.tcp.bind(new InetSocketAddress(k.bindPort));
                }
                k.connectStarted = System.currentTimeMillis();
                if (k.tcp.connect(addr)) return 1;
                return 0;
            }
            if (k.tcp.isConnected()) return 1;
            if (k.tcp.isConnectionPending() && k.tcp.finishConnect()) {
                log("connected #" + c);
                return 1;
            }
            if (System.currentTimeMillis() - k.connectStarted > CONNECT_TIMEOUT_MS) {
                log("connect #" + c + " timed out");
                return -1;
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            log("connect #" + c + " failed: " + e.getMessage());
            return -1;
        }
    }

    public boolean listen(byte[] a) {
        if (a.length < 1) return false;
        Conn k = get(a[0] & 0xFF);
        if (k == null || k.type != SOCK_TCP || k.tcp != null) return false;
        try {
            k.server = ServerSocketChannel.open();
            k.server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            k.server.bind(new InetSocketAddress(k.bindPort), 1);
            k.server.configureBlocking(false);
            log("listening on port " + k.bindPort);
            return true;
        } catch (IOException | RuntimeException e) {
            log("listen failed: " + e.getMessage());
            return false;
        }
    }

    public boolean accept(byte[] a) {
        if (a.length < 1) return false;
        Conn k = get(a[0] & 0xFF);
        if (k == null || k.server == null) return false;
        try {
            SocketChannel s = k.server.accept();
            if (s == null) return false;
            s.configureBlocking(false);
            s.setOption(StandardSocketOptions.TCP_NODELAY, true);
            k.server.close();
            k.server = null;
            k.tcp = s;
            log("accepted " + s.getRemoteAddress());
            return true;
        } catch (IOException | RuntimeException e) {
            log("accept failed: " + e.getMessage());
            return false;
        }
    }

    /** header = SEND frame body after the op byte ([conn][addr]); returns bytes sent or -1. */
    public int send(byte[] header, byte[] payload) {
        if (header == null || header.length < 2) return -1;
        int c = header[0] & 0xFF;
        Conn k = get(c);
        if (k == null) return -1;
        InetSocketAddress addr = AddrCodec.read(header, 1);
        try {
            if (k.type == SOCK_TCP) {
                if (k.tcp == null || !k.tcp.isConnected()) return -1;
                int w = k.tcp.write(ByteBuffer.wrap(payload));
                bytesOut += w;
                return w;
            }
            InetSocketAddress dest = addr != null ? addr : k.udpDefault;
            if (dest == null || k.udp == null) return -1;
            if (dest.getPort() == 53 && dns != null && dns.getMode() != DnsResponder.Mode.OFF) {
                if (dns.getMode() == DnsResponder.Mode.LOCAL) {
                    byte[] reply = dns.answer(payload);
                    if (reply != null) queue(k, new Datagram(reply, dest));
                    return payload.length;
                }
                InetSocketAddress up = dns.getUpstream();
                if (up != null) {
                    k.dnsRealServer = up;
                    k.dnsAskedServer = dest;
                    dest = up;
                }
            }
            int w = k.udp.send(ByteBuffer.wrap(payload), dest);
            bytesOut += w;
            return w;
        } catch (IOException | RuntimeException e) {
            log("send #" + c + " failed: " + e.getMessage());
            return -1;
        }
    }

    private static void queue(Conn k, Datagram d) {
        if (k.udpRx.size() >= MAX_QUEUED_DATAGRAMS) k.udpRx.pollFirst();
        k.udpRx.addLast(d);
    }

    /** a = [conn][size hi][size lo][1 if data wanted, 0 for an "alive?" check]. */
    public Recv recv(byte[] a) {
        if (a.length < 4) return new Recv(0, null, null);
        int c = a[0] & 0xFF;
        int size = GB.be16(a, 1);
        boolean wantData = a[3] == 1;
        Conn k = get(c);
        if (k == null) return new Recv(0, null, null);
        try {
            if (k.type == SOCK_TCP) return recvTcp(k, size, wantData);
            return recvUdp(k, size, wantData);
        } catch (IOException | RuntimeException e) {
            log("recv #" + c + " failed: " + e.getMessage());
            return new Recv(-1, null, null);
        }
    }

    private Recv recvTcp(Conn k, int size, boolean wantData) throws IOException {
        if (k.tcp == null || !k.tcp.isConnected()) return new Recv(-1, null, null);
        if (k.rx.length == 0 && !k.eof) {
            ByteBuffer bb = ByteBuffer.allocate(2048);
            int n = k.tcp.read(bb);
            if (n < 0) {
                k.eof = true;
            } else if (n > 0) {
                k.rx = new byte[n];
                bb.flip();
                bb.get(k.rx);
                bytesIn += n;
            }
        }
        if (k.rx.length == 0) return new Recv(k.eof ? -2 : 0, null, null);
        if (!wantData) return new Recv(0, null, null);
        int take = Math.min(size, k.rx.length);
        byte[] out = new byte[take];
        System.arraycopy(k.rx, 0, out, 0, take);
        byte[] rest = new byte[k.rx.length - take];
        System.arraycopy(k.rx, take, rest, 0, rest.length);
        k.rx = rest;
        return new Recv(take, out, null);
    }

    private Recv recvUdp(Conn k, int size, boolean wantData) throws IOException {
        if (k.udp == null) return new Recv(-1, null, null);
        for (int i = 0; i < MAX_QUEUED_DATAGRAMS; i++) {
            ByteBuffer bb = ByteBuffer.allocate(2048);
            SocketAddress src = k.udp.receive(bb);
            if (src == null) break;
            InetSocketAddress from = (InetSocketAddress) src;
            if (k.dnsRealServer != null && from.equals(k.dnsRealServer)) from = k.dnsAskedServer;
            else if (k.udpDefault != null && !from.equals(k.udpDefault)) continue;
            bb.flip();
            byte[] d = new byte[bb.remaining()];
            bb.get(d);
            bytesIn += d.length;
            queue(k, new Datagram(d, from));
        }
        Datagram d = k.udpRx.peekFirst();
        if (d == null) return new Recv(0, null, null);
        if (!wantData) return new Recv(0, null, null);
        k.udpRx.pollFirst();
        int take = Math.min(size, d.data.length);
        byte[] out = new byte[take];
        System.arraycopy(d.data, 0, out, 0, take);
        return new Recv(take, out, d.from);
    }
}
