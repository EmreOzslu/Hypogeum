package hypogeum.domain.strategy

import hypogeum.domain.campus.Facility
import hypogeum.domain.campus.FacilityType
import hypogeum.domain.content.Content
import hypogeum.domain.core.PerPlayer
import hypogeum.domain.core.PlayerId
import hypogeum.domain.game.GameEngine
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.game.MissionKind
import hypogeum.domain.game.Phase
import hypogeum.domain.tactics.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefenseTest {
    private val none = emptyList<StrategicCommand>()

    private fun resolve(s: GameState, p1: List<StrategicCommand> = none): GameStep.Ok =
        assertIs<GameStep.Ok>(GameEngine.resolveEpoch(s, PerPlayer(p1, none)))

    /** P1 gate-cells'i talep eder; epoch 2'de gate'e saldırı iner. Dönüş: saldırının indiği epoch'un başı. */
    private fun gateUnderThreat(): GameState {
        val afterClaim = resolve(GameEngine.newGame("m", 12), p1 = listOf(StrategicCommand.ClaimNode("gate-cells"))).state
        // gate tehdidi 2'den başlar: epoch 1 sonunda 3 (MAX), epoch 2 için uyarı.
        assertTrue(afterClaim.world.incursions.any { it.regionId == "gate" && it.arrivalEpoch == 2 })
        return afterClaim
    }

    @Test
    fun an_unclaimed_region_is_hit_without_a_fight_and_its_threat_resets() {
        val s = resolve(GameEngine.newGame("m", 12)).state
        val landing = resolve(s)
        assertTrue(landing.events.any { it is GameEvent.IncursionArrived && it.regionId == "gate" })
        assertEquals(Phase.PLANNING, landing.state.phase)
        assertTrue(landing.state.missions.isEmpty())
        // Tehdit 1'e iner, aynı epoch'un büyüme tiki +1 ekler.
        assertEquals(Content.MIN_THREAT + 1, landing.state.world.region("gate")!!.threat)
    }

    @Test
    fun a_claimed_region_fights_with_its_owners_ready_soldiers() {
        val s = gateUnderThreat()
        val state = resolve(s).state
        assertEquals(Phase.TACTICAL, state.phase)
        val m = state.missions.single()
        assertEquals(MissionKind.DEFENSE, m.kind)
        assertEquals(listOf(PlayerId.P1), m.participants)
        assertEquals(s.campuses.p1.roster.size, m.squads.size)
    }

    @Test
    fun injured_or_busy_soldiers_do_not_defend() {
        val base = gateUnderThreat()
        val roster = base.campuses.p1.roster
        val injured = base.copy(campuses = base.campuses.with(PlayerId.P1, base.campuses.p1.copy(roster = roster.map { it.copy(injuryEpochs = 2) })))
        val events = GameEngine.resolveEpoch(injured, PerPlayer(none, none)) as GameStep.Ok
        // Hazır asker yok: savunma kendiliğinden kaybedilir, görev kurulmaz.
        assertTrue(events.state.missions.isEmpty())
        assertTrue(events.events.any { it is GameEvent.DefenseFailed })
        assertEquals(null, events.state.world.findNode("gate-cells")!!.second.claimedBy)
    }

    @Test
    fun losing_a_defense_drops_the_claim_and_destroys_a_facility() {
        val base = gateUnderThreat()
        val grid = List(Content.SLOTS) { i -> if (i == 0) Facility(FacilityType.REACTOR) else null }
        val withFacility = base.copy(campuses = base.campuses.with(PlayerId.P1, base.campuses.p1.copy(grid = grid)))
        val m = resolve(withFacility).state.missions.single()
        val lostMission = m.copy(skirmish = m.skirmish.copy(winner = Side.ALIEN))
        val state = resolve(withFacility).state.copy(missions = listOf(lostMission))

        val r = Strategy.finishMission(state)
        assertEquals(null, r.state.world.findNode("gate-cells")!!.second.claimedBy)
        assertTrue(r.events.any { it is GameEvent.DefenseFailed && it.owner == PlayerId.P1 })
        assertEquals(listOf(GameEvent.FacilityDestroyed(PlayerId.P1, 0, FacilityType.REACTOR)), r.events.filterIsInstance<GameEvent.FacilityDestroyed>())
        assertEquals(null, r.state.campuses.p1.grid[0])
    }

    @Test
    fun winning_a_defense_keeps_the_claim_and_lowers_the_threat() {
        val state = resolve(gateUnderThreat()).state
        val won = state.copy(missions = listOf(state.missions.single().let { it.copy(skirmish = it.skirmish.copy(winner = Side.PLAYER)) }))
        val r = Strategy.finishMission(won)
        assertEquals(PlayerId.P1, r.state.world.findNode("gate-cells")!!.second.claimedBy)
        assertTrue(r.events.none { it is GameEvent.DefenseFailed })
        // Tehdit saldırı anında 3'tü; zafer -1 verir, epoch sonu tiki bu değeri değiştirmez.
        assertEquals(2, r.state.world.region("gate")!!.threat)
    }
}
