package hypogeum.domain.tactics

import hypogeum.domain.core.Pos
import hypogeum.domain.core.Rng
import hypogeum.domain.core.RngState
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

const val PLAYER_AP = 4
const val ALIEN_AP = 4
const val SHOT_AP_COST = 2
const val MAX_ROUNDS = 20
private const val MIN_HIT = 5
private const val MAX_HIT = 95

@Serializable
enum class Side { PLAYER, ALIEN }

/** Savaşçı: oyuncu askeri veya uzaylı. Ölen birim listeden çıkarılır. */
@Serializable
data class Combatant(
    val id: String,
    val side: Side,
    val pos: Pos,
    val hp: Int,
    val aim: Int,
    val damage: Int,
    val range: Int,
    val ap: Int,
)

@Serializable
data class SkirmishState(
    val seed: Long,
    val rng: RngState,
    val map: TacticalMap,
    val combatants: List<Combatant>,
    val round: Int,
    val active: Side,
    val winner: Side?,
) {
    fun living(side: Side): List<Combatant> = combatants.filter { it.side == side }

    fun byId(id: String): Combatant? = combatants.firstOrNull { it.id == id }

    fun occupied(p: Pos): Boolean = combatants.any { it.pos == p }

    fun replace(c: Combatant): SkirmishState =
        copy(combatants = combatants.map { if (it.id == c.id) c else it })

    fun remove(id: String): SkirmishState = copy(combatants = combatants.filterNot { it.id == id })

    fun withAp(side: Side, amount: Int): SkirmishState =
        copy(combatants = combatants.map { if (it.side == side) it.copy(ap = amount) else it })
}

@Serializable
sealed interface SkirmishCommand {
    @Serializable data class Move(val unitId: String, val to: Pos) : SkirmishCommand
    @Serializable data class Shoot(val unitId: String, val targetId: String) : SkirmishCommand
    @Serializable data object EndTurn : SkirmishCommand
}

sealed interface SkirmishEvent {
    data class Moved(val unitId: String, val to: Pos) : SkirmishEvent
    data class Shot(val attackerId: String, val targetId: String, val hit: Boolean, val damage: Int) : SkirmishEvent
    data class Died(val unitId: String) : SkirmishEvent
    data class TurnEnded(val round: Int) : SkirmishEvent
    data class Victory(val side: Side, val reason: String) : SkirmishEvent
}

sealed interface StepResult {
    data class Ok(val state: SkirmishState, val events: List<SkirmishEvent>) : StepResult
    data class Rejected(val reason: String) : StepResult
}

object Skirmish {
    /** Harita: '.' zemin, '#' duvar, 'l' alçak siper, 'h' yüksek siper, 'r' moloz (2 AP). */
    private val MAP_ROWS = listOf(
        "......#.",
        "..h.#...",
        ".l...l..",
        "...#....",
        "r...#...",
        "..h...l.",
        ".l...#..",
        "........",
    )

    fun newGame(seed: Long): SkirmishState {
        val combatants = listOf(
            Combatant("p1", Side.PLAYER, Pos(0, 7), hp = 4, aim = 70, damage = 2, range = 6, ap = PLAYER_AP),
            Combatant("p2", Side.PLAYER, Pos(1, 6), hp = 4, aim = 70, damage = 2, range = 6, ap = PLAYER_AP),
            Combatant("p3", Side.PLAYER, Pos(2, 7), hp = 4, aim = 70, damage = 2, range = 6, ap = PLAYER_AP),
            Combatant("a1", Side.ALIEN, Pos(7, 0), hp = 3, aim = 60, damage = 1, range = 4, ap = 0),
            Combatant("a2", Side.ALIEN, Pos(5, 0), hp = 3, aim = 60, damage = 1, range = 4, ap = 0),
            Combatant("a3", Side.ALIEN, Pos(7, 2), hp = 3, aim = 60, damage = 1, range = 4, ap = 0),
        )
        return SkirmishState(
            seed = seed,
            rng = RngState(seed),
            map = TacticalMap.parse(MAP_ROWS),
            combatants = combatants,
            round = 1,
            active = Side.PLAYER,
            winner = null,
        )
    }

