package hypogeum.data.sync

import hypogeum.domain.core.PlayerId
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.SkirmishCommand
import kotlinx.serialization.Serializable

/** Planlama batch'i. draftDigest, gönderenin commit anındaki state'inin hash'i. */
@Serializable
data class CommitBatch(
    val epoch: Int,
    val actor: PlayerId,
    val commands: List<StrategicCommand>,
    val draftDigest: String,
)

@Serializable
sealed interface SyncMessage {
    @Serializable data class Commit(val batch: CommitBatch) : SyncMessage
    @Serializable data class Tactical(val actor: PlayerId, val command: SkirmishCommand) : SyncMessage
    /** Bir state değişiminden sonra gönderenin state hash'i. Alıcı kendi hash'iyle karşılaştırır. */
    @Serializable data class Digest(val hash: String) : SyncMessage
}

/** Senkron bozuldu: iki taraf aynı state'e sahip değil. Oyun ilerlemez. */
class DesyncException(message: String) : RuntimeException(message)
