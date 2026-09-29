package com.mamba.picme.server.cos

import com.mamba.picme.server.config.AppConfig
import com.qcloud.cos.COSClient
import com.qcloud.cos.ClientConfig
import com.qcloud.cos.auth.BasicCOSCredentials
import com.qcloud.cos.http.HttpProtocol
import com.qcloud.cos.model.CannedAccessControlList
import com.qcloud.cos.model.ObjectMetadata
import com.qcloud.cos.model.PutObjectRequest
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date

private val logger = LoggerFactory.getLogger("CosService")

data class CosApkInfo(
    val exists: Boolean,
    val size: String,
    val sizeBytes: Long = 0,
    val lastModified: String,
    val version: String,
    val versionCode: Long = 0,
    val changelog: String = "",
    val publicUrl: String,
)

class CosService(config: AppConfig) {
    private val bucket = config.cosBucket
    private val region = config.cosRegion
    private val ipaKey = "ios/polang.ipa"

    private val client: COSClient? = run {
        if (config.cosSecretId.isBlank() || config.cosSecretKey.isBlank() || bucket.isBlank()) {
            logger.warn("COS not configured: secretId/key/bucket empty, APK features disabled")
            null
        } else {
            val cred = BasicCOSCredentials(config.cosSecretId, config.cosSecretKey)
            val clientConfig = ClientConfig(com.qcloud.cos.region.Region(region)).apply {
                httpProtocol = HttpProtocol.https
            }
            COSClient(cred, clientConfig)
        }
    }

    val configured: Boolean get() = client != null

    val publicUrl: String
        get() = apkPublicUrl(CHANNEL_RELEASE)

    val ipaPublicUrl: String
        get() = "https://cos.polang.net/$ipaKey"

    fun uploadApk(
        inputStream: InputStream,
        contentLength: Long,
        version: String,
        versionCode: Long = 0,
        changelog: String = "",
        channel: String = CHANNEL_RELEASE,
    ): Boolean {
        val c = client ?: run {
            logger.error("COS not configured, cannot upload")
            return false
        }
        val key = apkKey(channel)
        return try {
            val metadata = ObjectMetadata().apply {
                this.contentLength = contentLength
                contentType = "application/vnd.android.package-archive"
                addUserMetadata("version", version)
                addUserMetadata("versioncode", versionCode.toString())
                addUserMetadata("changelog", changelog)
            }
            val request = PutObjectRequest(bucket, key, inputStream, metadata)
            c.putObject(request)
            c.setObjectAcl(bucket, key, CannedAccessControlList.PublicRead)
            logger.info("APK uploaded to COS: bucket=$bucket, key=$key, version=$version($versionCode), size=$contentLength")
            true
        } catch (e: Exception) {
            logger.error("Failed to upload APK to COS", e)
            false
        }
    }

    fun getApkInfo(channel: String = CHANNEL_RELEASE): CosApkInfo {
        val url = apkPublicUrl(channel)
        val c = client ?: return CosApkInfo(false, "", 0, "", "", 0, "", url)
        val key = apkKey(channel)
        return try {
            val metadata = c.getObjectMetadata(bucket, key)
            CosApkInfo(
                exists = true,
                size = formatFileSize(metadata.contentLength),
                sizeBytes = metadata.contentLength,
                lastModified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(metadata.lastModified.time)),
                version = metadata.userMetadata["version"] ?: "",
                versionCode = metadata.userMetadata["versioncode"]?.toLongOrNull() ?: 0,
                changelog = metadata.userMetadata["changelog"] ?: "",
                publicUrl = url,
            )
        } catch (e: Exception) {
            logger.warn("Failed to query COS APK metadata: ${e.message}")
            CosApkInfo(false, "", 0, "", "", 0, "", url)
        }
    }

    fun uploadIpa(inputStream: InputStream, contentLength: Long, version: String): Boolean {
        val c = client ?: run {
            logger.error("COS not configured, cannot upload IPA")
            return false
        }
        return try {
            val metadata = ObjectMetadata().apply {
                this.contentLength = contentLength
                contentType = "application/octet-stream"
                addUserMetadata("version", version)
            }
            val request = PutObjectRequest(bucket, ipaKey, inputStream, metadata)
            c.putObject(request)
            c.setObjectAcl(bucket, ipaKey, CannedAccessControlList.PublicRead)
            logger.info("IPA uploaded to COS: bucket=$bucket, key=$ipaKey, version=$version, size=$contentLength")
            true
        } catch (e: Exception) {
            logger.error("Failed to upload IPA to COS", e)
            false
        }
    }

    fun getIpaInfo(): CosApkInfo {
        val url = ipaPublicUrl
        val c = client ?: return CosApkInfo(false, "", 0, "", "", 0, "", url)
        return try {
            val metadata = c.getObjectMetadata(bucket, ipaKey)
            CosApkInfo(
                exists = true,
                size = formatFileSize(metadata.contentLength),
                sizeBytes = metadata.contentLength,
                lastModified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(metadata.lastModified.time)),
                version = metadata.userMetadata["version"] ?: "",
                publicUrl = url,
            )
        } catch (e: Exception) {
            logger.warn("Failed to query COS IPA metadata: ${e.message}")
            CosApkInfo(false, "", 0, "", "", 0, "", url)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 1) "${String.format("%.1f", mb)} MB" else "${bytes / 1024} KB"
    }

    companion object {
        const val CHANNEL_DEBUG = "debug"
        const val CHANNEL_RELEASE = "release"

        fun isValidChannel(channel: String?): Boolean =
            channel == CHANNEL_DEBUG || channel == CHANNEL_RELEASE

        fun apkKey(channel: String): String = "apk/polang-$channel.apk"

        fun apkPublicUrl(channel: String): String = "https://cos.polang.net/${apkKey(channel)}"
    }
}
