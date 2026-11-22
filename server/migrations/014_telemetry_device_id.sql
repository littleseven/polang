-- 014_telemetry_device_id.sql — 遥测事件设备维度（2026-11-22 转化漏斗：下载→就绪→扫描）
-- 参考 DDL（运行时由 Exposed createMissingTablesAndColumns 自动补列）
-- device_id 来自 X-Device-Id 头，只落独立列不进 payload（payload 契约保持无标识）
ALTER TABLE telemetry_event ADD COLUMN device_id VARCHAR(128);
