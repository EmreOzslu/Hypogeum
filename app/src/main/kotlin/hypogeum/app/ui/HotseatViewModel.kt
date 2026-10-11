package hypogeum.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import hypogeum.data.session.HotseatSession
import hypogeum.domain.game.Phase
import hypogeum.domain.core.Pos
import hypogeum.domain.tactics.Side
import hypogeum.domain.tactics.SkirmishCommand

/** UI durumu: oturum, sayaç (yeniden çizim tetikler), el değiştirme ve seçili birim. */
class HotseatViewModel : ViewModel() {
    private var currentSession = HotseatSession(seed = 1L)

    val session: HotseatSession get() = currentSession

    /** Her değişiklikte artar; ekran bu değeri okuyarak yeniden çizilir. */
    var version by mutableIntStateOf(0)
        private set

    /** Commit sonrası karşı koltuğa geçiş ekranı gösterilir (planı gizlemek için). */
    var handoff by mutableStateOf(false)
        private set

    var selectedUnit by mutableStateOf<String?>(null)
        private set

    fun act(block: HotseatSession.() -> Unit) {
        currentSession.block()
        version++
    }

    fun commit() {
        act { commit() }
        handoff = session.awaitingHandoff
        selectedUnit = null
    }

    fun confirmHandoff() {
        handoff = false
    }

    fun restart(seed: Long) {
        currentSession = HotseatSession(seed)
        handoff = false
        selectedUnit = null
        version++
    }

    /** Taktik ızgaradaki bir kareye dokunma: kendi birimini seç, düşmana ateş et, boş kareye yürü. */
    fun tapCell(p: Pos) {
        val st = session.state.missions.firstOrNull()?.skirmish ?: return
        val at = st.combatants.firstOrNull { it.pos == p }
        val active = session.activeSeat
        if (at != null && at.side == Side.PLAYER && at.owner == active) {
            selectedUnit = at.id
            version++
            return
        }
        val sel = selectedUnit ?: return
        val cmd = if (at != null && at.side == Side.ALIEN) {
            SkirmishCommand.Shoot(sel, at.id)
        } else {
            SkirmishCommand.Move(sel, p)
        }
        act { tacticalCommand(cmd) }
        if (session.state.phase != Phase.TACTICAL) selectedUnit = null
    }

    fun endTurn() {
        act { tacticalCommand(SkirmishCommand.EndTurn) }
    }
}
