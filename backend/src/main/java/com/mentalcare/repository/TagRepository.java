package com.mentalcare.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TagRepository {

    private final JdbcClient jdbc;

    public TagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void replaceSessionTags(long sessionId, Collection<String> names) {
        jdbc.sql("DELETE FROM session_tags WHERE session_id = :sid").param("sid", sessionId).update();
        for (String name : names) {
            jdbc.sql("INSERT OR IGNORE INTO tags (name) VALUES (:name)").param("name", name).update();
            long tagId = jdbc.sql("SELECT id FROM tags WHERE name = :name").param("name", name).query(Long.class).single();
            jdbc.sql("INSERT OR IGNORE INTO session_tags (session_id, tag_id) VALUES (:sid, :tid)")
                    .param("sid", sessionId).param("tid", tagId).update();
        }
    }

    public record TagCount(String name, long count) {}

    public List<TagCount> allWithCounts() {
        return jdbc.sql("""
                SELECT t.name, COUNT(st.session_id) AS n FROM tags t
                LEFT JOIN session_tags st ON st.tag_id = t.id
                GROUP BY t.id ORDER BY n DESC, t.name
                """)
                .query((rs, i) -> new TagCount(rs.getString("name"), rs.getLong("n"))).list();
    }

    public List<String> findBySession(long sessionId) {
        return jdbc.sql("""
                SELECT t.name FROM tags t JOIN session_tags st ON st.tag_id = t.id
                WHERE st.session_id = :sid ORDER BY t.name
                """)
                .param("sid", sessionId).query(String.class).list();
    }

    public Map<Long, List<String>> findBySessions(List<Long> sessionIds) {
        Map<Long, List<String>> result = new HashMap<>();
        if (sessionIds.isEmpty()) return result;
        jdbc.sql("""
                SELECT st.session_id, t.name FROM tags t JOIN session_tags st ON st.tag_id = t.id
                WHERE st.session_id IN (:ids) ORDER BY t.name
                """)
                .param("ids", sessionIds)
                .query((rs, i) -> Map.entry(rs.getLong("session_id"), rs.getString("name")))
                .list()
                .forEach(e -> result.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>()).add(e.getValue()));
        return result;
    }
}
