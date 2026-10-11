package hypogeum.domain.core

import kotlinx.serialization.Serializable

/**
 * SplitMix64 durumu. Tek RNG akışı GameState içinde taşınır; her çekim durumu ilerletir.
 * Platform bağımlı RNG (kotlin.random.Random, java.util.Random) kullanılmaz.
 */
@Serializable
data class RngState(val s: Long)

data class Draw<out T>(val value: T, val next: RngState)

object Rng {
    private val GAMMA = 0x9E3779B97F4A7C15uL.toLong()

    fun nextLong(state: RngState): Draw<Long> {
        val s = state.s + GAMMA
        var z = s
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        z = z xor (z ushr 31)
        return Draw(z, RngState(s))
    }

    /** [0, bound) aralığında. bound > 0 olmalı. Modulo sapması kabul edilmiş durumda (bound küçük). */
    fun nextInt(state: RngState, bound: Int): Draw<Int> {
        require(bound > 0) { "bound must be positive" }
        val d = nextLong(state)
        val v = ((d.value ushr 1) % bound).toInt()
        return Draw(v, d.next)
    }

    fun nextBoolean(state: RngState): Draw<Boolean> {
        val d = nextLong(state)
        return Draw((d.value and 1L) == 1L, d.next)
    }
}
