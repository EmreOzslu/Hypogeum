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
 * Tek cihazda iki koltuk. Her koltuğun kendi state kopyası var; mesajlar bellek içi taşınır
 * ve her adımda iki taraf hash ile karşılaştırılır. Ağ (M6) geldiğinde bu sınıf değişmeden kalır.
 */
class HotseatMatch private constructor(p1: GameState, p2: GameState) {
    private val bus = LocalHotseatSync()
    private val sessions = PerPlayer(
        LockstepSession(PlayerId.P1, p1, bus.endpoint(PlayerId.P1)),
        LockstepSession(PlayerId.P2, p2, bus.endpoint(PlayerId.P2)),
    )

    fun state(seat: PlayerId): GameState = sessions[seat].state

    /** Koltuk planlamasını commit eder. İki koltuk da commit edince epoch çözülür. */
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

    private fun drain() {
        while (bus.hasPending()) {
            sessions.p1.pump()
            sessions.p2.pump()
        }
    }

    companion object {
        fun start(initial: GameState): HotseatMatch = HotseatMatch(initial, initial)

        /** Test: iki koltuğun state'i farklı başlayabilir (senkron bozukluğunu simüle etmek için). */
        fun withStates(p1: GameState, p2: GameState): HotseatMatch = HotseatMatch(p1, p2)
    }
}
