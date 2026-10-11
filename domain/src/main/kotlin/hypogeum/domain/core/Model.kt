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

    inline fun <R> map(transform: (T) -> R): PerPlayer<R> = PerPlayer(transform(p1), transform(p2))
}

/** Deterministik ID üretimi: UUID yok, sayaç var. */
@Serializable
data class IdCounter(val next: Int = 1) {
    fun issue(prefix: String): Pair<String, IdCounter> = "$prefix-$next" to IdCounter(next + 1)
}
