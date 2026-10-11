package hypogeum.domain.strategy

import hypogeum.domain.campus.Facility
import hypogeum.domain.campus.FacilityType
import hypogeum.domain.campus.Resources
import hypogeum.domain.content.Content
import hypogeum.domain.core.PerPlayer
import hypogeum.domain.core.PlayerId
import hypogeum.domain.game.GameEngine
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.game.Phase
import hypogeum.domain.tactics.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EconomyTest {
    private val none = emptyList<StrategicCommand>()

    private fun resolve(s: GameState, p1: List<StrategicCommand> = none, p2: List<StrategicCommand> = none): GameStep.Ok {
        val r = GameEngine.resolveEpoch(s, PerPlayer(p1, p2))
        return assertIs<GameStep.Ok>(r)
    }

    private fun rejections(s: GameState, p1: List<StrategicCommand> = none, p2: List<StrategicCommand> = none): List<String> =
        resolve(s, p1, p2).events.filterIsInstance<GameEvent.CommandRejected>().map { it.reason }

    /** Röle kurulmuş (iki epoch) bir P1 kampüsü. */
    private fun withRelay(): GameState {
        val s0 = GameEngine.newGame("m", 9)
        return resolve(resolve(s0, p1 = listOf(StrategicCommand.Build(0, FacilityType.RELAY))).state).state
    }

    @Test
    fun shipping_requires_a_relay() {
        val s = GameEngine.newGame("m", 9)
        assertEquals(
            listOf("build a relay to ship"),
            rejections(s, p1 = listOf(StrategicCommand.SendShipment(Resources(alloy = 1), emptyList()))),
        )
    }

    @Test
    fun shipment_loses_twenty_percent_in_transit_and_arrives_after_the_transit_time() {
        val s = withRelay()
        val soldier = s.campuses.p1.roster.first()
        val before1 = s.campuses.p1.stock.alloy
        val sent = resolve(s, p1 = listOf(StrategicCommand.SendShipment(Resources(alloy = 4), listOf(soldier.id))))
        val ev = sent.events.filterIsInstance<GameEvent.ShipmentSent>().single()
        // %20 kayıp, yukarı yuvarlanır: 4 -> ceil(0.8)=1 kayıp, 3 teslim.
        assertEquals(Resources(alloy = 3), ev.delivered)
        assertEquals(before1 - 4 + Content.facility(FacilityType.RELAY).output.alloy, sent.state.campuses.p1.stock.alloy)
        assertEquals(null, sent.state.campuses.p1.soldier(soldier.id))
        assertEquals(1, sent.state.shipments.size)
        val arrivalEpoch = sent.state.shipments.single().arrivalEpoch
        assertEquals(s.epoch + Content.TRANSIT_EPOCHS, arrivalEpoch)

        // Bekleme epoch'ları: henüz varmaz.
        var cur = sent.state
        val p2Before = cur.campuses.p2.stock.alloy
        var arrivedAt: Int? = null
        var guard = 0
        while (cur.shipments.isNotEmpty() && guard++ < 10) {
            val before = cur.epoch
            val step = resolve(cur)
            if (step.events.any { it is GameEvent.ShipmentArrived }) arrivedAt = before
            cur = step.state
        }
        // Varış, arrivalEpoch'ta epoch sonunda olur.
        assertEquals(arrivalEpoch, arrivedAt)
        assertEquals(p2Before + 3, cur.campuses.p2.stock.alloy)
        assertTrue(cur.shipments.isEmpty())
        assertTrue(cur.campuses.p2.roster.any { it.id == soldier.id })
    }

    @Test
    fun shipment_capacity_is_shared_and_extra_shipments_are_rejected() {
        val s = withRelay()
        val tiny = listOf(StrategicCommand.SendShipment(Resources(alloy = 1), emptyList()))
        assertEquals(listOf("transit capacity full"), rejections(s, p1 = tiny + tiny + tiny))
    }

    @Test
    fun research_needs_a_lab_runs_one_at_a_time_and_unlocks_its_effect() {
        var s = GameEngine.newGame("m", 4)
        assertEquals(listOf("build a lab first"), rejections(s, p1 = listOf(StrategicCommand.StartResearch("ballistics"))))
        s = resolve(resolve(s, p1 = listOf(StrategicCommand.Build(0, FacilityType.LAB))).state).state
        s = resolve(s, p1 = listOf(StrategicCommand.StartResearch("ballistics"))).state
        assertEquals(listOf("research already running"), rejections(s, p1 = listOf(StrategicCommand.StartResearch("field_medicine"))))
        while (s.campuses.p1.research.completed.isEmpty()) {
            s = resolve(s).state
        }
        assertEquals(listOf("ballistics"), s.campuses.p1.research.completed)
        assertEquals(null, s.campuses.p1.research.active)

        // Balistik: sevk edilen askerin nişanı +1 olur.
        val soldier = s.campuses.p1.roster.first()
        val after = resolve(s, p1 = listOf(StrategicCommand.Dispatch("dock", listOf(soldier.id)))).state
        assertEquals(soldier.stats.aim + Content.BALLISTICS_AIM_BONUS, after.missions.first().skirmish.combatants.first { it.id == "p1" }.aim)
    }

    @Test
    fun infirmary_heals_injured_soldiers_faster_than_the_base_rate() {
        val base = GameEngine.newGame("m", 4)
        val injured = base.campuses.p1.roster.first().copy(injuryEpochs = 2, hp = 1)
        val roster = listOf(injured) + base.campuses.p1.roster.drop(1)
        val withoutInfirmary = base.copy(campuses = base.campuses.with(PlayerId.P1, base.campuses.p1.copy(roster = roster)))
        val a = resolve(withoutInfirmary).state.campuses.p1.soldier(injured.id)!!
        assertEquals(1, a.injuryEpochs)

        val grid = List(Content.SLOTS) { i -> if (i == 0) Facility(FacilityType.INFIRMARY) else null }
        val withInfirmary = base.copy(campuses = base.campuses.with(PlayerId.P1, base.campuses.p1.copy(grid = grid, roster = roster)))
        val b = resolve(withInfirmary).state.campuses.p1.soldier(injured.id)!!
        assertEquals(0, b.injuryEpochs)
        assertEquals(b.stats.maxHp, b.hp)
    }

    @Test
    fun a_campus_without_relay_is_isolated_and_its_nodes_yield_half() {
        val base = GameEngine.newGame("m", 4)
        val owned = base.world.copy(
            regions = base.world.regions.map { r ->
                r.copy(nodes = r.nodes.map { n -> if (n.id == "gate-cells") n.copy(yieldPerEpoch = 4, claimedBy = PlayerId.P1) else n })
            },
        )
        val isolated = base.copy(world = owned)
        val cellsIsolated = resolve(isolated).state.campuses.p1.stock.cells
        assertEquals(Content.START_STOCK.cells + 2, cellsIsolated)

        val grid = List(Content.SLOTS) { i -> if (i == 0) Facility(FacilityType.RELAY) else null }
        val connected = isolated.copy(campuses = isolated.campuses.with(PlayerId.P1, isolated.campuses.p1.copy(grid = grid)))
        assertEquals(Content.START_STOCK.cells + 4, resolve(connected).state.campuses.p1.stock.cells)
    }

    @Test
    fun resolving_with_no_orders_keeps_the_phase_planning() {
        val s = resolve(GameEngine.newGame("m", 4)).state
        assertEquals(Phase.PLANNING, s.phase)
    }
}
