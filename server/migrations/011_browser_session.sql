-- 011_browser_session.sql — browser 会话统计（spec: browser-vnc 直播卡 §7）
-- 运行时由 Exposed SchemaUtils.create 自动建表；此处供手动初始化/核对。
CREATE TABLE IF NOT EXISTS browser_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token_hash VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    started_at BIGINT NOT NULL,
    ended_at BIGINT NULL,
    outcome VARCHAR(16) NULL
);
