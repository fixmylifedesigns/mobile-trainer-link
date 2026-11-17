package com.fixmylife.mobiletrainerlink;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.fixmylife.mobiletrainerlink.bridge.AddrCodec;
import com.fixmylife.mobiletrainerlink.bridge.DnsResponder;
import com.fixmylife.mobiletrainerlink.bridge.GB;

public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.fixmylife.mobiletrainerlink.USB_PERMISSION";
    private static final int BG = Color.parseColor("#14121C");
    private static final int CARD = Color.parseColor("#221E30");
    private static final int ACCENT = Color.parseColor("#FFD23F");
    private static final int TEXT = Color.parseColor("#ECE9F5");
    private static final int DIM = Color.parseColor("#A29DB5");

    private static final int[] DEVICE_VALUES = {GB.DEVICE_BLUE_PDC, GB.DEVICE_YELLOW_CDMA, GB.DEVICE_RED_DDI};
    private static final String[] DEVICE_LABELS = {"Blue — PDC (docomo etc.)", "Yellow — cdmaOne", "Red — DDI Pocket"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Updater updater;

    private TextView usbText, adapterText, versionText, trafficText, logView, activityDot;
    private EditText gameServerEdit, upstreamEdit, overridesEdit;
    private RadioGroup dnsGroup;
    private CheckBox systemDnsBox, keepAwakeBox;
    private Spinner deviceSpinner;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        loadSettings();
        updater = new Updater(this);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        startLink();
        handleIntent(getIntent());
        updater.check(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(ticker);
        updater.resumePending();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            LinkService.log("Pico plugged in");
            startLink();
        }
    }

    private void startLink() {
        try {
            startForegroundService(new Intent(this, LinkService.class));
        } catch (Exception e) {
            LinkService.log("Could not start the link service: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ actions

    private void askUsbPermission() {
        UsbManager um = getSystemService(UsbManager.class);
        UsbDevice d = LinkService.findPico(um);
        if (d == null) {
            toast("Plug the Pico into the phone first (USB-C OTG)");
            return;
        }
        if (um.hasPermission(d)) {
            toast("USB access already allowed");
            startLink();
            return;
        }
        Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        um.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
        startLink();
    }

    private void saveSettings() {
        String game = gameServerEdit.getText().toString().trim();
        String up = upstreamEdit.getText().toString().trim();
        if (!game.isEmpty() && AddrCodec.parseIpv4(game) == null) {
            toast("Game server must be an IPv4 address like 192.168.1.20");
            return;
        }
        if (!up.isEmpty() && AddrCodec.parseIpv4(up) == null) {
            toast("DNS server must be an IPv4 address");
            return;
        }
        DnsResponder.Mode mode = DnsResponder.Mode.LOCAL;
        int checked = dnsGroup.getCheckedRadioButtonId();
        if (checked == R_FORWARD) mode = DnsResponder.Mode.FORWARD;
        else if (checked == R_OFF) mode = DnsResponder.Mode.OFF;
        if (mode == DnsResponder.Mode.FORWARD && up.isEmpty()) {
            toast("Enter the DNS server to forward to");
            return;
        }
        Prefs.get(this).edit()
                .putString(Prefs.DNS_MODE, mode.name())
                .putString(Prefs.GAME_SERVER, game)
                .putString(Prefs.UPSTREAM_DNS, up)
                .putString(Prefs.OVERRIDES, overridesEdit.getText().toString())
                .putBoolean(Prefs.SYSTEM_DNS, systemDnsBox.isChecked())
                .putBoolean(Prefs.KEEP_AWAKE, keepAwakeBox.isChecked())
                .apply();
        LinkService s = LinkService.get();
        if (s != null) s.applySettings();
        toast("Saved");
    }

    private void sendDevice() {
        int device = DEVICE_VALUES[deviceSpinner.getSelectedItemPosition()];
        Prefs.get(this).edit().putInt(Prefs.DEVICE, device).apply();
        if (!runOnPico(s -> {
            s.setDevice(device, false);
            s.requestStatus();
        })) return;
        toast("Sent — turn the Game Boy off and on for it to take effect");
    }

    private boolean runOnPico(java.util.function.Consumer<com.fixmylife.mobiletrainerlink.bridge.BridgeSession> a) {
        LinkService s = LinkService.get();
        if (s == null || !s.withSession(a)) {
            toast("The Pico isn't connected yet");
            return false;
        }
        return true;
    }

    private void copyLog() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Mobile Trainer Link log", LinkService.logText(400)));
        toast("Log copied");
    }

    private void refresh() {
        LinkService.Status st = LinkService.status;
        usbText.setText(st.usb);
        usbText.setTextColor(st.connected ? Color.parseColor("#8BE28B") : TEXT);
        String adapter = "Adapter: " + st.adapter;
        if (!st.number.isEmpty()) adapter += "\nNumber: " + st.number;
        adapterText.setText(adapter);
        versionText.setText(st.version);
        versionText.setVisibility(st.version.isEmpty() ? View.GONE : View.VISIBLE);
        trafficText.setText("Sockets open: " + st.sockets + "   ↑ " + bytes(st.bytesUp) + "   ↓ " + bytes(st.bytesDown));
        boolean active = SystemClock.elapsedRealtime() - st.lastTraffic < 1500 && st.lastTraffic > 0;
        activityDot.setTextColor(active ? ACCENT : Color.parseColor("#4A4560"));
        logView.setText(LinkService.logText(80));
    }

    private static String bytes(long b) {
        if (b < 1024) return b + " B";
        return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ settings <-> views

    private void loadSettings() {
        gameServerEdit.setText(Prefs.gameServer(this));
        upstreamEdit.setText(Prefs.upstreamDns(this));
        overridesEdit.setText(Prefs.overrides(this));
        systemDnsBox.setChecked(Prefs.systemDns(this));
        keepAwakeBox.setChecked(Prefs.keepAwake(this));
        switch (Prefs.dnsMode(this)) {
            case FORWARD: dnsGroup.check(R_FORWARD); break;
            case OFF: dnsGroup.check(R_OFF); break;
            default: dnsGroup.check(R_LOCAL); break;
        }
        int device = Prefs.device(this);
        for (int i = 0; i < DEVICE_VALUES.length; i++) if (DEVICE_VALUES[i] == device) deviceSpinner.setSelection(i);
    }

    // ------------------------------------------------------------------ UI (built in code)

    private final int R_LOCAL = View.generateViewId();
    private final int R_FORWARD = View.generateViewId();
    private final int R_OFF = View.generateViewId();

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        scroll.addView(root);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Mobile Trainer Link", 24, TEXT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        activityDot = text("●", 22, Color.parseColor("#4A4560"), false);
        titleRow.addView(activityDot);
        root.addView(titleRow);
        root.addView(text("Game Boy ↔ Pico ↔ phone, as a Mobile Adapter GB", 13, DIM, false));

        // Status card
        LinearLayout status = card(root);
        usbText = text("", 16, TEXT, true);
        status.addView(usbText);
        adapterText = text("", 14, TEXT, false);
        adapterText.setPadding(0, dp(6), 0, 0);
        status.addView(adapterText);
        versionText = text("", 12, DIM, false);
        status.addView(versionText);
        trafficText = text("", 12, DIM, false);
        trafficText.setPadding(0, dp(6), 0, 0);
        status.addView(trafficText);
        LinearLayout statusButtons = row(status);
        statusButtons.addView(button("Allow USB", v -> askUsbPermission()), weight());
        statusButtons.addView(button("Refresh", v -> runOnPico(s -> {
            s.requestStatus();
            s.requestInfo();
            s.requestNumber();
        })), weight());

        // Server / DNS
        LinearLayout server = card(root);
        server.addView(header("Server"));
        server.addView(hint("Mobile Trainer looks up Nintendo's old servers (gameboy.datacenter.ne.jp, *.dion.ne.jp). Point them at a REON server you run."));
        dnsGroup = new RadioGroup(this);
        dnsGroup.addView(radio(R_LOCAL, "Answer DNS on the phone (recommended)"));
        dnsGroup.addView(radio(R_FORWARD, "Forward DNS to my own DNS server"));
        dnsGroup.addView(radio(R_OFF, "Off — send DNS where the cartridge asks"));
        server.addView(dnsGroup);
        server.addView(label("Game server IP (Nintendo hosts go here)"));
        gameServerEdit = edit("e.g. 192.168.1.20", false);
        server.addView(gameServerEdit);
        server.addView(label("DNS server IP for forwarding"));
        upstreamEdit = edit("e.g. 192.168.1.20", false);
        server.addView(upstreamEdit);
        systemDnsBox = check("Look up other sites (pokemon.co.jp etc.) with the phone's DNS");
        server.addView(systemDnsBox);
        server.addView(label("Host overrides, one per line: host ip  (start with . for subdomains)"));
        overridesEdit = edit("gameboy.datacenter.ne.jp 192.168.1.20", true);
        server.addView(overridesEdit);
        keepAwakeBox = check("Keep the phone's CPU awake while the Pico is connected");
        server.addView(keepAwakeBox);
        server.addView(button("Save", v -> saveSettings()));

        // Adapter model
        LinearLayout adapter = card(root);
        adapter.addView(header("Adapter"));
        adapter.addView(hint("Which Mobile Adapter GB the Pico pretends to be. Stored on the Pico."));
        deviceSpinner = new Spinner(this);
        ArrayAdapter<String> aa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, DEVICE_LABELS);
        deviceSpinner.setAdapter(aa);
        adapter.addView(deviceSpinner);
        LinearLayout adapterButtons = row(adapter);
        adapterButtons.addView(button("Send to Pico", v -> sendDevice()), weight());
        adapterButtons.addView(button("Save on Pico", v -> {
            if (runOnPico(s -> s.forceSave())) toast("Asked the Pico to save");
        }), weight());

        // Log
        LinearLayout log = card(root);
        LinearLayout logHead = new LinearLayout(this);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        logHead.addView(header("Log"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        logHead.addView(button("Copy", v -> copyLog()));
        log.addView(logHead);
        logView = text("", 11, DIM, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        log.addView(logView);

        root.addView(button("Check for updates", v -> updater.check(false)));
        return scroll;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(8), 0, 0);
        parent.addView(r);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginEnd(dp(6));
        return lp;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 17, ACCENT, true);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 12, DIM, false);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13, TEXT, false);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private EditText edit(String hintText, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        e.setHintTextColor(Color.parseColor("#6E6884"));
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setMinLines(2);
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setTypeface(Typeface.MONOSPACE);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setSingleLine(true);
        }
        return e;
    }

    private RadioButton radio(int id, String s) {
        RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(s);
        r.setTextColor(TEXT);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return r;
    }

    private CheckBox check(String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(TEXT);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return c;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }
}
