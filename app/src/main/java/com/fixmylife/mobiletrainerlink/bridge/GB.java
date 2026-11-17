package com.fixmylife.mobiletrainerlink.bridge;

/**
 * Constants of the GBridge USB protocol spoken by the pico-gb-mobile-adapter firmware
 * (https://github.com/Lorenzooone/pico-gb-mobile-adapter). The phone plays the role of
 * the "PC" side that usb_pico_interface.py / gbridge.py implement upstream.
 */
public final class GB {
    private GB() {}

    // USB identifiers of the firmware
    public static final int USB_VID = 0xCAFE;
    public static final int USB_PID = 0x4011;
    public static final int VENDOR_INTERFACE = 2;

    // One USB packet: [count | flags] + up to 63 bytes
    public static final int USB_PACKET = 0x40;
    public static final int USB_MAX_PAYLOAD = USB_PACKET - 1;
    public static final int FLAG_MASK = 0xC0;
    public static final int FLAG_DEBUG_FROM_PICO = 0x80;
    public static final int FLAG_DEBUG_TO_PICO = 0xC0;

    // Frame commands (Pico -> phone)
    public static final int CMD_PING = 0x01;
    public static final int CMD_DEBUG_LINE = 0x02;
    public static final int CMD_DEBUG_CHAR = 0x03;
    public static final int CMD_DEBUG_INFO = 0x04;
    public static final int CMD_DEBUG_LOG = 0x05;
    public static final int CMD_DEBUG_ACK = 0x08;
    public static final int CMD_DATA = 0x0A;
    public static final int CMD_DATA_FAIL = 0x0B;
    public static final int CMD_STREAM = 0x0C;
    public static final int CMD_STREAM_FAIL = 0x0D;

    // Frame commands (phone -> Pico)
    public static final int CMD_DATA_PC = 0x4A;
    public static final int CMD_DATA_PC_FAIL = 0x4B;
    public static final int CMD_STREAM_PC = 0x4C;
    public static final int CMD_STREAM_PC_FAIL = 0x4D;

    public static final int REPLY_F = 0x80;

    // Socket operations carried inside DATA frames
    public static final int MA_OPEN = 0;
    public static final int MA_CLOSE = 1;
    public static final int MA_CONNECT = 2;
    public static final int MA_LISTEN = 3;
    public static final int MA_ACCEPT = 4;
    public static final int MA_SEND = 5;
    public static final int MA_RECV = 6;

    // Debug commands (phone -> Pico), see bridge_debug_commands.c
    public static final int DBG_SEND_EEPROM = 1;
    public static final int DBG_UPDATE_EEPROM = 2;
    public static final int DBG_UPDATE_RELAY = 3;
    public static final int DBG_UPDATE_RELAY_TOKEN = 4;
    public static final int DBG_UPDATE_DNS1 = 5;
    public static final int DBG_UPDATE_DNS2 = 6;
    public static final int DBG_UPDATE_P2P_PORT = 7;
    public static final int DBG_UPDATE_DEVICE = 8;
    public static final int DBG_GET_NUMBER_STATUS = 9;
    public static final int DBG_SEND_IMPL_INFO = 10;
    public static final int DBG_STOP = 11;
    public static final int DBG_START = 12;
    public static final int DBG_STATUS = 13;
    public static final int DBG_SEND_NUMBER_OWN = 14;
    public static final int DBG_SEND_NUMBER_OTHER = 15;
    public static final int DBG_SEND_RELAY_TOKEN = 16;
    public static final int DBG_SET_SAVE_STYLE = 17;
    public static final int DBG_FORCE_SAVE = 18;
    public static final int DBG_SEND_GBRIDGE_CFG = 19;
    public static final int DBG_UPDATE_GBRIDGE_CFG = 20;
    public static final int DBG_ASK_NUMBER = 21;

    // DEBUG_INFO kinds (Pico -> phone)
    public static final int INFO_CFG = 0x01;
    public static final int INFO_NUM_STATUS = 0x02;
    public static final int INFO_IMPL = 0x03;
    public static final int INFO_STATUS = 0x04;
    public static final int INFO_NUMBER = 0x05;
    public static final int INFO_NUMBER_PEER = 0x06;
    public static final int INFO_RELAY_TOKEN = 0x07;
    public static final int INFO_GBRIDGE_CFG = 0x08;

    // Mobile Adapter GB models (mobile_adapter_data.py)
    public static final int DEVICE_BLUE_PDC = 8;
    public static final int DEVICE_YELLOW_CDMA = 9;
    public static final int DEVICE_GREEN_PHS = 10;
    public static final int DEVICE_RED_DDI = 11;
    public static final int DEVICE_UNMETERED = 0x80;

    public static String deviceName(int value) {
        switch (value & 0x7F) {
            case DEVICE_BLUE_PDC: return "Blue (PDC)";
            case DEVICE_YELLOW_CDMA: return "Yellow (cdmaOne)";
            case DEVICE_GREEN_PHS: return "Green (PHS)";
            case DEVICE_RED_DDI: return "Red (DDI)";
            default: return String.format("0x%02X", value & 0x7F);
        }
    }

    /** Size of the length field that follows a frame command, or -1 for single-byte commands. */
    static int lengthFieldSize(int cmd) {
        switch (cmd) {
            case CMD_DATA: return 1;
            case CMD_STREAM:
            case CMD_DEBUG_LINE:
            case CMD_DEBUG_CHAR:
            case CMD_DEBUG_INFO:
            case CMD_DEBUG_LOG: return 2;
            case CMD_DEBUG_ACK: return 0; // fixed 1-byte body
            default: return -1;
        }
    }

    static boolean isNoReply(int cmd) {
        return cmd == CMD_DEBUG_LINE || cmd == CMD_DEBUG_CHAR || cmd == CMD_DEBUG_INFO
                || cmd == CMD_DEBUG_LOG || cmd == CMD_DEBUG_ACK;
    }

    static int checksum(byte[] data, int off, int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) sum = (sum + (data[off + i] & 0xFF)) & 0xFFFF;
        return sum;
    }

    static int be16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
}
