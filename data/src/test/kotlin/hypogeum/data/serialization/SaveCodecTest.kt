package hypogeum.data.serialization

import hypogeum.domain.game.GameEngine
import hypogeum.domain.game.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SaveCodecTest {
    private val sample: GameState = GameEngine.newGame(matchId = "m-test", seed = 42L)

    @Test
    fun round_trip_preserves_state_and_bytes() {
        val text = SaveCodec.encode(sample)
        val decoded = SaveCodec.decode(text)
        assertEquals(sample, decoded)
        assertEquals(text, SaveCodec.encode(decoded))
    }

    @Test
    fun digest_is_stable_across_calls() {
        assertEquals(StateDigest.of(sample), StateDigest.of(sample))
    }

    @Test
    fun digest_changes_when_state_changes() {
        val advanced = sample.copy(epoch = sample.epoch + 1)
        assertEquals(false, StateDigest.of(sample) == StateDigest.of(advanced))
    }

    @Test
    fun rejects_unknown_schema_version() {
        val text = SaveCodec.encode(sample).replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        assertFailsWith<IncompatibleSaveException> { SaveCodec.decode(text) }
    }

    @Test
    fun rejects_unknown_engine_version() {
        val text = SaveCodec.encode(sample).replace("\"engineVersion\":\"0.1.0\"", "\"engineVersion\":\"9.9.9\"")
        assertFailsWith<IncompatibleSaveException> { SaveCodec.decode(text) }
    }
}
