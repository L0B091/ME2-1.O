package com.me2.android.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Textos de la bitácora (sin plan/premium/free/demo). Puro para test. */
data class BitacoraContent(
    val userName: String,
    val avatarLine: String?,
    /** Fecha local del dispositivo (dd/MM/yyyy); reemplaza la antigua línea NODO_ID. */
    val dateLine: String,
    val mailLine: String,
    val linkLabel: String,
    val linkProgressTenths: Int
) {
    fun allTexts(): List<String> = listOfNotNull(userName, avatarLine, dateLine, mailLine, linkLabel)

    companion object {
        fun build(
            displayName: String?, email: String, userId: String, avatarName: String?, usageMinutes: Long,
            now: Date = Date(com.me2.android.time.Me2Clock.now()), timeZone: TimeZone = com.me2.android.time.Me2Clock.ZONE
        ): BitacoraContent {
            val pct = LinkProgress.percent(usageMinutes)
            val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: email.substringBefore('@')
            return BitacoraContent(
                userName = name.uppercase(Locale.getDefault()),
                avatarLine = avatarName?.trim()?.takeIf { it.isNotEmpty() }?.let { "AVATAR // ${it.uppercase(Locale.getDefault())}" },
                dateLine = formatDate(now, timeZone),
                mailLine = "MAIL // $email",
                linkLabel = LinkProgress.label(pct),
                linkProgressTenths = LinkProgress.progressTenths(pct)
            )
        }

        /** Fecha dd/MM/yyyy en la zona fija de ME2 (no la del dispositivo). */
        fun formatDate(now: Date, timeZone: TimeZone = com.me2.android.time.Me2Clock.ZONE): String =
            SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).apply { this.timeZone = timeZone }.format(now)

        /** Id estable y corto derivado del userId (formato 00-00-00-0). */
        fun nodeId(userId: String): String {
            val h = (userId.hashCode().toLong() and 0x7fffffff) % 10_000_000L
            val s = h.toString().padStart(7, '0')
            return "${s.substring(0, 2)}-${s.substring(2, 4)}-${s.substring(4, 6)}-${s.substring(6)}"
        }
    }
}
