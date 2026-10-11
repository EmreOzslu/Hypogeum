package hypogeum.domain.strategy

import hypogeum.domain.campus.FacilityType
import hypogeum.domain.content.Content
import hypogeum.domain.game.Phase
import hypogeum.domain.game.GameEngine
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.roster.SoldierClass
import hypogeum.domain.tactics.Skirmish
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CampaignTest {
    private fun commit(s: GameState, vararg cmds: StrategicCommand): GameState {
        val r = GameEngine.commit(s, cmds.toList())
        assertIs<GameStep.Ok>(r)
        return r.state
    }

    private fun rejections(s: GameState, vararg cmds: StrategicCommand): List<String> {
        val r = GameEngine.commit(s, cmds.toList()) as GameStep.Ok
        return r.events.filterIsInstance<GameEvent.CommandRejected>().map { it.reason }
    }

    private fun endEpoch(s: GameState): GameState = commit(s)

    @Test
    fun new_game_starts_with_two_soldiers_and_start_stock() {
        val s = GameEngine.newGame("m", seed = 3)
        assertEquals(2, s.campus.roster.size)
        assertEquals(Content.START_STOCK, s.campus.stock)
        assertEquals(Phase.PLANNING, s.phase)
        assertEquals(1, s.epoch)
    }

    @Test
    fun building_into_occupied_or_queued_slot_is_rejected() {
        val s = GameEngine.newGame("m", 3)
        val rej = rejections(
            s,
            StrategicCommand.Build(0, FacilityType.REACTOR),
            StrategicCommand.Build(0, FacilityType.FOUNDRY),
        )
        assertEquals(listOf("slot occupied"), rej)
    }

    @Test
    fun build_costs_resources_and_completes_after_its_epochs() {
        // Commit kendi epoch'unu da kapatır: 2 epoch'luk inşa, commit + bir epoch sonra biter.
        var s = commit(GameEngine.newGame("m", 3), StrategicCommand.Build(0, FacilityType.REACTOR))
        val spec = Content.facility(FacilityType.REACTOR)
        assertEquals(Content.START_STOCK - spec.cost, s.campus.stock)
        assertEquals(null, s.campus.grid[0])
        s = endEpoch(s)
        assertEquals(FacilityType.REACTOR, s.campus.grid[0]?.type)
    }

    @Test
    fun reactor_produces_cells_each_epoch() {
        var s = commit(GameEngine.newGame("m", 3), StrategicCommand.Build(0, FacilityType.REACTOR))
        s = endEpoch(endEpoch(s))
        val before = s.campus.stock.cells
        s = endEpoch(s)
        assertEquals(before + Content.facility(FacilityType.REACTOR).output.cells, s.campus.stock.cells)
    }

    @Test
    fun recruiting_requires_a_barracks_and_adds_a_soldier_after_one_epoch() {
        var s = GameEngine.newGame("m", 3)
        assertEquals(listOf("build a barracks first"), rejections(s, StrategicCommand.Recruit(SoldierClass.MEDIC)))
        s = endEpoch(commit(s, StrategicCommand.Build(0, FacilityType.BARRACKS)))
        val before = s.campus.roster.size
        s = commit(s, StrategicCommand.Recruit(SoldierClass.MEDIC))
        assertEquals(before, s.campus.roster.size)
        s = endEpoch(s)
        assertEquals(before + 1, s.campus.roster.size)
        assertEquals(SoldierClass.MEDIC, s.campus.roster.last().cls)
    }

    @Test
    fun barracks_capacity_limits_concurrent_training() {
        var s = endEpoch(commit(GameEngine.newGame("m", 3), StrategicCommand.Build(0, FacilityType.BARRACKS)))
        assertEquals(
            listOf("all barracks are busy"),
            rejections(s, StrategicCommand.Recruit(SoldierClass.RIFLEMAN), StrategicCommand.Recruit(SoldierClass.RIFLEMAN)),
        )
        s = commit(s, StrategicCommand.Recruit(SoldierClass.RIFLEMAN))
        s = endEpoch(s)
        assertEquals(3, s.campus.roster.size)
    }

    @Test
    fun injured_or_unknown_soldiers_cannot_be_dispatched() {
        val s0 = GameEngine.newGame("m", 3)
        val first = s0.campus.roster[0]
        val injured = s0.copy(campus = s0.campus.copy(roster = listOf(first.copy(injuryEpochs = 2)) + s0.campus.roster.drop(1)))
        assertEquals(
            listOf("${first.id} is injured"),
            rejections(injured, StrategicCommand.Dispatch("dock", listOf(first.id))),
        )
        assertEquals(listOf("no soldier nobody"), rejections(s0, StrategicCommand.Dispatch("dock", listOf("nobody"))))
        assertEquals(listOf("unknown region moon"), rejections(s0, StrategicCommand.Dispatch("moon", listOf(first.id))))
    }

    @Test
    fun dispatch_starts_a_tactical_phase_and_blocks_further_orders() {
        val s0 = GameEngine.newGame("m", 3)
        val ids = s0.campus.roster.map { it.id }
        val r = GameEngine.commit(
            s0,
            listOf(StrategicCommand.Dispatch("dock", ids), StrategicCommand.Build(0, FacilityType.REACTOR)),
        ) as GameStep.Ok
        assertEquals(Phase.TACTICAL, r.state.phase)
        assertEquals(2, r.state.mission!!.squad.size)
        assertEquals(listOf("mission in progress"), r.events.filterIsInstance<GameEvent.CommandRejected>().map { it.reason })
    }

    @Test
    fun finishing_a_mission_applies_deaths_and_survivor_state() {
        val s0 = GameEngine.newGame("m", seed = 11)
        val dispatched = s0.campus.roster[0].id
        var s = (GameEngine.commit(s0, listOf(StrategicCommand.Dispatch("dock", listOf(dispatched)))) as GameStep.Ok).state
        val events = mutableListOf<GameEvent>()
        var steps = 0
        while (s.phase == Phase.TACTICAL && steps < 5_000) {
            val options = Skirmish.legalCommands(s.mission!!.skirmish)
            val r = GameEngine.tacticalCommand(s, options[steps % options.size]) as GameStep.Ok
            s = r.state
            events += r.events
            steps++
        }
        assertEquals(Phase.PLANNING, s.phase)
        assertEquals(null, s.mission)
        assertEquals(2, s.epoch)
        val killed = events.filterIsInstance<GameEvent.SoldierKilled>().map { it.soldierId }
        assertTrue(killed.all { it == dispatched }, "only the dispatched soldier can die")
        assertEquals(2 - killed.size, s.campus.roster.size)
        assertTrue(events.any { it is GameEvent.MissionEnded })
    }

    @Test
    fun same_seed_and_same_orders_give_identical_campaigns() {
        assertEquals(playCampaign(seed = 4, orderSeed = 9), playCampaign(seed = 4, orderSeed = 9))
    }

    @Test
    fun long_random_campaigns_keep_invariants() {
        for (seed in 1L..40L) {
            val end = playCampaign(seed, orderSeed = seed * 13, epochs = 12)
            assertInvariants(end)
        }
    }

    /** Rastgele stratejik emirler ve rastgele taktik hamleler; her epoch sonunda invariant kontrolü. */
    private fun playCampaign(seed: Long, orderSeed: Long, epochs: Int = 6): GameState {
        val chooser = Random(orderSeed)
        var s = GameEngine.newGame("m-$seed", seed)
        var done = 0
        var guard = 0
        while (done < epochs && guard < 2_000) {
            guard++
            if (s.phase == Phase.PLANNING) {
                val candidates = candidates(s)
                val batch = List(chooser.nextInt(0, 4)) { candidates[chooser.nextInt(candidates.size)] }
                val r = GameEngine.commit(s, batch)
                assertIs<GameStep.Ok>(r)
                val before = s.epoch
                s = r.state
                if (s.phase == Phase.PLANNING) {
                    assertEquals(before + 1, s.epoch)
                    done++
                    assertInvariants(s)
                }
            } else {
                val options = Skirmish.legalCommands(s.mission!!.skirmish)
                val r = GameEngine.tacticalCommand(s, options[chooser.nextInt(options.size)])
                assertIs<GameStep.Ok>(r)
                s = r.state
            }
        }
        return s
    }

    private fun candidates(s: GameState): List<StrategicCommand> = buildList {
        for (slot in 0 until Content.SLOTS) for (t in FacilityType.entries) add(StrategicCommand.Build(slot, t))
        for (c in SoldierClass.entries) add(StrategicCommand.Recruit(c))
        for (r in Content.REGIONS) {
            for (sol in s.campus.roster) add(StrategicCommand.Dispatch(r.id, listOf(sol.id)))
            if (s.campus.roster.size >= 2) {
                add(StrategicCommand.Dispatch(r.id, s.campus.roster.take(2).map { it.id }))
            }
        }
    }

    private fun assertInvariants(s: GameState) {
        val c = s.campus
        assertEquals(Content.SLOTS, c.grid.size)
        assertTrue(c.stock.alloy >= 0 && c.stock.cells >= 0 && c.stock.biomass >= 0, "negative stock: ${c.stock}")
        assertEquals(c.roster.size, c.roster.map { it.id }.toSet().size, "soldier ids must be unique")
        for (sol in c.roster) {
            assertTrue(sol.hp in 0..sol.stats.maxHp, "${sol.id} hp ${sol.hp}")
            assertTrue(sol.injuryEpochs >= 0)
        }
        for (order in c.queue) assertTrue(order.remainingEpochs > 0, "finished order left in queue")
        assertTrue(s.mission == null || s.phase == Phase.TACTICAL)
    }
}
