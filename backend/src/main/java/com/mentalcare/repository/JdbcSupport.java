package com.mentalcare.repository;

import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** Small helpers shared by the JdbcClient repositories. Timestamps are stored as ISO-8601 UTC text. */
final class JdbcSupport {
    private JdbcSupport() {}

    static String now() {
        return Instant.now().toString();
    }

    static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Instant.parse(value);
    }

    static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static long key(GeneratedKeyHolder holder) {
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("No generated key returned");
        }
        return key.longValue();
    }
}
