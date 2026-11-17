package com.fixmylife.mobiletrainerlink;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import com.fixmylife.mobiletrainerlink.bridge.BridgeSession;
import com.fixmylife.mobiletrainerlink.bridge.GB;

import java.io.IOException;

/**
 * USB link to the Pico: bulk endpoints of the firmware's vendor (WebUSB) interface,
 * the same one usb_pico_interface.py opens with libusb.
 */
final class UsbTransport implements BridgeSession.Transport {

    private final UsbDeviceConnection conn;
    private final UsbInterface intf;
    private final UsbEndpoint in;
    private final UsbEndpoint out;
    private int writeFailures;

    private UsbTransport(UsbDeviceConnection conn, UsbInterface intf, UsbEndpoint in, UsbEndpoint out) {
        this.conn = conn;
        this.intf = intf;
        this.in = in;
        this.out = out;
    }

    static UsbTransport open(UsbManager um, UsbDevice dev) throws IOException {
        UsbInterface intf = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface f = dev.getInterface(i);
            if (f.getInterfaceClass() != UsbConstants.USB_CLASS_VENDOR_SPEC) continue;
            if (intf == null || f.getId() == GB.VENDOR_INTERFACE) intf = f;
        }
        if (intf == null) throw new IOException("Pico has no vendor USB interface (wrong firmware?)");

        UsbEndpoint epIn = null, epOut = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint e = intf.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (e.getDirection() == UsbConstants.USB_DIR_IN) epIn = e;
            else epOut = e;
        }
        if (epIn == null || epOut == null) throw new IOException("Pico USB endpoints not found");

        UsbDeviceConnection c = um.openDevice(dev);
        if (c == null) throw new IOException("Could not open the Pico (USB permission?)");
        if (!c.claimInterface(intf, true)) {
            c.close();
            throw new IOException("Could not claim the Pico's USB interface");
        }
        UsbTransport t = new UsbTransport(c, intf, epIn, epOut);
        t.setConnected(true);
        return t;
    }

    /**
     * The firmware only answers on the vendor interface after the WebSerial-style
     * SET_CONTROL_LINE_STATE (0x22) request, exactly like the Python tool sends.
     */
    private void setConnected(boolean on) {
        int value = on ? 1 : 0;
        int r = conn.controlTransfer(0x01, 0x22, value, intf.getId(), null, 0, 1000);
        if (r < 0) conn.controlTransfer(0x41, 0x22, value, intf.getId(), null, 0, 1000);
    }

    @Override
    public void write(byte[] data, int len) throws IOException {
        int r = conn.bulkTransfer(out, data, len, 1000);
        if (r < 0) {
            if (++writeFailures >= 3) throw new IOException("USB write failed (Pico unplugged?)");
        } else {
            writeFailures = 0;
        }
    }

    @Override
    public int read(byte[] buf, int timeoutMs) {
        // Read exactly one max-size packet so a full 64-byte packet completes the transfer.
        int len = Math.min(buf.length, Math.max(GB.USB_PACKET, in.getMaxPacketSize()));
        return conn.bulkTransfer(in, buf, len, timeoutMs);
    }

    void close() {
        try {
            setConnected(false);
        } catch (Exception ignored) {
        }
        try {
            conn.releaseInterface(intf);
        } catch (Exception ignored) {
        }
        conn.close();
    }
}
