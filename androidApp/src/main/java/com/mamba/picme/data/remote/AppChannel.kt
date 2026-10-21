package com.mamba.picme.data.remote

import com.mamba.picme.BuildConfig

/**
 * 构建渠道头：debug 包发 "debug"、release 包发 "release"（BuildConfig.DEBUG 派生）。
 * 用途：服务端 DEBUG_QUOTA_BYPASS 开关配套——debug 包豁免 LLM/云端 Agent/浏览器额度扣减。
 * 契约对端 = server `auth/AppTokenAuth.kt` 的 APP_CHANNEL_HEADER；头名/取值改动须双向同步。
 * 头可被伪造，服务端 env 开关（默认关）是总闸；iOS 暂不上报。
 */
const val APP_CHANNEL_HEADER = "X-App-Channel"

val appChannelValue: String = if (BuildConfig.DEBUG) "debug" else "release"
