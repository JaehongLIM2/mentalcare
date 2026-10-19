-- mentalcare SQLite schema. Idempotent: safe to run on every startup.

CREATE TABLE IF NOT EXISTS prompt_versions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    version       TEXT NOT NULL,
    content_hash  TEXT NOT NULL,
    loaded_at     TEXT NOT NULL,
    UNIQUE (name, version, content_hash)
);

CREATE TABLE IF NOT EXISTS sessions (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    status             TEXT NOT NULL,            -- ACTIVE | COMPLETED
    title              TEXT,
    started_at         TEXT NOT NULL,
    ended_at           TEXT,
    prompt_version_id  INTEGER REFERENCES prompt_versions(id),
    crisis_flag        INTEGER NOT NULL DEFAULT 0,
    created_at         TEXT NOT NULL,
    updated_at         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status);

CREATE TABLE IF NOT EXISTS messages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  INTEGER NOT NULL REFERENCES sessions(id),
    role        TEXT NOT NULL,                   -- USER | ASSISTANT
    content     TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'COMPLETE',-- COMPLETE | INTERRUPTED | FAILED
    risk_level  TEXT,                            -- NONE | LOW | ELEVATED | IMMINENT (user messages only)
    created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, id);

CREATE TABLE IF NOT EXISTS weekly_checkins (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  INTEGER NOT NULL UNIQUE REFERENCES sessions(id),
    mood        INTEGER,
    stress      INTEGER,
    anxiety     INTEGER,
    energy      INTEGER,
    sleep       INTEGER,
    note        TEXT,
    created_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS session_summaries (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id         INTEGER NOT NULL UNIQUE REFERENCES sessions(id),
    summary_json       TEXT NOT NULL,
    model              TEXT NOT NULL,
    prompt_version_id  INTEGER REFERENCES prompt_versions(id),
    markdown_path      TEXT,
    created_at         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS memories (
    id                         INTEGER PRIMARY KEY AUTOINCREMENT,
    content                    TEXT NOT NULL,
    kind                       TEXT NOT NULL,    -- USER_STATED | AI_OBSERVED
    category                   TEXT NOT NULL,
    status                     TEXT NOT NULL,    -- CANDIDATE | ACTIVE | ARCHIVED
    first_observed_session_id  INTEGER REFERENCES sessions(id),
    last_observed_session_id   INTEGER REFERENCES sessions(id),
    observation_count          INTEGER NOT NULL DEFAULT 1,
    source                     TEXT NOT NULL DEFAULT 'SUMMARY', -- SUMMARY | MANUAL (written by the user)
    created_at                 TEXT NOT NULL,
    updated_at                 TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memories_status ON memories(status);

CREATE TABLE IF NOT EXISTS memory_observations (
    memory_id   INTEGER NOT NULL REFERENCES memories(id),
    session_id  INTEGER NOT NULL REFERENCES sessions(id),
    note        TEXT,
    created_at  TEXT NOT NULL,
    PRIMARY KEY (memory_id, session_id)
);

CREATE TABLE IF NOT EXISTS tags (
    id    INTEGER PRIMARY KEY AUTOINCREMENT,
    name  TEXT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS session_tags (
    session_id  INTEGER NOT NULL REFERENCES sessions(id),
    tag_id      INTEGER NOT NULL REFERENCES tags(id),
    PRIMARY KEY (session_id, tag_id)
);

CREATE TABLE IF NOT EXISTS safety_events (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id              INTEGER NOT NULL REFERENCES sessions(id),
    message_id              INTEGER REFERENCES messages(id),
    moderation_flagged      INTEGER NOT NULL DEFAULT 0,
    moderation_scores_json  TEXT,
    keyword_signal          INTEGER NOT NULL DEFAULT 0,
    risk_level              TEXT NOT NULL,
    assessment_reason       TEXT,
    created_at              TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS api_usage (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    request_type   TEXT NOT NULL,
    model          TEXT NOT NULL,
    input_tokens   INTEGER NOT NULL DEFAULT 0,
    output_tokens  INTEGER NOT NULL DEFAULT 0,
    session_id     INTEGER,
    created_at     TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS app_settings (
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL
);

-- Archives imported from another assistant (e.g. the ChatGPT export template). One row per imported session.
CREATE TABLE IF NOT EXISTS imported_archives (
    session_id    INTEGER PRIMARY KEY REFERENCES sessions(id),
    source_name   TEXT NOT NULL,
    raw_markdown  TEXT NOT NULL,
    content_hash  TEXT,
    imported_at   TEXT NOT NULL
);

-- Unsent composer text of an active session, so a refresh or closed tab does not lose it.
CREATE TABLE IF NOT EXISTS drafts (
    session_id  INTEGER PRIMARY KEY REFERENCES sessions(id),
    content     TEXT NOT NULL,                    -- '' = tombstone (sent; older autosaves are ignored)
    rev         INTEGER NOT NULL DEFAULT 0,       -- client revision; only newer writes replace the row
    updated_at  TEXT NOT NULL
);

-- Memory suggestions the user chose not to see again (hash of the normalised sentence only, no text).
CREATE TABLE IF NOT EXISTS memory_suggestion_dismissals (
    key         TEXT PRIMARY KEY,
    created_at  TEXT NOT NULL
);
