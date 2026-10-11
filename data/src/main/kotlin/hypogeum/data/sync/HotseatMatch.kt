package hypogeum.data.sync

import hypogeum.domain.core.PerPlayer
import hypogeum.domain.core.PlayerId
import hypogeum.domain.core.peer
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.SkirmishCommand

/**
 * Tek cihazda iki koltuk. Her koltuğun kendi state kopyası ve ReliableChannel'ı var; taşıma bellek içi.
 * Gerçek ağda (M6 FirebaseSync) aynı sınıf, yalnızca bus yerine başka Link kullanır.
 */
class HotseatMatch private constructor(p1: GameState, p2: GameState, private val bus: LocalBus<WireMessage>) {
    private val channels = PerPlayer(
        ReliableChannel(PlayerId.P1, p1.matchId, bus.endpoint(PlayerId.P1)),
        ReliableChannel(PlayerId.P2, p2.matchId, bus.endpoint(PlayerId.P2)),
    )
    private val sessions = PerPlayer(
        LockstepSession(PlayerId.P1, p1, channels.p1),
        LockstepSession(PlayerId.P2, p2, channels.p2),
    )

    init {
        channels.p1.hello()
        channels.p2.hello()
        drain()
    }

    fun state(seat: PlayerId): GameState = sessions[seat].state

    /** Koltuk bu epoch için commit etti, karşı tarafın commit'i henüz çözülmedi. */
    fun isWaiting(seat: PlayerId): Boolean = sessions[seat].isWaitingForPeer

    /** Dönen olaylar, komutu veren koltuğun raporudur; iki koltuk aynı olayı üretir, bir kez döner. */
    fun commitPlanning(seat: PlayerId, commands: List<StrategicCommand>): List<GameEvent> {
        sessions[seat].submitPlanning(commands)
        drain()
        sessions[seat.peer()].takeEvents()
        return sessions[seat].takeEvents()
    }

    fun tacticalCommand(seat: PlayerId, command: SkirmishCommand): GameStep {
        val r = sessions[seat].tacticalCommand(command)
        drain()
        sessions[seat.peer()].takeEvents()
        return r
    }

    /**
     * Kayıp mesajları kurtarır: iki koltuk da kendi son teslim noktasını bildirir, eksikler yeniden gönderilir.
     * Kayıpsız taşımada gereksizdir; kopma veya kayıp sonrası çağrılır.
     */
    fun resync() {
        channels.p1.hello()
        channels.p2.hello()
        drain()
    }

    private fun drain() {
        while (bus.hasPending()) {
            sessions.p1.pump()
            sessions.p2.pump()
        }
    }

    companion object {
        fun start(initial: GameState): HotseatMatch = HotseatMatch(initial, initial, LocalBus())

        /** Test: iki koltuğun state'i farklı olabilir (senkron bozukluğunu simüle etmek için). */
        fun withStates(p1: GameState, p2: GameState): HotseatMatch = HotseatMatch(p1, p2, LocalBus())

        /** Test: taşıma hattı dışarıdan verilir (kayıp/tekrar/kopma simülasyonu için). */
        fun withBus(initial: GameState, bus: LocalBus<WireMessage>): HotseatMatch = HotseatMatch(initial, initial, bus)
    }
}
