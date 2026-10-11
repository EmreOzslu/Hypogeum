package hypogeum.domain.game

import hypogeum.domain.core.IdCounter
import hypogeum.domain.core.RngState
import kotlinx.serialization.Serializable

/** Denge/kural sürümü. Denge değişince artırılır; kayıt ve handshake buna bağlanır. */
const val ENGINE_VERSION = "0.1.0"
const val SCHEMA_VERSION = 1

/**
 * M0 iskeleti. Faz, dünya, kampüs, mission alanları M1+ milestone'larında eklenir.
 * Tüm alanlar sıralı/Int/Long; Map, Set, Float yok.
 */
@Serializable
data class GameState(
    val schemaVersion: Int,
    val engineVersion: String,
    val matchId: String,
    val seed: Long,
    val rng: RngState,
    val ids: IdCounter,
    val epoch: Int,
)

object GameEngine {
    fun newGame(matchId: String, seed: Long): GameState = GameState(
        schemaVersion = SCHEMA_VERSION,
        engineVersion = ENGINE_VERSION,
        matchId = matchId,
        seed = seed,
        rng = RngState(seed),
        ids = IdCounter(),
        epoch = 0,
    )
}
