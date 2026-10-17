-- 013_llm_call_log_cached_tokens.sql — prompt 前缀缓存命中可观测（2026-10-17 成本治理）
-- 参考 DDL（运行时由 Exposed createMissingTablesAndColumns 自动补列）
-- null = 上游未上报 prompt_tokens_details.cached_tokens（不可观测）；0 = 上报但未命中
ALTER TABLE llm_call_log ADD COLUMN cached_tokens INTEGER;
