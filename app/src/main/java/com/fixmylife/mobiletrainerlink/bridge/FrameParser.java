package com.fixmylife.mobiletrainerlink.bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the byte stream coming from the Pico into GBridge frames.
 * Port of GBridge.consume_cmd() in gbridge.py: a frame is
 * [cmd][length (0-2 bytes, big endian)][body][checksum (2 bytes, big endian)],
 * except commands without a length field, which are a single byte (replies/acks).
 */
public final class FrameParser {

    public static final class Frame {
        public final int cmd;
        public final byte[] data;
        public final boolean checksumOk;

        Frame(int cmd, byte[] data, boolean checksumOk) {
            this.cmd = cmd;
            this.data = data;
            this.checksumOk = checksumOk;
        }
    }

    /** Anything longer than this can only be line noise, so resync instead of waiting forever. */
    private static final int MAX_BODY = 0x1000;

    private byte[] buf = new byte[512];
    private int len = 0;

    public List<Frame> feed(byte[] in, int off, int n) {
        if (len + n > buf.length) {
            byte[] bigger = new byte[Math.max(buf.length * 2, len + n)];
            System.arraycopy(buf, 0, bigger, 0, len);
            buf = bigger;
        }
        System.arraycopy(in, off, buf, len, n);
        len += n;

        List<Frame> out = new ArrayList<>();
        int consumed;
        while ((consumed = parseOne(out)) > 0) {
            System.arraycopy(buf, consumed, buf, 0, len - consumed);
            len -= consumed;
        }
        return out;
    }

    public void reset() {
        len = 0;
    }

    private int parseOne(List<Frame> out) {
        if (len == 0) return 0;
        int cmd = buf[0] & 0xFF;
        int lenSize = GB.lengthFieldSize(cmd);
        if (lenSize < 0) {
            out.add(new Frame(cmd, new byte[0], true));
            return 1;
        }
        int bodyLen;
        if (cmd == GB.CMD_DEBUG_ACK) {
            bodyLen = 1;
        } else {
            if (len < 1 + lenSize) return 0;
            bodyLen = 0;
            for (int i = 0; i < lenSize; i++) bodyLen = (bodyLen << 8) | (buf[1 + i] & 0xFF);
            if (bodyLen > MAX_BODY) return 1; // drop the bogus command byte and resync
        }
        int total = 1 + lenSize + bodyLen + 2;
        if (len < total) return 0;
        byte[] body = new byte[bodyLen];
        System.arraycopy(buf, 1 + lenSize, body, 0, bodyLen);
        int sum = GB.be16(buf, 1 + lenSize + bodyLen);
        out.add(new Frame(cmd, body, GB.checksum(body, 0, bodyLen) == sum));
        return total;
    }
}
