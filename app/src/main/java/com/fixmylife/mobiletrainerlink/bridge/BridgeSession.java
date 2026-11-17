package com.fixmylife.mobiletrainerlink.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Phone side of the GBridge protocol: one call to {@link #step()} sends one USB packet to the
 * Pico and handles the packet it answers with. Port of transfer_func() / SocketThread in
 * usb_pico_interface.py and GBridgeCommand in gbridge.py.
 */
public final class BridgeSession {

    public interface Transport {
        void write(byte[] data, int len) throws IOException;

        /** Reads one packet; returns bytes read, 0 or -1 when nothing arrived in time. */
        int read(byte[] buf, int timeoutMs) throws IOException;
    }

    public interface Listener {
        void onLog(String line);

        /** A debug_line_log() line printed by the Pico firmware / libmobile. */
        void onPicoLine(String line);

        /** DEBUG_INFO answers (GB.INFO_*), body without the kind byte. */
        void onInfo(int kind, byte[] body);

        /** The Pico confirmed a debug command (GB.DBG_*). */
        void onAck(int debugCmd);

        /** Socket traffic happened (for the UI's activity indicator). */
        void onTraffic();
    }

    private static final int READ_TIMEOUT_MS = 100;
    private static final int DEBUG_CHUNK = GB.USB_PACKET - 1 - 1 - 2; // 60

    private final Transport transport;
    private final Listener listener;
    private final SocketBridge sockets;
    private final FrameParser dataParser = new FrameParser();
    private final FrameParser debugParser = new FrameParser();
    private final ConcurrentLinkedQueue<byte[]> debugOut = new ConcurrentLinkedQueue<>();
    private final ByteQueue toPico = new ByteQueue();
    private final byte[] inBuf = new byte[GB.USB_PACKET * 2];
    private final byte[][] lastResult = new byte[2][];  // per channel: framed DATA_PC answer
    private final byte[][] lastPending = new byte[2][]; // per channel: framed STREAM_PC answer
    private byte[] lastSendHeader;                      // body of the DATA frame that announced a SEND
    private final StringBuilder lineBuf = new StringBuilder();

    public volatile long packets;

    public BridgeSession(Transport transport, SocketBridge sockets, Listener listener) {
        this.transport = transport;
        this.sockets = sockets;
        this.listener = listener;
    }

    public SocketBridge sockets() {
        return sockets;
    }

    // ------------------------------------------------------------------ USB loop

    /** Returns true if anything was exchanged, so the caller can skip its idle sleep. */
    public boolean step() throws IOException {
        byte[] pkt;
        boolean sending = false;
        if (toPico.size() > 0) {
            int n = Math.min(GB.USB_MAX_PAYLOAD, toPico.size());
            pkt = new byte[1 + n];
            pkt[0] = (byte) n;
            toPico.take(pkt, 1, n);
            sending = true;
        } else {
            byte[] dbg = debugOut.poll();
            if (dbg != null) {
                pkt = new byte[1 + dbg.length];
                pkt[0] = (byte) (dbg.length | GB.FLAG_DEBUG_TO_PICO);
                System.arraycopy(dbg, 0, pkt, 1, dbg.length);
                sending = true;
            } else {
                pkt = new byte[]{0};
            }
        }
        transport.write(pkt, pkt.length);
        packets++;

        int r = transport.read(inBuf, READ_TIMEOUT_MS);
        if (r <= 0) return sending;
        int hdr = inBuf[0] & 0xFF;
        int n = hdr & 0x3F;
        if (n == 0 || n > r - 1) return sending;
        boolean debug = (hdr & GB.FLAG_MASK) == GB.FLAG_DEBUG_FROM_PICO;
        List<FrameParser.Frame> frames = (debug ? debugParser : dataParser).feed(inBuf, 1, n);
        for (FrameParser.Frame f : frames) handle(f, debug ? 1 : 0);
        return true;
    }

    public void resetState() {
        dataParser.reset();
        debugParser.reset();
        toPico.clear();
        lastResult[0] = lastResult[1] = null;
        lastPending[0] = lastPending[1] = null;
        lastSendHeader = null;
        sockets.closeAll();
    }

    // ------------------------------------------------------------------ frames from the Pico

    private void handle(FrameParser.Frame f, int channel) {
        int upper = f.cmd;
        int response = -1;
        if ((upper & GB.REPLY_F) == 0 && !GB.isNoReply(upper)) response = upper | GB.REPLY_F;
        boolean retryData = upper == (GB.CMD_DATA_PC_FAIL | GB.REPLY_F);
        boolean retryStream = retryData || upper == (GB.CMD_STREAM_PC_FAIL | GB.REPLY_F);

        boolean ok = true;
        int op = -1;
        byte[] args = null;
        if (upper == GB.CMD_DATA) {
            ok = f.checksumOk;
            if (ok && f.data.length > 0) {
                op = f.data[0] & 0xFF;
                args = slice(f.data, 1);
                if (op == GB.MA_SEND) {   // the payload follows in a STREAM frame
                    lastSendHeader = args;
                    op = -1;
                }
            }
        } else if (upper == GB.CMD_STREAM) {
            ok = f.checksumOk;
            if (ok) {
                op = GB.MA_SEND;
                args = f.data;
            }
        } else if (GB.isNoReply(upper)) {
            ok = f.checksumOk;
            handleDebug(upper, f.data, ok);
        }
        if (response >= 0 && !ok) response += 1; // ask the Pico to resend

        if (response >= 0) {
            toPico.put((byte) response);
            if (op >= 0) {
                byte[][] answer = process(op, args, upper == GB.CMD_STREAM);
                if (answer != null) {
                    byte[] data = frame(GB.CMD_DATA_PC, 1, answer[0]);
                    byte[] stream = frame(GB.CMD_STREAM_PC, 2, answer[1]);
                    toPico.put(data);
                    toPico.put(stream);
                    lastResult[channel] = data;
                    lastPending[channel] = stream;
                }
            }
        } else {
            if (retryData && lastResult[channel] != null) toPico.put(lastResult[channel]);
            if (retryStream && lastPending[channel] != null) toPico.put(lastPending[channel]);
        }
    }

    /** Returns {result, pending stream} or null if the op produced no answer. */
    private byte[][] process(int op, byte[] a, boolean fromStream) {
        byte[] empty = new byte[0];
        switch (op) {
            case GB.MA_OPEN:
                return new byte[][]{{(byte) op, (byte) (sockets.open(a) ? 1 : 0)}, empty};
            case GB.MA_CLOSE:
                sockets.close(a);
                return new byte[][]{{(byte) op}, empty};
            case GB.MA_CONNECT:
                return new byte[][]{{(byte) op, (byte) sockets.connect(a)}, empty};
            case GB.MA_LISTEN:
                return new byte[][]{{(byte) op, (byte) (sockets.listen(a) ? 1 : 0)}, empty};
            case GB.MA_ACCEPT:
                return new byte[][]{{(byte) op, (byte) (sockets.accept(a) ? 1 : 0)}, empty};
            case GB.MA_SEND: {
                if (!fromStream || lastSendHeader == null) return null;
                int sent = sockets.send(lastSendHeader, a);
                if (sent > 0) listener.onTraffic();
                return new byte[][]{{(byte) op, (byte) (sent >> 8), (byte) sent}, empty};
            }
            case GB.MA_RECV: {
                SocketBridge.Recv r = sockets.recv(a);
                byte[] addr = AddrCodec.write(r.from);
                byte[] res = new byte[3 + addr.length];
                res[0] = (byte) op;
                res[1] = (byte) (r.code >> 8);
                res[2] = (byte) r.code;
                System.arraycopy(addr, 0, res, 3, addr.length);
                byte[] pending = (r.code > 0 && r.data != null) ? r.data : empty;
                if (pending.length > 0) listener.onTraffic();
                return new byte[][]{res, pending};
            }
            default:
                return null;
        }
    }

    private void handleDebug(int cmd, byte[] data, boolean ok) {
        if (!ok) {
            listener.onLog("Pico debug packet failed its checksum");
            return;
        }
        switch (cmd) {
            case GB.CMD_DEBUG_LINE: {
                int end = 0;
                while (end < data.length && data[end] != 0) end++;
                lineBuf.append(new String(data, 0, end, StandardCharsets.US_ASCII));
                int nl;
                while ((nl = lineBuf.indexOf("\n")) >= 0) {
                    String line = lineBuf.substring(0, nl).trim();
                    lineBuf.delete(0, nl + 1);
                    if (!line.isEmpty()) listener.onPicoLine(line);
                }
                break;
            }
            case GB.CMD_DEBUG_CHAR:
                listener.onPicoLine(hex(data));
                break;
            case GB.CMD_DEBUG_INFO:
                if (data.length > 0) listener.onInfo(data[0] & 0xFF, slice(data, 1));
                break;
            case GB.CMD_DEBUG_ACK:
                if (data.length > 0) listener.onAck(data[0] & 0xFF);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ debug commands to the Pico

    /** Queues a debug command, split into USB-sized pieces exactly like load_command() does. */
    public void queueDebug(int cmdId, byte[] payload) {
        if (payload == null) payload = new byte[0];
        int pieces = Math.max(1, (payload.length + DEBUG_CHUNK - 1) / DEBUG_CHUNK);
        for (int i = 0; i < pieces; i++) {
            int from = i * DEBUG_CHUNK;
            int to = Math.min(payload.length, from + DEBUG_CHUNK);
            byte[] p = new byte[1 + (to - from) + 2];
            p[0] = (byte) cmdId;
            System.arraycopy(payload, from, p, 1, to - from);
            int sum = GB.checksum(p, 0, p.length - 2);
            p[p.length - 2] = (byte) (sum >> 8);
            p[p.length - 1] = (byte) sum;
            debugOut.add(p);
        }
    }

    public void requestStatus() { queueDebug(GB.DBG_STATUS, null); }
    public void requestInfo() { queueDebug(GB.DBG_SEND_IMPL_INFO, null); }
    public void requestNumber() { queueDebug(GB.DBG_SEND_NUMBER_OWN, null); }
    public void startAdapter() { queueDebug(GB.DBG_START, null); }
    public void stopAdapter() { queueDebug(GB.DBG_STOP, null); }
    public void forceSave() { queueDebug(GB.DBG_FORCE_SAVE, null); }

    public void setDevice(int device, boolean unmetered) {
        queueDebug(GB.DBG_UPDATE_DEVICE, new byte[]{(byte) (device | (unmetered ? GB.DEVICE_UNMETERED : 0))});
    }

    /** dnsIndex 1 or 2; null clears it. Stored in the Pico's flash by the firmware. */
    public void setPicoDns(int dnsIndex, InetSocketAddress addr) {
        queueDebug(dnsIndex == 2 ? GB.DBG_UPDATE_DNS2 : GB.DBG_UPDATE_DNS1, AddrCodec.write(addr));
    }

    public void setRelay(InetSocketAddress addr) {
        queueDebug(GB.DBG_UPDATE_RELAY, AddrCodec.write(addr));
    }

    // ------------------------------------------------------------------ helpers

    static byte[] frame(int cmd, int lenSize, byte[] data) {
        if (data == null || data.length == 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 5);
        out.write(cmd);
        if (lenSize == 2) out.write(data.length >> 8);
        out.write(data.length & 0xFF);
        out.write(data, 0, data.length);
        int sum = GB.checksum(data, 0, data.length);
        out.write(sum >> 8);
        out.write(sum & 0xFF);
        return out.toByteArray();
    }

    static byte[] slice(byte[] b, int from) {
        if (from >= b.length) return new byte[0];
        byte[] out = new byte[b.length - from];
        System.arraycopy(b, from, out, 0, out.length);
        return out;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x & 0xFF));
        return sb.toString().trim();
    }

    /** Minimal growable FIFO of bytes. */
    static final class ByteQueue {
        private byte[] buf = new byte[1024];
        private int head, tail;

        int size() { return tail - head; }

        void clear() { head = tail = 0; }

        void put(byte b) {
            ensure(1);
            buf[tail++] = b;
        }

        void put(byte[] b) {
            if (b.length == 0) return;
            ensure(b.length);
            System.arraycopy(b, 0, buf, tail, b.length);
            tail += b.length;
        }

        void take(byte[] dst, int off, int n) {
            System.arraycopy(buf, head, dst, off, n);
            head += n;
            if (head == tail) head = tail = 0;
        }

        private void ensure(int n) {
            if (tail + n <= buf.length) return;
            int used = size();
            byte[] nb = new byte[Math.max(buf.length, (used + n) * 2)];
            System.arraycopy(buf, head, nb, 0, used);
            buf = nb;
            head = 0;
            tail = used;
        }
    }
}
