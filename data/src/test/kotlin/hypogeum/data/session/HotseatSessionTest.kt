package hypogeum.data.session

import hypogeum.domain.core.PlayerId
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.Phase
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.Skirmish
import hypogeum.domain.tactics.SkirmishCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotseatSessionTest {
    @Test
    fun commits_switch_seats_and_resolve_the_epoch_on_the_second() {
        val s = HotseatSession(seed = 2)
        assertEquals(PlayerId.P1, s.seat)
        s.commit()
        assertEquals(PlayerId.P2, s.seat)
        assertTrue(s.awaitingHandoff)
        s.commit()
        assertEquals(PlayerId.P1, s.seat)
        assertEquals(2, s.state.epoch)
        assertTrue(s.lastEvents.any { it is GameEvent.EpochStarted })
    }

    @Test
    fun seats_alternate_across_epochs() {
        val s = HotseatSession(seed = 2)
        repeat(3) { epoch ->
            assertEquals(PlayerId.P1, s.seat, "epoch ${epoch + 1} starts with P1")
            s.commit()
            assertEquals(PlayerId.P2, s.seat)
            s.commit()
        }
        assertEquals(4, s.state.epoch)
    }

    @Test
    fun drafts_are_per_seat() {
        val s = HotseatSession(seed = 2)
        s.addDraft(StrategicCommand.ClaimNode("dock-alloy"))
        assertEquals(1, s.draft().size)
        s.commit()
        assertTrue(s.draft().isEmpty(), "P2 starts with an empty draft")
        s.addDraft(StrategicCommand.ClaimNode("gate-cells"))
        s.commit()
        assertEquals(PlayerId.P1, s.seat)
    }

    @Test
    fun a_dispatch_moves_the_session_into_tactics_owned_by_the_sender() {
        val s = HotseatSession(seed = 4)
        val soldier = s.state.campuses.p1.roster.first().id
        s.addDraft(StrategicCommand.Dispatch("dock", listOf(soldier)))
        s.commit()
        s.commit()
        assertEquals(Phase.TACTICAL, s.state.phase)
        assertEquals(PlayerId.P1, s.activeSeat)
        s.tacticalCommand(SkirmishCommand.EndTurn)
        assertNull(s.lastError)
        assertTrue(s.state.phase == Phase.TACTICAL || s.state.phase == Phase.PLANNING)
        val legal = Skirmish.legalCommands(s.state.missions.first().skirmish)
        assertTrue(legal.isNotEmpty())
    }

    @Test
    fun tactical_errors_are_reported_not_thrown() {
        val s = HotseatSession(seed = 4)
        val soldier = s.state.campuses.p1.roster.first().id
        s.addDraft(StrategicCommand.Dispatch("dock", listOf(soldier)))
        s.commit()
        s.commit()
        s.tacticalCommand(SkirmishCommand.Move("nobody", hypogeum.domain.core.Pos(0, 0)))
        assertTrue(s.lastError != null)
    }
}
