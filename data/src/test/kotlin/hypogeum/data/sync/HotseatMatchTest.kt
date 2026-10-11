package hypogeum.data.sync

import hypogeum.domain.campus.FacilityType
import hypogeum.domain.core.PlayerId
import hypogeum.domain.game.GameEngine
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameStep
import hypogeum.domain.game.Phase
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.Skirmish
import hypogeum.domain.tactics.SkirmishCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HotseatMatchTest {
    @Test
    fun both_seats_resolve_the_epoch_to_the_same_state() {
        val match = HotseatMatch.start(GameEngine.newGame("m", 7))
        assertTrue(match.commitPlanning(PlayerId.P1, listOf(StrategicCommand.Build(0, FacilityType.BARRACKS))).isEmpty())
        val events = match.commitPlanning(PlayerId.P2, listOf(StrategicCommand.ClaimNode("dock-alloy")))
        assertEquals(1, events.count { it is GameEvent.EpochStarted }, "one report per epoch, not one per seat")
        assertEquals(match.state(PlayerId.P1), match.state(PlayerId.P2))
        assertEquals(2, match.state(PlayerId.P1).epoch)
    }

    @Test
    fun a_commit_drafted_on_a_different_state_is_a_desync() {
        val base = GameEngine.newGame("m", 7)
        // Seat P2 farklı bir RNG durumuyla başlar: P2'nin hash'i P1'inkinden farklı olur.
        val diverged = base.copy(rng = base.rng.copy(s = base.rng.s + 1))
        val match = HotseatMatch.withStates(base, diverged)
        assertFailsWith<DesyncException> {
            match.commitPlanning(PlayerId.P1, emptyList())
            match.commitPlanning(PlayerId.P2, emptyList())
        }
    }

    @Test
    fun tactical_commands_are_owned_and_mirrored() {
        val match = HotseatMatch.start(GameEngine.newGame("m", 7))
        val ids = match.state(PlayerId.P1).campuses.p1.roster.map { it.id }
        match.commitPlanning(PlayerId.P1, listOf(StrategicCommand.Dispatch("dock", ids)))
        match.commitPlanning(PlayerId.P2, emptyList())
        assertEquals(Phase.TACTICAL, match.state(PlayerId.P2).phase)

        val rejected = match.tacticalCommand(PlayerId.P2, SkirmishCommand.EndTurn)
        assertEquals(GameStep.Rejected("not part of this mission"), rejected)

        val legal = Skirmish.legalCommands(match.state(PlayerId.P1).missions.first().skirmish)
        val move = legal.first { it is SkirmishCommand.Move }
        assertIs<GameStep.Ok>(match.tacticalCommand(PlayerId.P1, move))
        assertEquals(match.state(PlayerId.P1), match.state(PlayerId.P2))
    }

    @Test
    fun long_hotseat_session_never_desyncs() {
        val match = HotseatMatch.start(GameEngine.newGame("m", 13))
        repeat(8) { epoch ->
            val p1 = if (epoch % 2 == 0) listOf(StrategicCommand.ClaimNode("gate-cells")) else emptyList()
            match.commitPlanning(PlayerId.P1, p1)
            while (match.state(PlayerId.P1).phase == Phase.TACTICAL) {
                val owner = match.state(PlayerId.P1).missions.first().skirmish.activeOwner
                val options = Skirmish.legalCommands(match.state(PlayerId.P1).missions.first().skirmish)
                match.tacticalCommand(owner, options.last())
            }
            match.commitPlanning(PlayerId.P2, emptyList())
            assertEquals(match.state(PlayerId.P1), match.state(PlayerId.P2))
        }
    }

    @Test
    fun co_op_mission_alternates_turns_and_both_seats_stay_in_sync() {
        val match = HotseatMatch.start(GameEngine.newGame("m", 17))
        val p1 = match.state(PlayerId.P1).campuses.p1.roster.first().id
        val p2 = match.state(PlayerId.P1).campuses.p2.roster.first().id
        match.commitPlanning(PlayerId.P1, listOf(StrategicCommand.Dispatch("gate", listOf(p1))))
        val launched = match.commitPlanning(PlayerId.P2, listOf(StrategicCommand.Dispatch("gate", listOf(p2))))
        assertTrue(launched.any { it is GameEvent.MissionLaunched })
        assertEquals(Phase.TACTICAL, match.state(PlayerId.P2).phase)
        assertEquals(match.state(PlayerId.P1), match.state(PlayerId.P2))

        val mission = match.state(PlayerId.P1).missions.single()
        assertEquals(listOf(PlayerId.P1, PlayerId.P2), mission.participants)
        assertEquals(PlayerId.P1, mission.skirmish.activeOwner)

        // Sıra P1'de: P2 komut veremez, state değişmez.
        assertEquals(GameStep.Rejected("not your turn"), match.tacticalCommand(PlayerId.P2, SkirmishCommand.EndTurn))
        assertEquals(match.state(PlayerId.P1), match.state(PlayerId.P2))

        // Her iki koltuk sırayla oynar; her adımda iki state de aynı kalmalı (digest kontrolü içeride).
        var guard = 0
        while (match.state(PlayerId.P1).phase == Phase.TACTICAL && guard < 5_000) {
            guard++
            val active = match.state(PlayerId.P1).missions.first().skirmish.activeOwner
            val options = Skirmish.legalCommands(match.state(PlayerId.P1).missions.first().skirmish)
            val pick = options[guard % options.size]
            val r = match.tacticalCommand(active, pick)
            assertIs<GameStep.Ok>(r)
            assertEquals(match.state(PlayerId.P1), match.state(PlayerId.P2))
        }
        assertEquals(Phase.PLANNING, match.state(PlayerId.P1).phase)
        assertEquals(match.state(PlayerId.P1), match.state(PlayerId.P2))
    }
}
