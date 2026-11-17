package com.fixmylife.mobiletrainerlink;

import android.content.Context;
import android.content.SharedPreferences;

import com.fixmylife.mobiletrainerlink.bridge.DnsResponder;

/** Saved settings. */
final class Prefs {
    private Prefs() {}

    static final String DNS_MODE = "dns_mode";
    static final String GAME_SERVER = "game_server";
    static final String UPSTREAM_DNS = "upstream_dns";
    static final String OVERRIDES = "overrides";
    static final String SYSTEM_DNS = "system_dns";
    static final String DEVICE = "device";
    static final String KEEP_AWAKE = "keep_awake";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static DnsResponder.Mode dnsMode(Context c) {
        try {
            return DnsResponder.Mode.valueOf(get(c).getString(DNS_MODE, DnsResponder.Mode.LOCAL.name()));
        } catch (IllegalArgumentException e) {
            return DnsResponder.Mode.LOCAL;
        }
    }

    static String gameServer(Context c) { return get(c).getString(GAME_SERVER, ""); }
    static String upstreamDns(Context c) { return get(c).getString(UPSTREAM_DNS, ""); }
    static String overrides(Context c) { return get(c).getString(OVERRIDES, ""); }
    static boolean systemDns(Context c) { return get(c).getBoolean(SYSTEM_DNS, true); }
    static boolean keepAwake(Context c) { return get(c).getBoolean(KEEP_AWAKE, true); }
    static int device(Context c) { return get(c).getInt(DEVICE, 8); }
}
