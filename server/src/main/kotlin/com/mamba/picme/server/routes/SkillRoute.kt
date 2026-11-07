package com.mamba.picme.server.routes

import com.mamba.picme.server.agent.SkillCenter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Skill 中心下载端点（spike：2026-11-07）——端侧 Agent 的 skill 资产来源。
 *
 * - `GET /v1/skills`：技能清单（name + description，渐进披露的「披露层」）；
 * - `GET /v1/skills/{name}`：技能正文（frontmatter 之后的指令部分，≤32K 字符）。
 *
 * 鉴权走全局 app-token 拦截器（本路由不做 per-user 判定——skill 是全局公共资产，
 * 无用户维度数据）；云端 Agent 不走本端点（server 直读 SkillCenter 磁盘，零网络跳数）。
 */
fun Route.skillRoute(center: SkillCenter) {
    get("/v1/skills") {
        call.respond(
            buildJsonObject {
                putJsonArray("skills") {
                    center.manifest().forEach { meta ->
                        add(
                            buildJsonObject {
                                put("name", meta.name)
                                put("description", meta.description)
                            },
                        )
                    }
                }
            },
        )
    }

    get("/v1/skills/{name}") {
        val name = call.parameters["name"].orEmpty()
        val content = center.content(name)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "skill_not_found"))
        call.respond(
            buildJsonObject {
                put("name", name)
                put("content", content)
            },
        )
    }
}
