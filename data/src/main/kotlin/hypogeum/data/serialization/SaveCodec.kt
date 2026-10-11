package hypogeum.data.serialization

import hypogeum.domain.game.ENGINE_VERSION
import hypogeum.domain.game.GameState
import hypogeum.domain.game.SCHEMA_VERSION
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Tek JSON konfigürasyonu. Digest bu çıktıya dayandığı için burada değişmemeli:
 * değişirse golden digest testi kırılır ve bu bilinçli bir sürüm kararı olmalı.
 */
object JsonConfig {
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "t"
        ignoreUnknownKeys = false
        prettyPrint = false
    }
}

class IncompatibleSaveException(message: String) : RuntimeException(message)

object SaveCodec {
    fun encode(state: GameState): String = JsonConfig.json.encodeToString(state)

    fun decode(text: String): GameState {
        val state = JsonConfig.json.decodeFromString<GameState>(text)
        if (state.schemaVersion != SCHEMA_VERSION) {
            throw IncompatibleSaveException(
                "schemaVersion ${state.schemaVersion} desteklenmiyor (beklenen $SCHEMA_VERSION)",
            )
        }
        if (state.engineVersion != ENGINE_VERSION) {
            throw IncompatibleSaveException(
                "engineVersion ${state.engineVersion} desteklenmiyor (beklenen $ENGINE_VERSION)",
            )
        }
        return state
    }
}

object StateDigest {
    /** Kanonik JSON üzerinden SHA-256, hex. Cihazlar arası karşılaştırma için. */
    fun of(state: GameState): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(SaveCodec.encode(state).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
