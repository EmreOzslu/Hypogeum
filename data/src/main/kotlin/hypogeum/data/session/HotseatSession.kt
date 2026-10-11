package hypogeum.data.session

import hypogeum.data.sync.HotseatMatch
import hypogeum.domain.core.PerPlayer
import hypogeum.domain.core.PlayerId
import hypogeum.domain.core.peer
import hypogeum.domain.game.GameEngine
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.game.Phase
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.SkirmishCommand

/**
 * Tek cihazda iki koltuğun oyun akışı (UI'dan bağımsız). Planlama taslakları koltuk bazlıdır;
 * commit ikinci koltuktan sonra epoch'u çözer; taktik faz görev sahibine aittir.
 */
class HotseatSession private constructor(private val match: HotseatMatch, initialSeed: Long) {
    constructor(seed: Long) : this(HotseatMatch.start(GameEngine.newGame("hotseat-$seed", seed)), seed)

    var seat: PlayerId = PlayerId.P1
        private set

    private val drafts = PerPlayer(mutableListOf<StrategicCommand>(), mutableListOf<StrategicCommand>())
    private val committed = mutableSetOf<PlayerId>()

    /** Son işlemden kalan olaylar (UI gösterir, sonra temizlenir). */
    var lastEvents: List<GameEvent> = emptyList()
        private set

    /** Son hata (reddedilen komut ya da kural ihlali). Yeni işlemde sıfırlanır. */
    var lastError: String? = null
        private set

    val state: GameState get() = match.state(seat)

    /** Sıra hangi koltukta: taktikte görev sahibi, planlamada aktif koltuk. */
    val activeSeat: PlayerId
        get() = if (state.phase == Phase.TACTICAL) state.missions.first().skirmish.activeOwner else seat

    /** Commit edip sıra karşı koltuğa geçtiyse true: UI ekranı devretmeli. */
    val awaitingHandoff: Boolean get() = state.phase == Phase.PLANNING && committed.isNotEmpty()

    fun draft(): List<StrategicCommand> = drafts[seat].toList()

    fun addDraft(cmd: StrategicCommand) {
        drafts[seat] += cmd
    }

    fun clearDraft() {
        drafts[seat].clear()
    }

    /** Bu koltuğun taslağını commit eder. Her iki koltuk de commit ederse epoch çözülür. */
    fun commit() {
        lastError = null
        if (state.phase != Phase.PLANNING) {
            lastError = "Şu an taktik faz var"
            return
        }
        if (seat in committed) {
            lastError = "Bu koltuk zaten commit etti"
            return
        }
        val batch = drafts[seat].toList()
        drafts[seat].clear()
        lastEvents = match.commitPlanning(seat, batch)
        committed += seat
        if (committed.size == 2) {
            committed.clear()
            seat = PlayerId.P1
        } else {
            seat = seat.peer()
        }
    }

    /** Taktik komut. Görev sahibi koltuk seçili olmalı. */
    fun tacticalCommand(cmd: SkirmishCommand) {
        lastError = null
        val owner = activeSeat
        val r = match.tacticalCommand(owner, cmd)
        when (r) {
            is GameStep.Rejected -> lastError = r.reason
            is GameStep.Ok -> lastEvents = r.events
        }
        if (state.phase == Phase.PLANNING) seat = PlayerId.P1
    }

    /** Taktik sırasında hamle yapacak koltuğu seçer (kendi görevinde). */
    fun selectSeatForTactics() {
        if (state.phase == Phase.TACTICAL) seat = activeSeat
    }

    companion object {
        /** Test: başlangıç durumu ve taşıma ile kurulum. */
        fun fromMatch(match: HotseatMatch, seed: Long): HotseatSession = HotseatSession(match, seed)
    }
}
