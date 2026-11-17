import com.fixmylife.mobiletrainerlink.bridge.*;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** Drives the real firmware gbridge code (fake_pico) with the phone-side BridgeSession. */
public class TestHarness {
    public static void main(String[] args) throws Exception {
        ServerSocket echo = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread et = new Thread(() -> {
            try (Socket s = echo.accept()) {
                InputStream in = s.getInputStream();
                byte[] b = new byte[200];
                int got = 0;
                while (got < 200) {
                    int n = in.read(b, got, 200 - got);
                    if (n < 0) break;
                    got += n;
                }
                Thread.sleep(300);
                s.getOutputStream().write(b, 0, got); // echo back slowly, in one go
                s.getOutputStream().flush();
                Thread.sleep(300);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        et.start();

        Process p = new ProcessBuilder(args[0], String.valueOf(echo.getLocalPort()))
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        DataOutputStream toPico = new DataOutputStream(new BufferedOutputStream(p.getOutputStream()));
        DataInputStream fromPico = new DataInputStream(new BufferedInputStream(p.getInputStream()));
        BlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try {
                while (true) {
                    int n = fromPico.readUnsignedByte();
                    byte[] b = new byte[n];
                    fromPico.readFully(b);
                    inbox.put(b);
                }
            } catch (Exception ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();

        final int[] seen = {0};
        BridgeSession.Transport t = new BridgeSession.Transport() {
            public void write(byte[] d, int len) throws IOException {
                toPico.writeByte(len);
                toPico.write(d, 0, len);
                toPico.flush();
            }

            public int read(byte[] buf, int timeoutMs) {
                try {
                    byte[] b = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
                    if (b == null) return 0;
                    if (System.getenv("CORRUPT") != null && b.length > 8 && (b[0] & 0xC0) == 0 && ++seen[0] % 7 == 3) {
                        b[b.length - 1] ^= 0x55; System.out.println("[harness] corrupted a packet");
                    }
                    System.arraycopy(b, 0, buf, 0, b.length);
                    return b.length;
                } catch (InterruptedException e) {
                    return 0;
                }
            }
        };
        DnsResponder dns = new DnsResponder();
        dns.setGameServer(new byte[]{10, 0, 0, 7});
        dns.setUseSystemDns(false);
        dns.setLog(s -> System.out.println("[phone] " + s));
        SocketBridge sockets = new SocketBridge(dns, s -> System.out.println("[phone] " + s));
        BridgeSession session = new BridgeSession(t, sockets, new BridgeSession.Listener() {
            public void onLog(String l) { System.out.println("[phone] " + l); }
            public void onPicoLine(String l) { System.out.println("[pico-line] " + l); }
            public void onInfo(int k, byte[] b) { System.out.println("[info] " + k); }
            public void onAck(int c) { System.out.println("[ack] " + c); }
            public void onTraffic() {}
        });
        session.queueDebug(GB.DBG_STATUS, null); // exercise the debug path too
        long deadline = System.currentTimeMillis() + 60_000;
        while (p.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                if (!session.step()) Thread.sleep(1);
            } catch (IOException e) {
                break;
            }
        }
        p.waitFor(5, TimeUnit.SECONDS);
        System.out.println("fake_pico exit code: " + (p.isAlive() ? "still running" : p.exitValue())
                + ", USB packets: " + session.packets);
        if (p.isAlive()) p.destroyForcibly();
        System.exit(p.isAlive() ? 2 : p.exitValue());
    }
}
