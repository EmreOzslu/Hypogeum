package hypogeum.domain.tactics

import hypogeum.domain.core.Pos
import hypogeum.domain.core.RngState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SkirmishTest {
    private fun ok(r: StepResult): SkirmishState {
        assertIs<StepResult.Ok>(r, "expected Ok but was $r")
        return r.state
    }

    /** Elle kurulmuş küçük sahne: testler haritayı ve birimleri kontrol eder. */
    private fun scene(rows: List<String>, vararg units: Combatant) = SkirmishState(
        seed = 0,
        rng = RngState(1),
        map = TacticalMap.parse(rows),
        combatants = units.toList(),
        round = 1,
        active = Side.PLAYER,
        winner = null,
    )

    private fun c(id: String, side: Side, x: Int, y: Int, hp: Int = 3, ap: Int = 4, aim: Int = 70, range: Int = 6) =
        Combatant(id, side, Pos(x, y), hp = hp, aim = aim, damage = 2, range = range, ap = ap)

    @Test
    fun wall_blocks_line_of_sight() {
        val s = scene(
            listOf(".....", "..#..", "....."),
            c("p1", Side.PLAYER, 0, 1),
            c("a1", Side.ALIEN, 4, 1),
        )
        assertIs<StepResult.Rejected>(Skirmish.apply(s, SkirmishCommand.Shoot("p1", "a1")))
    }

    @Test
    fun clear_line_allows_shot_and_spends_ap() {
        val s = scene(listOf("....."), c("p1", Side.PLAYER, 0, 0), c("a1", Side.ALIEN, 3, 0))
        val after = ok(Skirmish.apply(s, SkirmishCommand.Shoot("p1", "a1")))
        assertEquals(4 - SHOT_AP_COST, after.byId("p1")!!.ap)
    }

    @Test
    fun shot_out_of_range_is_rejected() {
        val s = scene(listOf("........"), c("p1", Side.PLAYER, 0, 0, range = 2), c("a1", Side.ALIEN, 5, 0))
        assertIs<StepResult.Rejected>(Skirmish.apply(s, SkirmishCommand.Shoot("p1", "a1")))
    }

    @Test
    fun cover_lowers_hit_rate_over_many_rolls() {
        // Düz zemin: aim 60 -> %60. Yüksek siper: 60 - 40 = %20. Geniş örneklemde fark görünmeli.
        fun hits(row: String): Int = (1L..2_000L).count { seed ->
            val s = scene(listOf(row), c("p1", Side.PLAYER, 0, 0, aim = 60), c("a1", Side.ALIEN, 3, 0))
                .copy(rng = RngState(seed))
            val ev = (Skirmish.apply(s, SkirmishCommand.Shoot("p1", "a1")) as StepResult.Ok).events
            ev.filterIsInstance<SkirmishEvent.Shot>().single().hit
        }
        val flat = hits("....")
        val covered = hits("...h")
        assertTrue(flat in 1100..1300, "flat hit rate should be ~60%, got $flat/2000")
        assertTrue(covered in 300..500, "high-cover hit rate should be ~20%, got $covered/2000")
    }

    @Test
    fun move_respects_ap_and_terrain_cost() {
        // Moloz 2 AP. p1 4 AP ile (0,0)->(2,0) yolunda: iki floor = 2 AP, OK.
        val s = scene(listOf("....r"), c("p1", Side.PLAYER, 0, 0, ap = 4))
        val moved = ok(Skirmish.apply(s, SkirmishCommand.Move("p1", Pos(2, 0))))
        assertEquals(Pos(2, 0), moved.byId("p1")!!.pos)
        assertEquals(2, moved.byId("p1")!!.ap)
        // Moloza giriş 2 AP: 3 floor + moloz = 5 AP > 4, ulaşılamaz.
        assertIs<StepResult.Rejected>(Skirmish.apply(s, SkirmishCommand.Move("p1", Pos(4, 0))))
    }

    @Test
    fun cannot_move_through_units_or_walls() {
        val s = scene(listOf(".#..."), c("p1", Side.PLAYER, 0, 0), c("a1", Side.ALIEN, 2, 0))
        assertIs<StepResult.Rejected>(Skirmish.apply(s, SkirmishCommand.Move("p1", Pos(2, 0))))
    }

    @Test
    fun cannot_command_alien_or_inactive_side() {
        val s = scene(listOf("..."), c("p1", Side.PLAYER, 0, 0), c("a1", Side.ALIEN, 2, 0))
        assertIs<StepResult.Rejected>(Skirmish.apply(s, SkirmishCommand.Move("a1", Pos(1, 0))))
    }

    @Test
    fun killing_last_alien_wins_the_skirmish() {
        val s = scene(listOf("...."), c("p1", Side.PLAYER, 0, 0, aim = 100), c("a1", Side.ALIEN, 1, 0, hp = 2))
        val after = ok(Skirmish.apply(s, SkirmishCommand.Shoot("p1", "a1")))
        assertEquals(Side.PLAYER, after.winner)
        assertIs<StepResult.Rejected>(Skirmish.apply(after, SkirmishCommand.EndTurn))
    }

    @Test
    fun end_turn_runs_alien_phase_and_advances_round() {
        val start = Skirmish.newGame(seed = 5)
        val next = ok(Skirmish.apply(start, SkirmishCommand.EndTurn))
        assertEquals(2, next.round)
        assertEquals(Side.PLAYER, next.active)
        assertEquals(PLAYER_AP, next.living(Side.PLAYER).first().ap)
    }

    @Test
    fun time_limit_ends_the_game_for_aliens() {
        // Son turdayız: bir tur daha geçmek sınırı aşar ve uzaylılar kazanır.
        val s = Skirmish.newGame(seed = 9).copy(round = MAX_ROUNDS)
        val end = ok(Skirmish.apply(s, SkirmishCommand.EndTurn))
        assertEquals(Side.ALIEN, end.winner)
    }

    @Test
    fun same_seed_and_same_choices_give_identical_states() {
        val a = playRandomly(seed = 123L, choiceSeed = 77L)
        val b = playRandomly(seed = 123L, choiceSeed = 77L)
        assertEquals(a, b)
    }

    @Test
    fun random_play_always_terminates_and_keeps_invariants() {
        for (seed in 1L..200L) {
            val end = playRandomly(seed, choiceSeed = seed * 31)
            assertTrue(end.winner != null, "game $seed did not finish")
            assertInvariants(end)
        }
    }

    private fun playRandomly(seed: Long, choiceSeed: Long): SkirmishState {
        val chooser = Random(choiceSeed)
        var s = Skirmish.newGame(seed)
        var steps = 0
        while (s.winner == null && steps < 5_000) {
            val options = Skirmish.legalCommands(s)
            val pick = options[chooser.nextInt(options.size)]
            s = ok(Skirmish.apply(s, pick))
            assertInvariants(s)
            steps++
        }
        return s
    }

    private fun assertInvariants(s: SkirmishState) {
        assertEquals(s.combatants.size, s.combatants.map { it.id }.toSet().size, "ids must be unique")
        assertEquals(s.combatants.size, s.combatants.map { it.pos }.toSet().size, "tiles must not overlap")
        for (c in s.combatants) {
            assertTrue(s.map.inside(c.pos), "${c.id} outside map")
            assertTrue(!s.map.terrainAt(c.pos).blocksMove, "${c.id} stands in a wall")
            assertTrue(c.hp in 1..4, "${c.id} hp out of range: ${c.hp}")
            assertTrue(c.ap >= 0, "${c.id} negative AP")
        }
    }
}
