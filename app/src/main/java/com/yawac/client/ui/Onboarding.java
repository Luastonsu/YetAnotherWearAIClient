package com.yawac.client.ui;

import android.content.Context;
import android.content.SharedPreferences;

/** Consent and hands-on progress are independent of the retired feature introduction. */
final class Onboarding {
    static final String AGREEMENT = "agreement_version", STEP = "onboarding_step", DEMO = "onboarding_chat";
    static final int VERSION = 1, SETTINGS = 0, PROVIDER = 1, PROVIDER_BACK = 2,
            HOME = 3, NEW_CHAT = 4, OPTIONS = 5, CLOSE_OPTIONS = 6, CHAT_BACK = 7, DONE = 8;
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("wearai_prefs", Context.MODE_PRIVATE); }
    static boolean accepted(Context c) { return prefs(c).getInt(AGREEMENT, 0) == VERSION; }
    static int step(Context c) { return prefs(c).getInt(STEP, SETTINGS); }
    static void step(Context c, int value) { prefs(c).edit().putInt(STEP, value).apply(); }
    static boolean active(Context c) { return accepted(c) && step(c) < DONE; }
    static void accept(Context c) { prefs(c).edit().putInt(AGREEMENT, VERSION).putInt(STEP, SETTINGS).apply(); }
    static boolean isDemo(Context c, String id) { return id != null && id.equals(prefs(c).getString(DEMO, "")); }
    static void complete(Context c) { prefs(c).edit().putInt(STEP, DONE).remove(DEMO).apply(); }
    private Onboarding() { }
}
