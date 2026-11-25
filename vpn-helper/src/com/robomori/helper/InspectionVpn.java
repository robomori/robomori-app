package com.robomori.helper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Captures every app's traffic and hands each TCP connection to Robomori on the computer, through a SOCKS5 server
 * that {@code adb reverse} makes reachable at {@code 127.0.0.1:port} on the device.
 *
 * <p>Robomori holds a control connection to the VPN at that same port for as long as it inspects the device. When that
 * connection closes or goes quiet, because Robomori stopped, quit or crashed, or the device was unplugged, the VPN turns
 * itself off, so the device never loses its connection to the internet.
 */
public final class InspectionVpn extends VpnService {
    static final String STOP = "com.robomori.helper.STOP";
    private static final String CHANNEL = "inspection";
    /** The first bytes on the control connection, telling Robomori's server it isn't SOCKS5 or HTTP. */
    private static final byte[] HELLO = "\u0001robomori-vpn 1\n".getBytes(StandardCharsets.US_ASCII);
    /** Robomori pings every 2 seconds; this much silence means it is gone. */
    private static final int SILENCE_MILLIS = 8000;
    /** The VPN's own addresses; the DNS address is answered inside the tunnel, by hev-socks5-tunnel's mapped DNS. */
    private static final String ADDRESS = "10.215.173.1";
    private static final String DNS = "10.215.173.2";
    private static final String ADDRESS6 = "fd52:6f62:6f6d:6f72::1";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // First, as a service started in the foreground must be shown within seconds, even one asked to stop.
        showNotification();
        if (intent == null || STOP.equals(intent.getAction())) {
            end();
            return START_NOT_STICKY;
        }
        if (session != null) session.close();
        session = new Session(intent.getIntExtra("port", 0));
        session.start();
        return START_NOT_STICKY;
    }

    /** The VPN was turned off in Settings, or another VPN took over. */
    @Override
    public void onRevoke() { end(); }

    @Override
    public void onDestroy() {
        if (session != null) session.close();
        session = null;
    }

    private void end() {
        if (session != null) session.close();
        session = null;
        stopForeground(true);
        stopSelf();
    }

    /** Robomori's blue, as its buttons and selection use it. */
    private static final int ACCENT = 0xFF3574F0;

    private void showNotification() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel),
                NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL);
        } else builder = new Notification.Builder(this);
        // The icon is a white shape, as Android tints it; the color fills the circle it sits on, which would be white too.
        Notification notification = builder.setSmallIcon(R.drawable.icon).setColor(ACCENT)
            .setContentTitle(getString(R.string.inspecting)).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
    }

    /** One run of the VPN, for as long as Robomori's control connection lasts. */
    private final class Session extends Thread {
        private final int port;
        private final Socket control = new Socket();

        Session(int port) {
            super("robomori-vpn");
            this.port = port;
        }

        /** Ends the run and waits for the tunnel to stop, so the next one can start. */
        void close() {
            try { control.close(); } catch (IOException ignored) { }
            try { join(); } catch (InterruptedException ignored) { }
        }

        @Override
        public void run() {
            ParcelFileDescriptor tun = null;
            Thread tunnel = null;
            try {
                control.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000);
                control.setSoTimeout(SILENCE_MILLIS);
                control.setTcpNoDelay(true);
                OutputStream out = control.getOutputStream();
                out.write(HELLO);
                tun = establish();
                if (tun == null) {
                    send(out, "failed Android has not allowed the VPN");
                    return;
                }
                int fd = tun.getFd();
                tunnel = new Thread(() -> {
                    Tunnel.run(config(port), fd);
                    // A tunnel that stopped on its own would leave the device without a network, so the run ends.
                    try { control.close(); } catch (IOException ignored) { }
                }, "robomori-tunnel");
                tunnel.start();
                send(out, "ready");
                BufferedReader in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.US_ASCII));
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.startsWith("uid ")) send(out, answerUid(line));
                }
            } catch (IOException | RuntimeException ignored) {
                // Robomori is gone, or never answered: either way the device goes back to its own network.
            } finally {
                Tunnel.quit();
                if (tunnel != null) try { tunnel.join(); } catch (InterruptedException ignored) { }
                if (tun != null) try { tun.close(); } catch (IOException ignored) { }
                main.post(() -> { if (session == this) end(); });
            }
        }

        private ParcelFileDescriptor establish() {
            // With ACTIVATE_VPN allowed by adb, this makes the helper the device's VPN without asking the user.
            if (prepare(InspectionVpn.this) != null) return null;
            Builder builder = new Builder().setSession("Robomori").setMtu(8500)
                .addAddress(ADDRESS, 30).addAddress(ADDRESS6, 126)
                .addRoute("0.0.0.0", 0).addRoute("::", 0)
                .addDnsServer(DNS);
            try {
                // The tunnel's own connections to Robomori must not loop back into the VPN.
                builder.addDisallowedApplication(getPackageName());
            } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
                throw new IllegalStateException(impossible);
            }
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            return builder.establish();
        }

        /** Answers {@code uid <id> <app address> <app port> <dialed address> <dialed port>} with the app's user ID. */
        private String answerUid(String line) {
            String[] parts = line.split(" ");
            int uid = -1;
            if (parts.length == 6 && Build.VERSION.SDK_INT >= 29) {
                try {
                    InetSocketAddress app = new InetSocketAddress(InetAddress.getByName(parts[2]), Integer.parseInt(parts[3]));
                    InetSocketAddress dialed = new InetSocketAddress(InetAddress.getByName(parts[4]), Integer.parseInt(parts[5]));
                    uid = getSystemService(ConnectivityManager.class).getConnectionOwnerUid(OsConstants.IPPROTO_TCP, app, dialed);
                } catch (IOException | RuntimeException ignored) { }
            }
            return "uid " + (parts.length > 1 ? parts[1] : "0") + " " + uid;
        }
    }

    private static synchronized void send(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** hev-socks5-tunnel's settings: every TCP connection to Robomori's SOCKS5 server, and names looked up inside. */
    private static String config(int port) {
        return "tunnel:\n  mtu: 8500\n" +
            "socks5:\n  port: " + port + "\n  address: 127.0.0.1\n  udp: 'tcp'\n" +
            // Answers each name with an address of its own, which the SOCKS5 server then gets as the name, so Robomori
            // knows the site of every connection and looks it up on the computer.
            "mapdns:\n  address: " + DNS + "\n  port: 53\n  network: 100.64.0.0\n  netmask: 255.192.0.0\n  cache-size: 10000\n" +
            "misc:\n  log-level: warn\n  connect-timeout: 5000\n";
    }
}
