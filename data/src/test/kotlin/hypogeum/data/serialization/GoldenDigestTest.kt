package hypogeum.data.serialization

import hypogeum.domain.game.GameEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class GoldenDigestTest {
    /**
     * Regresyon pini: bu değer değişirse kanonik JSON ya da başlangıç state'i değişmiştir.
     * Bilinçli bir değişiklikse bu değeri ve ENGINE_VERSION'ı birlikte güncelle.
     */
    @Test
    fun new_game_digest_is_pinned() {
        val state = GameEngine.newGame(matchId = "m-golden", seed = 42L)
        assertEquals(PINNED, StateDigest.of(state))
    }

    private companion object {
        const val PINNED = "faf9dba3a5ad41e602f81556403154dff92362e5fbf0a9bba6349207ec51e1d4"
    }
}
