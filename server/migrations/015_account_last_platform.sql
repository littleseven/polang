-- 参考 DDL（运行时由 Exposed createMissingTablesAndColumns 自动补列）
-- 账号维度「最后所见平台」：云端 Agent 路径不写 llm_call_log，
-- 后台用户列表平台列无法从调用流水推导，改由认证拦截器逐请求自愈更新本列。
ALTER TABLE account ADD COLUMN last_platform VARCHAR(16);
