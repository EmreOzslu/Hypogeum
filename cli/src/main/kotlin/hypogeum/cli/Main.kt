package hypogeum.cli

import hypogeum.domain.core.Pos
import hypogeum.domain.tactics.Combatant
import hypogeum.domain.tactics.SkirmishCommand
import hypogeum.domain.tactics.SkirmishEvent
import hypogeum.domain.tactics.Side
import hypogeum.domain.tactics.SkirmishState
import hypogeum.domain.tactics.Skirmish
import hypogeum.domain.tactics.StepResult

/** Birim etiketi: oyuncular '1'..'3', uzaylılar 'A'..'C' (id: p1 / a1 biçimi). */
fun glyphOf(c: Combatant): Char = when (c.side) {
    Side.PLAYER -> c.id[1]
    Side.ALIEN -> 'A' + (c.id[1] - '1')
}

fun render(s: SkirmishState): String {
    val sb = StringBuilder()
    sb.append("   ").append((0 until s.map.width).joinToString(" ") { it.toString() }).append('\n')
    for (y in 0 until s.map.height) {
        sb.append("%2d ".format(y))
        for (x in 0 until s.map.width) {
            val p = Pos(x, y)
            val unit = s.combatants.firstOrNull { it.pos == p }
            sb.append(if (unit != null) glyphOf(unit) else s.map.terrainAt(p).glyph)
            if (x < s.map.width - 1) sb.append(' ')
        }
        sb.append('\n')
    }
    sb.append("\nLegend: 1-3 squad  A-C uzaylı  # duvar  l/h siper  r moloz  . zemin\n")
    return sb.toString()
}

fun renderUnits(s: SkirmishState): String = s.combatants.joinToString("\n") { c ->
    val tag = if (c.side == Side.PLAYER) "squad" else "alien"
    "  %-3s %-6s (%d,%d) hp=%d ap=%d".format(c.id, tag, c.pos.x, c.pos.y, c.hp, c.ap)
}

fun describe(e: SkirmishEvent): String = when (e) {
    is SkirmishEvent.Moved -> "${e.unitId} -> (${e.to.x},${e.to.y})"
    is SkirmishEvent.Shot ->
        if (e.hit) "${e.attackerId} ${e.targetId}'i vurdu (-${e.damage})" else "${e.attackerId} ıskaladı (${e.targetId})"
    is SkirmishEvent.Died -> "${e.unitId} öldü"
    is SkirmishEvent.TurnEnded -> "Tur ${e.round} bitti. Uzaylılar hareket ediyor."
    is SkirmishEvent.Victory -> "OYUN BİTTİ: ${if (e.side == Side.PLAYER) "Squad kazandı" else "Uzaylılar kazandı"} (${e.reason})"
}

/** Metin komutu -> domain komutu. Geçersiz metin null döner. */
fun parse(line: String): Command? {
    val parts = line.trim().split(Regex("\\s+"))
    return when (parts.firstOrNull()?.lowercase()) {
        "move", "m" -> {
            if (parts.size != 4) return null
            val x = parts[2].toIntOrNull() ?: return null
            val y = parts[3].toIntOrNull() ?: return null
            Command.Game(SkirmishCommand.Move(parts[1], Pos(x, y)))
        }
        "shoot", "s" -> if (parts.size == 3) Command.Game(SkirmishCommand.Shoot(parts[1], parts[2])) else null
        "end", "e" -> Command.Game(SkirmishCommand.EndTurn)
        "help", "h", "?" -> Command.Help
        "status", "st" -> Command.Status
        "quit", "q" -> Command.Quit
        else -> null
    }
}

sealed interface Command {
    data class Game(val cmd: SkirmishCommand) : Command
    data object Help : Command
    data object Status : Command
    data object Quit : Command
}

private const val HELP = """Komutlar:
  move <id> <x> <y>   birimi (x,y) karesine taşı  (örn: move p1 3 4)
  shoot <id> <hedef>  ateş et                    (örn: shoot p1 a2)
  end                 turu bitir, uzaylılar oynar
  status              haritayı ve birimleri göster
  help                bu yardım
  quit                çık"""

fun main(args: Array<String>) {
    val seed = args.firstOrNull()?.toLongOrNull() ?: 1L
    var state = Skirmish.newGame(seed)
    println("HYPOGEUM — Taktik Skirmish (seed=$seed)")
    println(HELP)
    println()
    println(render(state))
    while (state.winner == null) {
        print("[tur ${state.round}] > ")
        val line = readlnOrNull() ?: break
        when (val cmd = parse(line)) {
            null -> if (line.isNotBlank()) println("Anlaşılmadı. 'help' yazabilirsin.")
            Command.Help -> println(HELP)
            Command.Status -> println(render(state) + renderUnits(state))
            Command.Quit -> break
            is Command.Game -> when (val r = Skirmish.apply(state, cmd.cmd)) {
                is StepResult.Rejected -> println("Olmadı: ${r.reason}")
                is StepResult.Ok -> {
                    state = r.state
                    r.events.forEach { println("  " + describe(it)) }
                    if (cmd.cmd is SkirmishCommand.EndTurn && state.winner == null) {
                        println(render(state))
                    }
                }
            }
        }
    }
    println(render(state))
    println(renderUnits(state))
    println(state.winner?.let { "Sonuç: ${if (it == Side.PLAYER) "Kazandın" else "Kaybettin"}" } ?: "Oyun yarıda kesildi.")
}
