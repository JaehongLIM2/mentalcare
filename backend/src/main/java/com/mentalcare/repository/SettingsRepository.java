package com.mentalcare.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Key/value runtime settings the user can change from the Settings screen. */
@Repository
public class SettingsRepository {

    private final JdbcClient jdbc;

    public SettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.sql("SELECT value FROM app_settings WHERE key = :key").param("key", key)
                .query(String.class).optional();
    }

    public void set(String key, String value) {
        if (value == null) {
            jdbc.sql("DELETE FROM app_settings WHERE key = :key").param("key", key).update();
            return;
        }
        jdbc.sql("INSERT INTO app_settings (key, value) VALUES (:key, :value) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
                .param("key", key).param("value", value).update();
    }

    public Map<String, String> all() {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.sql("SELECT key, value FROM app_settings ORDER BY key")
                .query((rs, i) -> Map.entry(rs.getString("key"), rs.getString("value")))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }
}
