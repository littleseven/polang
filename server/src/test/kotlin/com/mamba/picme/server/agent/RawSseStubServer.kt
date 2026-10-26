package com.mamba.picme.server.agent

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 裸 socket 假 LLM 服务器（openui P2 流式预览测试用）。MockEngine 不支持 SSECapability
 * （流式请求客户端侧即抛，不消耗响应队列）、CIO embeddedServer 与 Ktor SSE 客户端组合
 * 在测试环境读不到响应体——故用手写 HTTP/1.1 响应（Content-Length 精确 +
 * Connection: close）排掉框架变量，确定性演练 SSE 流式链路。
 *
 * [handler] 收（请求体, 第几次调用），回（响应体, Content-Type）。
 */
class RawSseStubServer(
    private val handler: (requestBody: String, callIndex: Int) -> Pair<String, String>,
) : AutoCloseable {

    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort
    val callCount = AtomicInteger(0)

    init {
        thread(start = true, isDaemon = true) {
            while (!serverSocket.isClosed) {
                try {
                    serverSocket.accept().use { conn -> handle(conn) }
                } catch (e: SocketException) {
                    break
                }
            }
        }
    }

    private fun handle(conn: Socket) {
        conn.soTimeout = 15_000
        val input = conn.getInputStream()
        val output = conn.getOutputStream()
        val requestLine = readAsciiLine(input) ?: return
        var contentLength = -1
        var chunked = false
        val headers = mutableListOf<String>()
        while (true) {
            val line = readAsciiLine(input) ?: return
            if (line.isEmpty()) break
            headers += line
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
            if (line.equals("Transfer-Encoding: chunked", ignoreCase = true)) chunked = true
        }
        println("STUB REQ: $requestLine | $headers")
        // Ktor CIO 客户端请求体超过阈值会切 chunked 编码，两种形态都要收
        val bodyBytes = if (chunked) readChunked(input) else input.readNBytes(maxOf(contentLength, 0))
        val body = String(bodyBytes, Charsets.UTF_8)
        if (!requestLine.startsWith("POST")) {
            writeResponse(output, 405, "application/json", "{}")
            return
        }
        val (responseBody, contentType) = handler(body, callCount.incrementAndGet())
        writeResponse(output, 200, contentType, responseBody)
    }

    /** 逐块读 chunked 体：每块 = 十六进制长度行 + 数据 + CRLF，0 长度块收尾（后跟 trailer 到空行）。 */
    private fun readChunked(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                while (true) {
                    val trailer = readAsciiLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.write(input.readNBytes(size))
            readAsciiLine(input) // 块尾 CRLF
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun writeResponse(output: OutputStream, status: Int, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bodyBytes)
        output.flush()
    }

    override fun close() {
        serverSocket.close()
    }
}
