package com.maafw.naruto.data.notify

import android.content.Context
import com.maafw.naruto.data.settings.SettingsRepository
import com.maafw.naruto.third.Ln
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 第三方通知推送喵～
 * 支持：喵提醒 / Server酱 / 钉钉机器人 / SMTP / 自定义 Webhook。
 * 任务完成/失败时由 [push] 按设置渠道推送。
 */
object NotificationPusher {

    private const val TAG = "NotificationPusher"

    /** 按当前设置的渠道推送喵（isSuccess 区分完成/出错，分别受独立开关控制） */
    fun push(context: Context, title: String, content: String, isSuccess: Boolean) {
        val channel = SettingsRepository.getPushChannel(context)
        if (channel == "none") return
        if (isSuccess && !SettingsRepository.isPushNotifySuccess(context)) return
        if (!isSuccess && !SettingsRepository.isPushNotifyError(context)) return
        val result = when (channel) {
            "miaotixing" -> pushMiaoTixing(context, title, content)
            "serverchan" -> pushServerChan(context, title, content)
            "dingtalk" -> pushDingTalk(context, title, content)
            "smtp" -> pushSmtp(context, title, content)
            "webhook" -> pushWebhook(context, title, content)
            else -> null
        }
        if (result != null) {
            Ln.i("$TAG push($channel): $result")
        }
    }

    // ==================== HTTP 工具 ====================

    private fun httpPost(url: String, body: String, contentType: String = "application/x-www-form-urlencoded"): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", contentType)
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val reader = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = reader?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
        conn.disconnect()
        return "code=$code resp=${resp.take(200)}"
    }

    // ==================== 各渠道 ====================

    /** 喵提醒：https://miaotixing.com/jump?to=url-devdoc，参数是 id=喵码（不是 token）喵 */
    private fun pushMiaoTixing(context: Context, title: String, content: String): String {
        val code = SettingsRepository.getPushMiaotixingToken(context)
        if (code.isBlank()) return "喵提醒 喵码未配置"
        val body = "id=${url(code)}&title=${url(title)}&content=${url(content)}"
        return httpPost("https://miaotixing.com/trigger", body)
    }

    /** Server酱：https://sctapi.ftqq.com/{SendKey}.send 喵 */
    private fun pushServerChan(context: Context, title: String, content: String): String {
        val key = SettingsRepository.getPushServerChanKey(context)
        if (key.isBlank()) return "Server酱 SendKey 未配置"
        val body = "title=${url(title)}&desp=${url(content)}"
        return httpPost("https://sctapi.ftqq.com/$key.send", body)
    }

    /** 钉钉机器人喵 */
    private fun pushDingTalk(context: Context, title: String, content: String): String {
        val token = SettingsRepository.getPushDingTalkToken(context)
        if (token.isBlank()) return "钉钉 access_token 未配置"
        val json = "{\"msgtype\":\"text\",\"text\":{\"content\":\"${esc(title)}\\n${esc(content)}\"}}"
        return httpPost("https://oapi.dingtalk.com/robot/send?access_token=$token", json, "application/json; charset=utf-8")
    }

    /** 自定义 Webhook 喵 */
    private fun pushWebhook(context: Context, title: String, content: String): String {
        val url = SettingsRepository.getPushWebhookUrl(context)
        if (url.isBlank()) return "Webhook URL 未配置"
        var body = SettingsRepository.getPushWebhookBody(context)
        if (body.isBlank()) body = "{\"title\":\"{title}\",\"content\":\"{content}\"}"
        body = body.replace("{title}", title).replace("{content}", content)
        return httpPost(url, body, "application/json; charset=utf-8")
    }

    /** SMTP（原始 socket 最小实现：EHLO/AUTH LOGIN/MAIL/RCPT/DATA）喵 */
    private fun pushSmtp(context: Context, title: String, content: String): String {
        val host = SettingsRepository.getPushSmtpHost(context)
        val port = SettingsRepository.getPushSmtpPort(context)
        val user = SettingsRepository.getPushSmtpUser(context)
        val pass = SettingsRepository.getPushSmtpPass(context)
        val to = SettingsRepository.getPushSmtpTo(context)
        if (host.isBlank() || user.isBlank() || pass.isBlank() || to.isBlank()) {
            return "SMTP 配置不完整"
        }
        return try {
            val ssl = port == 465
            val socket: java.net.Socket = if (ssl) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, port) as SSLSocket
            } else {
                java.net.Socket(host, port)
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = socket.getOutputStream()
            fun read(): String {
                val sb = StringBuilder()
                var line: String?
                do {
                    line = reader.readLine()
                    if (line != null) sb.append(line).append("\n")
                } while (line != null && !(line.length >= 4 && line[3] == ' '))
                return sb.toString().trim()
            }

            fun cmd(c: String) {
                writer.write((c + "\r\n").toByteArray(Charsets.UTF_8))
                writer.flush()
            }

            read()
            if (!ssl) {
                cmd("EHLO localhost")
                read()
            } else {
                cmd("EHLO localhost")
                read()
            }
            cmd("AUTH LOGIN")
            read()
            cmd(java.util.Base64.getEncoder().encodeToString(user.toByteArray()))
            read()
            cmd(java.util.Base64.getEncoder().encodeToString(pass.toByteArray()))
            read()
            cmd("MAIL FROM:<${user.split("@").first()}@${user.substringAfter("@", host)}>")
            read()
            cmd("RCPT TO:<$to>")
            read()
            cmd("DATA")
            read()
            val subject = "=?UTF-8?B?${java.util.Base64.getEncoder().encodeToString(title.toByteArray())}?="
            val data = "From: $user\r\nTo: $to\r\nSubject: $subject\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$content\r\n.\r\n"
            writer.write(data.toByteArray(Charsets.UTF_8))
            writer.flush()
            read()
            cmd("QUIT")
            read()
            socket.close()
            "SMTP 发送成功"
        } catch (e: Exception) {
            "SMTP 发送失败: ${e.message}"
        }
    }

    private fun url(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}