    fun apply(state: SkirmishState, cmd: SkirmishCommand): StepResult {
        if (state.winner != null) return StepResult.Rejected("game is over")
        return when (cmd) {
            is SkirmishCommand.Move -> move(state, cmd)
            is SkirmishCommand.Shoot -> shoot(state, cmd)
            SkirmishCommand.EndTurn -> endTurn(state)
        }
    }

    /** Oyuncunun o an yapabileceği tüm geçerli komutlar (CLI yardımı ve fuzz testleri için). */
    fun legalCommands(state: SkirmishState): List<SkirmishCommand> {
        if (state.winner != null) return emptyList()
        val out = mutableListOf<SkirmishCommand>()
        for (c in state.living(state.active)) {
            for (i in 0 until state.map.width * state.map.height) {
                val cmd = SkirmishCommand.Move(c.id, state.map.posOf(i))
                if (apply(state, cmd) is StepResult.Ok) out += cmd
            }
            for (t in state.living(opposite(state.active))) {
                val cmd = SkirmishCommand.Shoot(c.id, t.id)
                if (apply(state, cmd) is StepResult.Ok) out += cmd
            }
        }
        out += SkirmishCommand.EndTurn
        return out
    }

    private fun move(s: SkirmishState, cmd: SkirmishCommand.Move): StepResult {
        val u = ownUnit(s, cmd.unitId) ?: return StepResult.Rejected("not your unit: ${cmd.unitId}")
        if (!s.map.inside(cmd.to)) return StepResult.Rejected("outside map")
        if (cmd.to == u.pos) return StepResult.Rejected("already there")
        if (s.occupied(cmd.to)) return StepResult.Rejected("tile occupied")
        val blocked = s.combatants.filter { it.id != u.id }.map { it.pos }.toSet()
        val cost = Pathing.reachable(s.map, u.pos, blocked, u.ap)[cmd.to]
            ?: return StepResult.Rejected("unreachable with ${u.ap} AP")
        val moved = s.replace(u.copy(pos = cmd.to, ap = u.ap - cost))
        return StepResult.Ok(moved, listOf(SkirmishEvent.Moved(u.id, cmd.to)))
    }

    private fun shoot(s: SkirmishState, cmd: SkirmishCommand.Shoot): StepResult {
        val a = ownUnit(s, cmd.unitId) ?: return StepResult.Rejected("not your unit: ${cmd.unitId}")
        val t = s.byId(cmd.targetId)?.takeIf { it.side != a.side } ?: return StepResult.Rejected("invalid target")
        if (a.ap < SHOT_AP_COST) return StepResult.Rejected("not enough AP to shoot")
        lineOfFire(s.map, a, t)?.let { return StepResult.Rejected(it) }
        val events = mutableListOf<SkirmishEvent>()
        val after = resolveShot(s.replace(a.copy(ap = a.ap - SHOT_AP_COST)), a, t, events)
        return StepResult.Ok(after, events)
    }

    private fun endTurn(s: SkirmishState): StepResult {
        if (s.active != Side.PLAYER) return StepResult.Rejected("not the player's phase")
        val events = mutableListOf<SkirmishEvent>(SkirmishEvent.TurnEnded(s.round))
        var st = s.copy(active = Side.ALIEN).withAp(Side.ALIEN, ALIEN_AP)
        for (id in s.living(Side.ALIEN).map { it.id }) {
            if (st.winner != null) break
            st = alienAct(st, id, events)
        }
        if (st.winner == null && s.round + 1 > MAX_ROUNDS) {
            events += SkirmishEvent.Victory(Side.ALIEN, "time limit reached")
            return StepResult.Ok(st.copy(winner = Side.ALIEN), events)
        }
        if (st.winner == null) {
            st = st.copy(round = st.round + 1, active = Side.PLAYER).withAp(Side.PLAYER, PLAYER_AP)
        }
        return StepResult.Ok(st, events)
    }

