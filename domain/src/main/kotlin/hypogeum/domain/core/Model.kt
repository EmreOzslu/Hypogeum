package hypogeum.domain.core

import kotlinx.serialization.Serializable

@Serializable
data class Pos(val x: Int, val y: Int)

@Serializable
enum class PlayerId { P1, P2 }

/** Map yerine sabit alanlı çift: sıra ve anahtar kararlı. */
@Serializable
data class PerPlayer<T>(val p1: T, val p2: T) {
    operator fun get(id: PlayerId): T = when (id) {
        PlayerId.P1 -> p1
        PlayerId.P2 -> p2
    }

    fun with(id: PlayerId, value: T): PerPlayer<T> = when (id) {
        PlayerId.P1 -> copy(p1 = value)
        PlayerId.P2 -> copy(p2 = value)
    }

    inline fun <R> map(transform: (T) -> R): PerPlayer<R> = PerPlayer(transform(p1), transform(p2))
}

fun PlayerId.peer(): PlayerId = if (this == PlayerId.P1) PlayerId.P2 else PlayerId.P1

/** Deterministik ID üretimi: UUID yok, sayaç var. */
@Serializable
data class IdCounter(val next: Int = 1) {
    fun issue(prefix: String): Pair<String, IdCounter> = "$prefix-$next" to IdCounter(next + 1)
}
