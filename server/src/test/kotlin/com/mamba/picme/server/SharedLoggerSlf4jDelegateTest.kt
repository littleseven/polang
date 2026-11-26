package com.mamba.picme.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mamba.picme.agent.core.platform.logging.Logger as AgentCoreLogger
import com.mamba.picme.domain.chat.openui.OpenUiTurnNativeContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory

/**
 * U1 评审 follow-up（Task 12 任务 A）：server 组合根注入 shared AgentCoreLogger 的
 * slf4j delegate 后，shared openui 校验栈日志（Normalizer w / TurnNativeContract w+i）
 * 必须经 logback 路由（时间戳/级别），不再走 companion println 兜底。
 * logger 名 = tag（"picme-agent"，与上提前 server slf4j 形态对齐）。
 */
class SharedLoggerSlf4jDelegateTest {

    private val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as LogbackLogger
    private val appender = ListAppender<ILoggingEvent>()
    private var originalRootLevel: Level? = null

    private val noopLogger = object : AgentCoreLogger {
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) = Unit
        override fun isLogEnabled(tag: String): Boolean = false
    }

    @Before
    fun setUp() {
        root.addAppender(appender)
        appender.start()
    }

    @After
    fun tearDown() {
        root.detachAppender(appender)
        originalRootLevel?.let { root.level = it }
        originalRootLevel = null
        // 恢复全局 delegate，避免污染同 JVM 内其他测试的日志断言
        AgentCoreLogger.setDelegate(noopLogger)
    }

    @Test
    fun `delegate maps levels to slf4j with tag as logger name`() {
        // 生产 logback.xml root=INFO，临时降到 DEBUG 才能钉住 d→debug 映射
        originalRootLevel = root.level
        root.level = Level.DEBUG
        installSharedLoggerDelegate()
        val delegate = Slf4jAgentCoreLogger()

        delegate.d("picme-agent", "debug msg")
        delegate.i("picme-agent", "info msg")
        delegate.w("picme-agent", "warn msg")
        delegate.e("picme-agent", "error msg")

        val events = appender.list.filter { it.loggerName == "picme-agent" }
        assertEquals(
            listOf(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR),
            events.map { it.level },
        )
    }

    @Test
    fun `shared ensureValidProgram logs route through logback`() {
        installSharedLoggerDelegate()

        val wrapped = OpenUiTurnNativeContract.ensureValidProgram("这不是一颗程序")

        assertTrue(wrapped.startsWith("// summary:"))
        val event = appender.list.first { it.loggerName == "picme-agent" && it.level == Level.INFO }
        assertEquals("picme-agent", event.loggerName)
        assertTrue(event.formattedMessage.contains("终结兜底"))
    }
}
