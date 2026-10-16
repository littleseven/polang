-- 012_agent_session.sql — 云端 Agent 会话域（ADR-018：事件溯源，事件流为唯一真相）
-- 运行时由 Exposed SchemaUtils.create 自动建表；此处供手动初始化/核对。
CREATE TABLE IF NOT EXISTS agent_session (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    id VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    channel_kind VARCHAR(16) NOT NULL,
    model VARCHAR(128) NOT NULL,
    byok_base_url VARCHAR(256) NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    finished_at BIGINT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS agent_session_id_unique ON agent_session(id);
CREATE INDEX IF NOT EXISTS idx_agent_session_token_hash ON agent_session(token_hash);

CREATE TABLE IF NOT EXISTS agent_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id VARCHAR(64) NOT NULL,
    type VARCHAR(24) NOT NULL,
    source VARCHAR(16) NOT NULL,
    role VARCHAR(16) NOT NULL,
    parent_id BIGINT NULL,
    payload_json TEXT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_event_session_id_id ON agent_event(session_id, id);
