package com.me2.android.ui

import java.util.Locale

/** Textos de la bitácora (sin plan/premium/free/demo). Puro para test. */
data class BitacoraContent(
    val userName: String,
    val avatarLine: String?,
    val nodeLine: String,
    val mailLine: String,
    val linkLabel: String,
    val linkProgressTenths: Int
) {
    fun allTexts(): List<String> = listOfNotNull(userName, avatarLine, nodeLine, mailLine, linkLabel)

    companion object {
        fun build(displayName: String?, email: String, userId: String, avatarName: String?, usageMinutes: Long): BitacoraContent {
            val pct = LinkProgress.percent(usageMinutes)
            val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: email.substringBefore('@')
            return BitacoraContent(
                userName = name.uppercase(Locale.getDefault()),
                avatarLine = avatarName?.trim()?.takeIf { it.isNotEmpty() }?.let { "AVATAR // ${it.uppercase(Locale.getDefault())}" },
                nodeLine = "NODO_ID // ${nodeId(userId)}",
                mailLine = "MAIL // $email",
                linkLabel = LinkProgress.label(pct),
                linkProgressTenths = LinkProgress.progressTenths(pct)
            )
        }

        /** Id estable y corto derivado del userId (formato 00-00-00-0). */
        fun nodeId(userId: String): String {
            val h = (userId.hashCode().toLong() and 0x7fffffff) % 10_000_000L
            val s = h.toString().padStart(7, '0')
            return "${s.substring(0, 2)}-${s.substring(2, 4)}-${s.substring(4, 6)}-${s.substring(6)}"
        }
    }
}
