package com.mentalcare.repository;

import com.mentalcare.domain.PromptVersion;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.mentalcare.repository.JdbcSupport.*;

@Repository
public class PromptVersionRepository {

    private static final RowMapper<PromptVersion> MAPPER = (rs, i) -> new PromptVersion(
            rs.getLong("id"), rs.getString("name"), rs.getString("version"),
            rs.getString("content_hash"), instant(rs, "loaded_at"));

    private final JdbcClient jdbc;

    public PromptVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PromptVersion findOrCreate(String name, String version, String contentHash) {
        Optional<PromptVersion> existing = jdbc.sql("""
                SELECT * FROM prompt_versions WHERE name = :name AND version = :version AND content_hash = :hash
                """)
                .param("name", name).param("version", version).param("hash", contentHash)
                .query(MAPPER).optional();
        if (existing.isPresent()) return existing.get();
        jdbc.sql("INSERT INTO prompt_versions (name, version, content_hash, loaded_at) VALUES (:name, :version, :hash, :now)")
                .param("name", name).param("version", version).param("hash", contentHash).param("now", now())
                .update();
        return jdbc.sql("SELECT * FROM prompt_versions WHERE name = :name AND version = :version AND content_hash = :hash")
                .param("name", name).param("version", version).param("hash", contentHash)
                .query(MAPPER).single();
    }

    public Optional<PromptVersion> findById(long id) {
        return jdbc.sql("SELECT * FROM prompt_versions WHERE id = :id").param("id", id).query(MAPPER).optional();
    }
}
