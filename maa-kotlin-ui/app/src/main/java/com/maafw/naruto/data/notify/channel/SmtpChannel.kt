/*
 * 火影MAA - 安卓脚本辅助框架
 * Copyright (C) 2024  火影MAA贡献者
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.maafw.naruto.data.notify.channel

import android.content.Context
import com.maafw.naruto.data.notify.PushChannel
import com.maafw.naruto.data.notify.PushResult
import com.maafw.naruto.data.settings.SettingsRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * SMTP 邮件推送通道。
 */
class SmtpChannel(private val context: Context) : PushChannel {

    override val channelId = "SMTP"

    override suspend fun deliver(title: String, body: String): PushResult {
        val host = SettingsRepository.getPushSmtpHost(context)
        val user = SettingsRepository.getPushSmtpUser(context)
        val pass = SettingsRepository.getPushSmtpPass(context)
        val to = SettingsRepository.getPushSmtpTo(context)
        val port = SettingsRepository.getPushSmtpPort(context)

        if (host.isBlank() || user.isBlank() || pass.isBlank() || to.isBlank()) {
            return PushResult.Rejected("SMTP 配置不完整")
        }

        return try {
            val ssl = port == 465
            val socket: Socket = if (ssl) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, port) as SSLSocket
            } else {
                Socket(host, port)
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer: OutputStream = socket.getOutputStream()

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
            cmd("EHLO localhost")
            read()
            cmd("AUTH LOGIN")
            read()
            cmd(Base64.getEncoder().encodeToString(user.toByteArray()))
            read()
            cmd(Base64.getEncoder().encodeToString(pass.toByteArray()))
            read()
            cmd("MAIL FROM:<${user.split("@").first()}@${user.substringAfter("@", host)}>")
            read()
            cmd("RCPT TO:<$to>")
            read()
            cmd("DATA")
            read()
            val subject = "=?UTF-8?B?${Base64.getEncoder().encodeToString(title.toByteArray())}?="
            val data = "From: $user\r\nTo: $to\r\nSubject: $subject\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n$body\r\n.\r\n"
            writer.write(data.toByteArray(Charsets.UTF_8))
            writer.flush()
            read()
            cmd("QUIT")
            read()
            socket.close()
            PushResult.Delivered
        } catch (e: Exception) {
            PushResult.Retryable("SMTP 发送失败: ${e.message}")
        }
    }
}