package hypogeum.data.sync

import hypogeum.data.serialization.StateDigest
import hypogeum.domain.core.PerPlayer
import hypogeum.domain.core.PlayerId
import hypogeum.domain.game.GameEngine
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.game.Phase
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.SkirmishCommand

/**
 * Bir koltuğun kendi state kopyası ve senkron protokolü.
 * Her iki koltuk aynı batch'leri aynı domain fonksiyonuyla çözer; sonucu hash ile karşılaştırır.
 */
class LockstepSession(
    val seat: PlayerId,
    state: GameState,
    private val net: GameSync,
) {
    var state: GameState = state
        private set

    private var local: CommitBatch? = null
    private var peer: CommitBatch? = null
    private val pendingEvents = mutableListOf<GameEvent>()

    /** Planlamayı kilitler ve commit'i karşı tarafa gönderir. Çözüm, iki commit de gelince olur. */
    fun submitPlanning(commands: List<StrategicCommand>) {
        check(state.phase == Phase.PLANNING) { "not in planning phase" }
        check(local == null) { "already committed this epoch" }
        val batch = CommitBatch(state.epoch, seat, commands, StateDigest.of(state))
        local = batch
        net.send(SyncMessage.Commit(batch))
        resolveIfReady()
    }

    /** Kendi commit'i var, karşı tarafınki henüz gelmedi. */
    val isWaitingForPeer: Boolean get() = local != null && peer == null

    /** Bu koltuğun biriken olayları alır ve temizler. */
    fun takeEvents(): List<GameEvent> {
        val out = pendingEvents.toList()
        pendingEvents.clear()
        return out
    }

    /** Taktik komut: sahibi uygular ve karşı tarafa iletir. Başka koltuk bu fonksiyonu çağıramaz. */
    fun tacticalCommand(command: SkirmishCommand): GameStep {
        val r = GameEngine.tacticalCommand(state, seat, command)
        if (r is GameStep.Ok) {
            state = r.state
            net.send(SyncMessage.Tactical(seat, command))
            net.send(SyncMessage.Digest(StateDigest.of(state)))
        }
        return r
    }

    /** Gelen mesajları sırayla işler. Senkron bozulursa DesyncException. */
    fun pump() {
        for (msg in net.poll()) {
            when (msg) {
                is SyncMessage.Commit -> {
                    val b = msg.batch
                    if (b.draftDigest != StateDigest.of(state)) {
                        throw DesyncException("commit from ${b.actor} was drafted on a different state (epoch ${b.epoch})")
                    }
                    peer = b
                    resolveIfReady()
                }
                is SyncMessage.Tactical -> {
                    val r = GameEngine.tacticalCommand(state, msg.actor, msg.command)
                    if (r !is GameStep.Ok) throw DesyncException("mirrored command rejected: ${(r as GameStep.Rejected).reason}")
                    state = r.state
                    pendingEvents += r.events
                }
                is SyncMessage.Digest -> {
                    val mine = StateDigest.of(state)
                    if (msg.hash != mine) throw DesyncException("state digest mismatch: peer=${msg.hash.take(12)} local=${mine.take(12)}")
                }
            }
        }
    }

    private fun resolveIfReady() {
        val mine = local ?: return
        val theirs = peer ?: return
        val batches = if (seat == PlayerId.P1) {
            PerPlayer(mine.commands, theirs.commands)
        } else {
            PerPlayer(theirs.commands, mine.commands)
        }
        val r = GameEngine.resolveEpoch(state, batches)
        check(r is GameStep.Ok) { "epoch resolution rejected: ${(r as GameStep.Rejected).reason}" }
        state = r.state
        pendingEvents += r.events
        local = null
        peer = null
        net.send(SyncMessage.Digest(StateDigest.of(state)))
    }
}
