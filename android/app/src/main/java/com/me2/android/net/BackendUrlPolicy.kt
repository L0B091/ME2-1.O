package com.me2.android.net

import java.net.URI

/**
 * B3: las URLs de medios que llegan del backend solo pueden apuntar al MISMO origen del backend (esquema+host+puerto),
 * y el token Bearer solo se adjunta a ese origen. Evita que una URL absoluta ajena reciba el token de sesión.
 */
object BackendUrlPolicy {
    fun resolve(baseUrl: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        val base = runCatching { URI(baseUrl.trimEnd('/')) }.getOrNull() ?: return null
        if (path.startsWith("/") && !path.startsWith("//")) {
            return if (path.contains("..") || path.contains('\\')) null else base.toString() + path
        }
        val uri = runCatching { URI(path) }.getOrNull() ?: return null
        return if (sameOrigin(base, uri) && !(uri.rawPath ?: "").contains("..")) uri.toString() else null
    }

    fun mayAttachToken(baseUrl: String, url: String): Boolean {
        val base = runCatching { URI(baseUrl.trimEnd('/')) }.getOrNull() ?: return false
        val target = runCatching { URI(url) }.getOrNull() ?: return false
        return sameOrigin(base, target)
    }

    private fun port(u: URI) = if (u.port != -1) u.port else when (u.scheme?.lowercase()) { "https" -> 443; "http" -> 80; else -> -1 }

    private fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, ignoreCase = true) && a.host != null && a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)
}
