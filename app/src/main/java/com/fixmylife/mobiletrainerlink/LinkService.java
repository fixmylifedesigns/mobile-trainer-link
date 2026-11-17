package com.fixmylife.mobiletrainerlink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.fixmylife.mobiletrainerlink.bridge.AddrCodec;
import com.fixmylife.mobiletrainerlink.bridge.BridgeSession;
import com.fixmylife.mobiletrainerlink.bridge.DnsResponder;
import com.fixmylife.mobiletrainerlink.bridge.GB;
import com.fixmylife.mobiletrainerlink.bridge.SocketBridge;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the USB loop with the Pico running while the phone sits next to the Game Boy
 * (screen off included). All Mobile Adapter traffic goes through here.
 */
public class LinkService extends Service {

    static final String ACTION_STOP = "com.fixmylife.mobiletrainerlink.STOP";
    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "MTLink";
    private static final int MAX_LOG = 400;

    /** State the screen shows. */
    static final class Status {
        volatile String usb = "Starting…";
        volatile String adapter = "—";
        volatile String version = "";
        volatile String number = "";
        volatile boolean connected;
        volatile long lastTraffic;
        volatile int sockets;
        volatile long bytesUp;
        volatile long bytesDown;
    }

    static final Status status = new Status();
    private static final ArrayDeque<String> logLines = new ArrayDeque<>();
    private static volatile LinkService instance;

    private final DnsResponder dns = new DnsResponder();
    private volatile boolean running;
    private volatile BridgeSession session;
    private Thread loop;
    private PowerManager.WakeLock wakeLock;

    static LinkService get() {
        return instance;
    }

    static void log(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + line;
        Log.i(TAG, line);
        synchronized (logLines) {
            logLines.addLast(stamped);
            while (logLines.size() > MAX_LOG) logLines.pollFirst();
        }
    }

    static String logText(int lastLines) {
        synchronized (logLines) {
            StringBuilder sb = new StringBuilder();
            int skip = Math.max(0, logLines.size() - lastLines);
            int i = 0;
            for (String l : logLines) {
                if (i++ < skip) continue;
                sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }

    static UsbDevice findPico(UsbManager um) {
        if (um == null) return null;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getVendorId() == GB.USB_VID && d.getProductId() == GB.USB_PID) return d;
        }
        return null;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Game Boy link",
                NotificationManager.IMPORTANCE_LOW));
        dns.setLog(LinkService::log);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for the Pico"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        applySettings();
        if (!running) {
            running = true;
            loop = new Thread(this::runLoop, "pico-usb");
            loop.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        releaseWakeLock();
        status.usb = "Stopped";
        status.connected = false;
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, LinkService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("Mobile Trainer Link")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_launcher_fg), "Stop", stop).build())
                .build();
    }

    private void updateNotification(String text) {
        try {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ settings & commands

    void applySettings() {
        dns.setMode(Prefs.dnsMode(this));
        dns.setGameServer(AddrCodec.parseIpv4(Prefs.gameServer(this)));
        byte[] up = AddrCodec.parseIpv4(Prefs.upstreamDns(this));
        InetSocketAddress upstream = null;
        if (up != null) {
            try {
                upstream = new InetSocketAddress(InetAddress.getByAddress(up), 53);
            } catch (IOException ignored) {
            }
        }
        dns.setUpstream(upstream);
        dns.setOverrides(Prefs.overrides(this));
        dns.setUseSystemDns(Prefs.systemDns(this));
    }

    /** Runs a command against the live session; returns false if the Pico isn't connected. */
    boolean withSession(java.util.function.Consumer<BridgeSession> action) {
        BridgeSession s = session;
        if (s == null) return false;
        action.accept(s);
        return true;
    }

    // ------------------------------------------------------------------ USB loop

    private void runLoop() {
        UsbManager um = getSystemService(UsbManager.class);
        String lastWait = "";
        while (running) {
            UsbDevice dev = findPico(um);
            String wait = null;
            if (dev == null) wait = "Waiting for the Pico — plug it into the phone";
            else if (!um.hasPermission(dev)) wait = "Pico found — tap “Allow USB”";
            if (wait != null) {
                status.usb = wait;
                status.connected = false;
                if (!wait.equals(lastWait)) {
                    log(wait);
                    updateNotification(wait);
                    lastWait = wait;
                }
                releaseWakeLock();
                if (!sleep(1000)) break;
                continue;
            }
            lastWait = "";
            UsbTransport t = null;
            SocketBridge sockets = new SocketBridge(dns, LinkService::log);
            try {
                t = UsbTransport.open(um, dev);
                if (Prefs.keepAwake(this)) acquireWakeLock();
                BridgeSession s = new BridgeSession(t, sockets, listener);
                session = s;
                status.connected = true;
                status.usb = "Connected to the Pico";
                log("Pico connected");
                updateNotification("Connected — Game Boy link ready");
                s.requestInfo();
                s.requestStatus();

                long lastBusy = SystemClock.elapsedRealtime();
                long lastCheck = lastBusy;
                while (running) {
                    boolean busy = s.step();
                    long now = SystemClock.elapsedRealtime();
                    if (busy) lastBusy = now;
                    if (now - lastCheck > 1500) {
                        lastCheck = now;
                        if (findPico(um) == null) throw new IOException("Pico unplugged");
                        status.sockets = sockets.openCount();
                        status.bytesUp = sockets.bytesOut;
                        status.bytesDown = sockets.bytesIn;
                    }
                    if (!busy && !sleep(now - lastBusy < 5000 ? 2 : 12)) break;
                }
            } catch (IOException | RuntimeException e) {
                log("USB: " + e.getMessage());
            } finally {
                session = null;
                status.connected = false;
                sockets.closeAll();
                if (t != null) t.close();
            }
            if (!sleep(500)) break;
        }
        releaseWakeLock();
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MobileTrainerLink:usb");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ------------------------------------------------------------------ messages from the Pico

    private final BridgeSession.Listener listener = new BridgeSession.Listener() {
        @Override
        public void onLog(String line) {
            log(line);
        }

        @Override
        public void onPicoLine(String line) {
            log("Pico: " + line);
        }

        @Override
        public void onInfo(int kind, byte[] b) {
            switch (kind) {
                case GB.INFO_STATUS:
                    if (b.length >= 2) {
                        boolean active = (b[0] & 1) != 0;
                        status.adapter = (active ? "Running" : "Stopped") + " · "
                                + GB.deviceName(b[1]) + ((b[1] & GB.DEVICE_UNMETERED) != 0 ? " (unmetered)" : "");
                        log("Adapter: " + status.adapter);
                    }
                    break;
                case GB.INFO_IMPL:
                    if (b.length >= 8) {
                        String lm = ((b[0] & 0xFF) << 8 | (b[1] & 0xFF)) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF);
                        String fw = ((b[4] & 0xFF) << 8 | (b[5] & 0xFF)) + "." + (b[6] & 0xFF) + "." + (b[7] & 0xFF);
                        String name = cString(b, 8);
                        status.version = name + " · firmware " + fw + " · libmobile " + lm;
                        log("Pico: " + status.version);
                    }
                    break;
                case GB.INFO_NUMBER:
                    status.number = cString(b, 0);
                    log("Adapter number: " + status.number);
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onAck(int cmd) {
            log("Pico saved setting (command " + cmd + ")");
        }

        @Override
        public void onTraffic() {
            status.lastTraffic = SystemClock.elapsedRealtime();
        }
    };

    private static String cString(byte[] b, int from) {
        int end = from;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, from, end - from, StandardCharsets.US_ASCII);
    }
}
