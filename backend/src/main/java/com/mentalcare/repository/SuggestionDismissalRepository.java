package com.mentalcare.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.mentalcare.repository.JdbcSupport.now;

/** Memory suggestions the user chose not to see again. Only the key (a hash of the sentence) is stored. */
@Repository
public class SuggestionDismissalRepository {

    private final JdbcClient jdbc;

    public SuggestionDismissalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> keys() {
        return jdbc.sql("SELECT key FROM memory_suggestion_dismissals").query(String.class).list();
    }

    /** Dismissing the same suggestion twice is not an error. */
    public void add(String key) {
        jdbc.sql("INSERT OR IGNORE INTO memory_suggestion_dismissals (key, created_at) VALUES (:k, :now)")
                .param("k", key).param("now", now()).update();
    }
}