    /** Uzaylı tek karar turu: yakın hedefe doğru en iyi kareye yürü, mümkünse ateş et. */
    private fun alienAct(s0: SkirmishState, id: String, events: MutableList<SkirmishEvent>): SkirmishState {
        var s = s0
        val target = s.living(Side.PLAYER).minByOrNull { chebyshev(it.pos, s.byId(id)!!.pos) } ?: return s
        val me = s.byId(id) ?: return s
        if (lineOfFire(s.map, me, target) == null && me.ap >= SHOT_AP_COST) {
            return resolveShot(s.replace(me.copy(ap = me.ap - SHOT_AP_COST)), me, target, events)
        }
        val blocked = s.combatants.filter { it.id != id }.map { it.pos }.toSet()
        val reach = Pathing.reachable(s.map, me.pos, blocked, me.ap)
        val best = reach.entries.minWithOrNull(
            compareBy<Map.Entry<Pos, Int>>({ chebyshev(it.key, target.pos) }, { it.value }, { it.key.y }, { it.key.x }),
        )
        if (best != null && best.key != me.pos && chebyshev(best.key, target.pos) < chebyshev(me.pos, target.pos)) {
            val moved = me.copy(pos = best.key, ap = me.ap - best.value)
            s = s.replace(moved)
            events += SkirmishEvent.Moved(id, best.key)
            if (lineOfFire(s.map, moved, target) == null && moved.ap >= SHOT_AP_COST) {
                s = s.replace(moved.copy(ap = moved.ap - SHOT_AP_COST))
                s = resolveShot(s, moved, target, events)
            }
        }
        return s
    }

    private fun resolveShot(
        s: SkirmishState,
        a: Combatant,
        t: Combatant,
        events: MutableList<SkirmishEvent>,
    ): SkirmishState {
        val cover = s.map.terrainAt(t.pos).coverBonus
        val chance = (a.aim - cover).coerceIn(MIN_HIT, MAX_HIT)
        val roll = Rng.nextInt(s.rng, 100)
        var next = s.copy(rng = roll.next)
        val hit = roll.value < chance
        events += SkirmishEvent.Shot(a.id, t.id, hit, if (hit) a.damage else 0)
        if (!hit) return next
        val remaining = t.hp - a.damage
        if (remaining > 0) return next.replace(t.copy(hp = remaining))
        events += SkirmishEvent.Died(t.id)
        next = next.remove(t.id)
        return checkVictory(next, events)
    }

    private fun checkVictory(s: SkirmishState, events: MutableList<SkirmishEvent>): SkirmishState {
        val winner = when {
            s.living(Side.ALIEN).isEmpty() -> Side.PLAYER
            s.living(Side.PLAYER).isEmpty() -> Side.ALIEN
            else -> return s
        }
        events += SkirmishEvent.Victory(winner, if (winner == Side.PLAYER) "all aliens destroyed" else "squad wiped out")
        return s.copy(winner = winner)
    }

    /** null: ateş hattı açık. Aksi halde sebep. */
    private fun lineOfFire(map: TacticalMap, a: Combatant, t: Combatant): String? {
        if (chebyshev(a.pos, t.pos) > a.range) return "target out of range"
        for (p in bresenham(a.pos, t.pos)) {
            if (map.terrainAt(p).blocksSight) return "line of sight blocked"
        }
        return null
    }

    /** Uç noktalar hariç Bresenham hattı. Tamsayı aritmetiği: platform bağımsız. */
    private fun bresenham(from: Pos, to: Pos): List<Pos> {
        val out = mutableListOf<Pos>()
        var x = from.x
        var y = from.y
        val dx = abs(to.x - from.x)
        val dy = -abs(to.y - from.y)
        val sx = if (from.x < to.x) 1 else -1
        val sy = if (from.y < to.y) 1 else -1
        var err = dx + dy
        while (true) {
            if (Pos(x, y) == to) break
            if (Pos(x, y) != from) out += Pos(x, y)
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
        return out
    }

    private fun ownUnit(s: SkirmishState, id: String): Combatant? =
        s.byId(id)?.takeIf { it.side == s.active }

    private fun opposite(side: Side): Side = if (side == Side.PLAYER) Side.ALIEN else Side.PLAYER

    private fun chebyshev(a: Pos, b: Pos): Int = max(abs(a.x - b.x), abs(a.y - b.y))
}
