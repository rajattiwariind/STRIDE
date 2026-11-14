package com.stride.app.utils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DateUtils {
    private DateUtils() {}
    public static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
    public static String formatDate(long millis) {
        return new SimpleDateFormat("EEE, dd MMM • h:mm a", Locale.getDefault()).format(new Date(millis));
    }
}
