package com.mamba.picme.agent.core.skill

/**
 * skill 资产传输层（commonMain 无 HTTP 手段，组合根注入平台实现；测试注入 fake）。
 * 实现：androidApp `SkillClient`（OkHttp → picme-server `/v1/skills`）。
 *
 * 失败语义一律返回 null（不抛异常）——[SkillRepository] 据此回落本地缓存
 * （stale-while-revalidate），网络故障不穿透 ReAct 链。
 */
interface SkillTransport {

    /**
     * 拉取技能清单响应原文（`{"skills":[{name,description,version}]}`）。
     * @return 响应 body；网络失败/非 2xx/空 body 返回 null
     */
    suspend fun fetchManifestJson(): String?

    /**
     * 拉取单技能正文响应原文（`{"name","content","version"}`）。
     * @return 响应 body；404/网络失败返回 null
     */
    suspend fun fetchSkillJson(name: String): String?
}

/**
 * skill 本地缓存存储（组合根注入平台实现；Android = filesDir JSON 文件）。
 * 存储层刻意保持「 dumb pipe」：序列化/反序列化收口在 [SkillCodec]，平台只管读写字符串。
 */
interface SkillCacheStore {

    /** 读取缓存快照 JSON（[SkillCodec.encodeSnapshot] 产物）；无缓存/读取失败返回 null。 */
    suspend fun read(): String?

    /** 覆盖写入缓存快照 JSON；失败静默（缓存丢失不致命，下次同步重建）。 */
    suspend fun write(snapshotJson: String)
}
