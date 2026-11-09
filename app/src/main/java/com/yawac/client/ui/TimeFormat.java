package com.yawac.client.ui;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class TimeFormat {

    private TimeFormat() {
    }

    public static String formatShort(long timestamp) {
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(timestamp);
        Calendar now = Calendar.getInstance();
        String pattern = date.get(Calendar.YEAR) != now.get(Calendar.YEAR) ? "yyyy/M/d"
                : date.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) ? "HH:mm" : "M/d";
        return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(timestamp));
    }

    public static String format(long timestamp) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timestamp);
        Calendar now = Calendar.getInstance();

        String pattern;
        if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && c.get(Calendar.MONTH) == now.get(Calendar.MONTH)
                && c.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)) {
            pattern = "HH:mm";
        } else if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            pattern = "M月d日 HH:mm";
        } else {
            pattern = "yyyy年M月d日 HH:mm";
        }
        return new SimpleDateFormat(pattern, Locale.CHINA).format(new Date(timestamp));
    }
}
