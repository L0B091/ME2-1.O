package com.me2.android.gallery

import kotlin.random.Random

/** Selección aleatoria de clips para el widget, sin repetir el anterior cuando hay más de uno. */
object ClipPicker {
    fun <T> pickRandom(items: List<T>, previous: T?, random: Random = Random.Default): T? {
        if (items.isEmpty()) return null
        if (items.size == 1) return items.first()
        val pool = items.filter { it != previous }.ifEmpty { items }
        return pool[random.nextInt(pool.size)]
    }
}
