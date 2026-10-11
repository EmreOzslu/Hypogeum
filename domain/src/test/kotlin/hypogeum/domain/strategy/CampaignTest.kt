package hypogeum.domain.strategy

import hypogeum.domain.campus.FacilityType
import hypogeum.domain.content.Content
import hypogeum.domain.core.PerPlayer
import hypogeum.domain.core.PlayerId
import hypogeum.domain.core.peer
import hypogeum.domain.core.Rng
import hypogeum.domain.game.GameEngine
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.game.Phase
import hypogeum.domain.roster.SoldierClass
import hypogeum.domain.tactics.Skirmish
import hypogeum.domain.tactics.SkirmishCommand
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CampaignTest {
    private val none = emptyList<StrategicCommand>()

    private fun resolve(s: GameState, p1: List<StrategicCommand> = none, p2: List<StrategicCommand> = none): GameState {
        val r = GameEngine.resolveEpoch(s, PerPlayer(p1, p2))
        assertIs<GameStep.Ok>(r)
        return r.state
    }

    private fun events(s: GameState, p1: List<StrategicCommand> = none, p2: List<StrategicCommand> = none): List<GameEvent> {
        val r = GameEngine.resolveEpoch(s, PerPlayer(p1, p2))
        assertIs<GameStep.Ok>(r)
        return r.events
    }

    private fun rejections(s: GameState, p1: List<StrategicCommand> = none, p2: List<StrategicCommand> = none): List<String> =
        events(s, p1, p2).filterIsInstance<GameEvent.CommandRejected>().map { it.reason }

    @Test
    fun new_game_has_two_campuses_and_a_shared_world() {
        val s = GameEngine.newGame("m", seed = 3)
        assertEquals(2, s.campuses.p1.roster.size)
        assertEquals(2, s.campuses.p2.roster.size)
        assertEquals(Content.START_STOCK, s.campuses.p1.stock)
        assertEquals(Content.REGIONS.size, s.world.regions.size)
        assertEquals(Phase.PLANNING, s.phase)
        assertEquals(1, s.epoch)
    }

    @Test
    fun building_is_independent_per_campus() {
        val s = resolve(GameEngine.newGame("m", 3), p1 = listOf(StrategicCommand.Build(0, FacilityType.REACTOR)))
        assertEquals(Content.START_STOCK - Content.facility(FacilityType.REACTOR).cost, s.campuses.p1.stock)
        assertEquals(Content.START_STOCK, s.campuses.p2.stock)
    }

    @Test
    fun build_completes_after_two_epoch_ends_and_double_booking_is_rejected() {
        val s0 = GameEngine.newGame("m", 3)
        assertEquals(
            listOf("slot occupied"),
            rejections(s0, p1 = listOf(StrategicCommand.Build(0, FacilityType.REACTOR), StrategicCommand.Build(0, FacilityType.FOUNDRY))),
        )
        var s = resolve(s0, p1 = listOf(StrategicCommand.Build(0, FacilityType.REACTOR)))
        assertEquals(null, s.campuses.p1.grid[0])
        s = resolve(s)
        assertEquals(FacilityType.REACTOR, s.campuses.p1.grid[0]?.type)
    }

    @Test
    fun recruiting_needs_barracks_and_trains_over_two_epochs() {
        var s = GameEngine.newGame("m", 3)
        assertEquals(listOf("build a barracks first"), rejections(s, p1 = listOf(StrategicCommand.Recruit(SoldierClass.MEDIC))))
        s = resolve(s, p1 = listOf(StrategicCommand.Build(0, FacilityType.BARRACKS)))
        s = resolve(s)
        val before = s.campuses.p1.roster.size
        s = resolve(s, p1 = listOf(StrategicCommand.Recruit(SoldierClass.MEDIC)))
        assertEquals(before, s.campuses.p1.roster.size)
        s = resolve(s)
        assertEquals(before + 1, s.campuses.p1.roster.size)
        assertEquals(SoldierClass.MEDIC, s.campuses.p1.roster.last().cls)
    }

    @Test
    fun dispatch_checks_injury_unknown_region_and_double_booking() {
        val s0 = GameEngine.newGame("m", 3)
        val first = s0.campuses.p1.roster[0]
        val injured = s0.copy(campuses = s0.campuses.with(PlayerId.P1, s0.campuses.p1.copy(roster = listOf(first.copy(injuryEpochs = 2)) + s0.campuses.p1.roster.drop(1))))
        assertEquals(listOf("${first.id} is injured"), rejections(injured, p1 = listOf(StrategicCommand.Dispatch("dock", listOf(first.id)))))
        assertEquals(listOf("unknown region moon"), rejections(s0, p1 = listOf(StrategicCommand.Dispatch("moon", listOf(first.id)))))
        assertEquals(
            listOf("${first.id} is already on a mission"),
            rejections(
                s0,
                p1 = listOf(
                    StrategicCommand.Dispatch("dock", listOf(first.id)),
                    StrategicCommand.Dispatch("gate", listOf(first.id)),
                ),
            ),
        )
    }

    @Test
    fun dispatch_starts_tactics_owned_by_the_sender() {
        val s0 = GameEngine.newGame("m", 3)
        val ids = s0.campuses.p1.roster.map { it.id }
        val s = resolve(s0, p1 = listOf(StrategicCommand.Dispatch("dock", ids)))
        assertEquals(Phase.TACTICAL, s.phase)
        assertEquals(PlayerId.P1, s.missions.first().owner)
        assertEquals(2, s.missions.first().squad.size)
        // Sıra P2'de değil, P1'de: P2'nin taktik komutu reddedilmeli.
        val notMine = GameEngine.tacticalCommand(s, PlayerId.P2, SkirmishCommand.EndTurn)
        assertEquals(GameStep.Rejected("not your mission"), notMine)
        assertIs<GameStep.Ok>(GameEngine.tacticalCommand(s, PlayerId.P1, SkirmishCommand.EndTurn))
    }

    @Test
    fun contested_node_goes_to_exactly_one_player_and_only_the_winner_pays() {
        val s0 = GameEngine.newGame("m", 21)
        val claim = listOf(StrategicCommand.ClaimNode("dock-alloy"))
        val ev = events(s0, p1 = claim, p2 = claim)
        val claimed = ev.filterIsInstance<GameEvent.NodeClaimed>().single()
        val rejected = ev.filterIsInstance<GameEvent.CommandRejected>().single()
        assertEquals("node already claimed", rejected.reason)
        assertTrue(claimed.owner != rejected.actor)

        // Kazanan, seed'li coin flip ile belirlenir: P1 önce ise flip=true.
        val flip = Rng.nextBoolean(s0.rng).value
        assertEquals(if (flip) PlayerId.P1 else PlayerId.P2, claimed.owner)

        val after = GameEngine.resolveEpoch(s0, PerPlayer(claim, claim)).let { (it as GameStep.Ok).state }
        val loser = claimed.owner.peer()
        // Kazanan: -2 alloy ödedi, sonra düğüm verimi +1 aldı. Kaybeden: hiç ödemedi.
        assertEquals(Content.START_STOCK.alloy - Content.CLAIM_COST.alloy + 1, after.campuses[claimed.owner].stock.alloy)
        assertEquals(Content.START_STOCK.alloy, after.campuses[loser].stock.alloy)
    }

    @Test
    fun non_conflicting_claims_both_succeed() {
        val s = resolve(
            GameEngine.newGame("m", 21),
            p1 = listOf(StrategicCommand.ClaimNode("dock-alloy")),
            p2 = listOf(StrategicCommand.ClaimNode("gate-cells")),
        )
        assertEquals(PlayerId.P1, s.world.findNode("dock-alloy")!!.second.claimedBy)
        assertEquals(PlayerId.P2, s.world.findNode("gate-cells")!!.second.claimedBy)
    }

    @Test
    fun threat_builds_to_a_warning_and_the_arrival_takes_the_claims_back() {
        // dock tehdidi 1'den başlar, her epoch sonunda +1. Epoch 2 sonunda 3'e (MAX) ulaşır
        // ve epoch 3 için uyarı üretir. Epoch 3 sonunda saldırı gelir: düğümler sahipsizleşir.
        var s = resolve(GameEngine.newGame("m", 5), p1 = listOf(StrategicCommand.ClaimNode("dock-alloy")))
        assertEquals(PlayerId.P1, s.world.findNode("dock-alloy")!!.second.claimedBy)
        assertEquals(2, s.world.region("dock")!!.threat)

        val endOfEpoch2 = events(s)
        assertEquals(listOf(GameEvent.IncursionWarning("dock", 3)), endOfEpoch2.filterIsInstance<GameEvent.IncursionWarning>())
        s = resolve(s)
        assertTrue(s.world.incursions.any { it.regionId == "dock" && it.arrivalEpoch == 3 })

        val endOfEpoch3 = events(s)
        assertEquals(listOf(GameEvent.IncursionArrived("dock")), endOfEpoch3.filterIsInstance<GameEvent.IncursionArrived>())
        s = resolve(s)
        assertEquals(null, s.world.findNode("dock-alloy")!!.second.claimedBy)
        // Saldırı tehdidi 1'e indirir; aynı epoch'un büyüme tiki +1 ekler.
        assertEquals(Content.MIN_THREAT + 1, s.world.region("dock")!!.threat)
        assertTrue(s.world.incursions.none { it.regionId == "dock" })
    }

    @Test
    fun same_batches_give_identical_states() {
        val batches = PerPlayer(
            listOf(StrategicCommand.Build(0, FacilityType.BARRACKS), StrategicCommand.ClaimNode("gate-bio")),
            listOf(StrategicCommand.Recruit(SoldierClass.HEAVY), StrategicCommand.ClaimNode("gate-bio")),
        )
        val a = GameEngine.resolveEpoch(GameEngine.newGame("m", 8), batches)
        val b = GameEngine.resolveEpoch(GameEngine.newGame("m", 8), batches)
        assertEquals(a, b)
    }

    @Test
    fun random_two_player_campaigns_keep_invariants() {
        for (seed in 1L..30L) {
            assertInvariants(playCampaign(seed, orderSeed = seed * 17, epochs = 10))
        }
    }

    private fun playCampaign(seed: Long, orderSeed: Long, epochs: Int): GameState {
        val chooser = Random(orderSeed)
        var s = GameEngine.newGame("m-$seed", seed)
        var done = 0
        var guard = 0
        while (done < epochs && guard < 4_000) {
            guard++
            if (s.phase == Phase.PLANNING) {
                val batches = PerPlayer(randomBatch(s, chooser), randomBatch(s, chooser))
                val before = s.epoch
                val r = GameEngine.resolveEpoch(s, batches)
                assertIs<GameStep.Ok>(r)
                s = r.state
                if (s.phase == Phase.PLANNING) {
                    assertEquals(before + 1, s.epoch)
                    done++
                    assertInvariants(s)
                }
            } else {
                val owner = s.missions.first().owner
                val options = Skirmish.legalCommands(s.missions.first().skirmish)
                val r = GameEngine.tacticalCommand(s, owner, options[chooser.nextInt(options.size)])
                assertIs<GameStep.Ok>(r)
                s = r.state
            }
        }
        return s
    }

    private fun randomBatch(s: GameState, rnd: Random): List<StrategicCommand> {
        val candidates = buildList {
            for (slot in 0 until Content.SLOTS) for (t in FacilityType.entries) add(StrategicCommand.Build(slot, t))
            for (c in SoldierClass.entries) add(StrategicCommand.Recruit(c))
            for (r in s.world.regions) add(StrategicCommand.Dispatch(r.id, listOf(s.campuses.p1.roster.firstOrNull()?.id ?: "none")))
            for (r in s.world.regions) for (n in r.nodes) add(StrategicCommand.ClaimNode(n.id))
        }
        return List(rnd.nextInt(0, 3)) { candidates[rnd.nextInt(candidates.size)] }
    }

    private fun assertInvariants(s: GameState) {
        for (seat in PlayerId.entries) {
            val c = s.campuses[seat]
            assertEquals(Content.SLOTS, c.grid.size)
            assertTrue(c.stock.alloy >= 0 && c.stock.cells >= 0 && c.stock.biomass >= 0, "negative stock: ${c.stock}")
            assertEquals(c.roster.size, c.roster.map { it.id }.toSet().size, "soldier ids must be unique")
            for (sol in c.roster) assertTrue(sol.hp in 0..sol.stats.maxHp, "${sol.id} hp ${sol.hp}")
            for (order in c.queue) assertTrue(order.remainingEpochs > 0, "finished order left in queue")
        }
        assertTrue((s.phase == Phase.TACTICAL) == s.missions.isNotEmpty())
        for (r in s.world.regions) assertTrue(r.threat in Content.MIN_THREAT..Content.MAX_THREAT)
    }
}
