package com.mentalcare.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Local-date helpers. All timestamps are stored in UTC; the user sees them in the PC's time zone. */
public final class Dates {
    private Dates() {}

    public static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    public static LocalDate localDate(Instant instant) {
        return instant == null ? null : instant.atZone(zone()).toLocalDate();
    }

    public static String isoDate(Instant instant) {
        LocalDate d = localDate(instant);
        return d == null ? "" : d.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
