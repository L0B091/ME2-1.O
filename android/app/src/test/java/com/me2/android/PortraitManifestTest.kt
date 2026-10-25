package com.me2.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PortraitManifestTest {
    @Test fun todasLasActivitiesEnVertical() {
        val f = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        val acts = Regex("<activity\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(f.readText()).map { it.value }.toList()
        assert(acts.isNotEmpty())
        val sinPortrait = acts.filterNot { it.contains("Theme.Translucent") /* trampolín sin UI: Android 8.0 crashea si fija orientación */ || it.contains("android:screenOrientation=\"portrait\"") }
        assertEquals(emptyList<String>(), sinPortrait)
    }
}
