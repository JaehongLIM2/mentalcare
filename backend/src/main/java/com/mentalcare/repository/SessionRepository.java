package com.mentalcare.repository;

import com.mentalcare.domain.Enums.SessionKind;
import com.mentalcare.domain.Enums.SessionStatus;
import com.mentalcare.domain.Session;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mentalcare.repository.JdbcSupport.*;

@Repository
public class SessionRepository {

    private static final RowMapper<Session> MAPPER = (rs, i) -> new Session(
            rs.getLong("id"),
            SessionStatus.valueOf(rs.getString("status")),
            rs.getString("kind") == null ? SessionKind.COUNSELING : SessionKind.valueOf(rs.getString("kind")),
            rs.getString("title"),
            instant(rs, "started_at"),
            instant(rs, "ended_at"),
            longOrNull(rs, "prompt_version_id"),
            rs.getInt("crisis_flag") != 0,
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    public SessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Session create(Long promptVersionId) {
        String now = now();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO sessions (status, kind, started_at, prompt_version_id, crisis_flag, created_at, updated_at)
                VALUES ('ACTIVE', 'COUNSELING', :now, :pv, 0, :now, :now)
                """)
                .param("now", now)
                .param("pv", promptVersionId)
                .update(keys);
        return findById(key(keys)).orElseThrow();
    }

    /** A COMPLETED, message-less session representing an archive imported from another assistant. */
    public Session createImported(String title, Instant counseledAt) {
        String now = now();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO sessions (status, kind, title, started_at, ended_at, prompt_version_id, crisis_flag, created_at, updated_at)
                VALUES ('COMPLETED', 'IMPORTED', :title, :started, :started, NULL, 0, :now, :now)
                """)
                .param("title", title).param("started", text(counseledAt)).param("now", now)
                .update(keys);
        return findById(key(keys)).orElseThrow();
    }

    public Optional<Session> findById(long id) {
        return jdbc.sql("SELECT * FROM sessions WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<Session> findActive() {
        return jdbc.sql("SELECT * FROM sessions WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
                .query(MAPPER).optional();
    }

    public List<Session> findAll(int limit, int offset) {
        return jdbc.sql("SELECT * FROM sessions ORDER BY started_at DESC LIMIT :limit OFFSET :offset")
                .param("limit", limit).param("offset", offset).query(MAPPER).list();
    }

    /**
     * History filter. {@code query} matches the title, the conversation, the summary, the check-in note and
     * imported Markdown (substring, case-insensitive for ASCII); dates are the user's local calendar days.
     */
    public record Filter(String query, LocalDate from, LocalDate to, String tag) {
        public boolean isEmpty() {
            return (query == null || query.isBlank()) && from == null && to == null && (tag == null || tag.isBlank());
        }
    }

    public List<Session> search(Filter filter, int limit, int offset) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);
        var spec = jdbc.sql("SELECT * FROM sessions s" + where + " ORDER BY started_at DESC LIMIT :limit OFFSET :offset")
                .param("limit", limit).param("offset", offset);
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec.query(MAPPER).list();
    }

    public long countMatching(Filter filter) {
        Map<String, Object> params = new LinkedHashMap<>();
        String where = where(filter, params);
        var spec = jdbc.sql("SELECT COUNT(*) FROM sessions s" + where);
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec.query(Long.class).single();
    }

    private static String where(Filter f, Map<String, Object> params) {
        if (f == null || f.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        if (f.query() != null && !f.query().isBlank()) {
            params.put("q", "%" + escapeLike(f.query().trim()) + "%");
            params.put("qJson", "%" + escapeLike(jsonEscaped(f.query().trim())) + "%");
            sb.append("""
                     AND (s.title LIKE :q ESCAPE '!'
                       OR EXISTS (SELECT 1 FROM messages m WHERE m.session_id = s.id AND m.status <> 'FAILED' AND m.content LIKE :q ESCAPE '!')
                       OR EXISTS (SELECT 1 FROM session_summaries ss WHERE ss.session_id = s.id AND (ss.summary_json LIKE :q ESCAPE '!' OR ss.summary_json LIKE :qJson ESCAPE '!'))
                       OR EXISTS (SELECT 1 FROM weekly_checkins c WHERE c.session_id = s.id AND c.note LIKE :q ESCAPE '!')
                       OR EXISTS (SELECT 1 FROM imported_archives a WHERE a.session_id = s.id AND a.raw_markdown LIKE :q ESCAPE '!'))
                    """);
        }
        if (f.from() != null) {
            params.put("from", dayStart(f.from()));
            sb.append(" AND substr(s.started_at, 1, 19) >= :from");
        }
        if (f.to() != null) {
            params.put("to", dayStart(f.to().plusDays(1)));
            sb.append(" AND substr(s.started_at, 1, 19) < :to");
        }
        if (f.tag() != null && !f.tag().isBlank()) {
            params.put("tag", f.tag().trim());
            sb.append(" AND EXISTS (SELECT 1 FROM session_tags st JOIN tags t ON t.id = st.tag_id WHERE st.session_id = s.id AND t.name = :tag)");
        }
        return sb.toString();
    }

    /** First 19 chars of the UTC instant at local midnight (yyyy-MM-ddTHH:mm:ss), comparable with stored text. */
    private static String dayStart(LocalDate day) {
        return day.atStartOfDay(com.mentalcare.common.Dates.zone()).toInstant().toString().substring(0, 19);
    }

    /** The query as it appears inside JSON written with every non-ASCII character escaped (older summaries). */
    static String jsonEscaped(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127 || c == '<' || c == '>' || c == '&' || c == '\'') sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** LIKE wildcards in the user's query are literal; '!' is the escape character. */
    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** Recent completed counseling sessions (imported archives are provided to the context separately). */
    public List<Session> findRecentCompleted(int limit) {
        return jdbc.sql("SELECT * FROM sessions WHERE status = 'COMPLETED' AND kind = 'COUNSELING' ORDER BY ended_at DESC LIMIT :limit")
                .param("limit", limit).query(MAPPER).list();
    }

    public List<Session> findImported() {
        return jdbc.sql("SELECT * FROM sessions WHERE kind = 'IMPORTED' ORDER BY started_at ASC").query(MAPPER).list();
    }

    public List<Session> findByIds(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM sessions WHERE id IN (:ids)").param("ids", ids).query(MAPPER).list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM sessions").query(Long.class).single();
    }

    public long countCompleted() {
        return jdbc.sql("SELECT COUNT(*) FROM sessions WHERE status = 'COMPLETED' AND kind = 'COUNSELING'").query(Long.class).single();
    }

    public void markCompleted(long id, String title, Instant endedAt) {
        jdbc.sql("""
                UPDATE sessions SET status = 'COMPLETED', title = :title, ended_at = :ended, updated_at = :now
                WHERE id = :id
                """)
                .param("title", title).param("ended", text(endedAt)).param("now", now()).param("id", id)
                .update();
    }

    public void setCrisisFlag(long id, boolean flag) {
        jdbc.sql("UPDATE sessions SET crisis_flag = :flag, updated_at = :now WHERE id = :id")
                .param("flag", flag ? 1 : 0).param("now", now()).param("id", id).update();
    }

    public void touch(long id) {
        jdbc.sql("UPDATE sessions SET updated_at = :now WHERE id = :id").param("now", now()).param("id", id).update();
    }
}
