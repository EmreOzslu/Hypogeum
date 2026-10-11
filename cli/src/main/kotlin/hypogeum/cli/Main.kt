package hypogeum.cli

import hypogeum.domain.campus.FacilityType
import hypogeum.domain.campus.ProductionOrder
import hypogeum.domain.content.Content
import hypogeum.domain.core.Pos
import hypogeum.domain.game.GameEngine
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.game.Phase
import hypogeum.domain.roster.SoldierClass
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.Combatant
import hypogeum.domain.tactics.Side
import hypogeum.domain.tactics.SkirmishCommand
import hypogeum.domain.tactics.SkirmishEvent
import hypogeum.domain.tactics.SkirmishState

fun main(args: Array<String>) {
    val seed = args.firstOrNull()?.toLongOrNull() ?: 1L
    val draft = mutableListOf<StrategicCommand>()
    var state = GameEngine.newGame("cli-$seed", seed)
    println("HYPOGEUM — Kampüs (seed=$seed)")
    println(HELP)
    println(renderCampus(state))
    while (true) {
        when (state.phase) {
            Phase.PLANNING -> print("[epoch ${state.epoch} | taslak ${draft.size}] > ")
            Phase.TACTICAL -> print("[görev ${state.mission?.regionId} tur ${state.mission?.skirmish?.round}] > ")
        }
        val line = readlnOrNull() ?: break
        val trimmed = line.trim()
        if (trimmed == "quit" || trimmed == "q") break
        if (trimmed.isEmpty()) continue
        state = when (state.phase) {
            Phase.PLANNING -> planningLine(state, trimmed, draft)
            Phase.TACTICAL -> tacticalLine(state, trimmed)
        }
    }
    println("Çıkış. Epoch ${state.epoch}.")
}

private fun planningLine(state: GameState, line: String, draft: MutableList<StrategicCommand>): GameState {
    val parts = line.split(Regex("\\s+"))
    when (parts[0].lowercase()) {
        "help", "?" -> println(HELP)
        "status" -> println(renderCampus(state))
        "draft" -> println(if (draft.isEmpty()) "  (boş)" else draft.joinToString("\n") { "  " + describeCommand(it) })
        "clear" -> { draft.clear(); println("Taslak temizlendi.") }
        "build" -> {
            val slot = parts.getOrNull(1)?.toIntOrNull()
            val type = parts.getOrNull(2)?.let(::facilityOf)
            if (slot == null || type == null || parts.size != 3) {
                println("Kullanım: build <slot 0-${Content.SLOTS - 1}> <reactor|foundry|hydro|barracks>")
            } else draft += StrategicCommand.Build(slot, type)
        }
        "recruit" -> {
            val cls = parts.getOrNull(1)?.let(::classOf)
            if (cls == null || parts.size != 2) println("Kullanım: recruit <rifleman|heavy|medic>")
            else draft += StrategicCommand.Recruit(cls)
        }
        "dispatch" -> {
            if (parts.size < 3) println("Kullanım: dispatch <bölge> <asker-id>...   (bölgeler: ${Content.REGIONS.joinToString { it.id }})")
            else draft += StrategicCommand.Dispatch(parts[1], parts.drop(2))
        }
        "commit", "c" -> {
            val batch = draft.toList()
            draft.clear()
            return when (val r = GameEngine.commit(state, batch)) {
                is GameStep.Rejected -> { println("Olmadı: ${r.reason}"); state }
                is GameStep.Ok -> {
                    r.events.forEach { println("  " + describeStrategic(it)) }
                    if (r.state.phase == Phase.TACTICAL) println(renderSkirmish(r.state.mission!!.skirmish))
                    else println(renderCampus(r.state))
                    r.state
                }
            }
        }
        else -> println("Anlaşılmadı. 'help' yazabilirsin.")
    }
    return state
}

private fun tacticalLine(state: GameState, line: String): GameState {
    val cmd = parseTactical(line)
    if (cmd == null) {
        if (line == "help" || line == "?") println(HELP_TACTICAL) else println("Anlaşılmadı. 'help' yazabilirsin.")
        if (line == "status") println(renderSkirmish(state.mission!!.skirmish))
        return state
    }
    return when (val r = GameEngine.tacticalCommand(state, cmd)) {
        is GameStep.Rejected -> { println("Olmadı: ${r.reason}"); state }
        is GameStep.Ok -> {
            for (e in r.events) println("  " + describeStrategic(e))
            if (r.state.phase == Phase.PLANNING) {
                println(renderCampus(r.state))
            } else if (cmd is SkirmishCommand.EndTurn && r.state.mission?.skirmish?.winner == null) {
                println(renderSkirmish(r.state.mission!!.skirmish))
            }
            r.state
        }
    }
}

// --- metinden komut ---

private fun facilityOf(s: String): FacilityType? = when (s.lowercase()) {
    "reactor" -> FacilityType.REACTOR
    "foundry" -> FacilityType.FOUNDRY
    "hydro" -> FacilityType.HYDROPONICS
    "barracks" -> FacilityType.BARRACKS
    else -> null
}

private fun classOf(s: String): SoldierClass? = when (s.lowercase()) {
    "rifleman" -> SoldierClass.RIFLEMAN
    "heavy" -> SoldierClass.HEAVY
    "medic" -> SoldierClass.MEDIC
    else -> null
}

fun parseTactical(line: String): SkirmishCommand? {
    val parts = line.split(Regex("\\s+"))
    return when (parts[0].lowercase()) {
        "move", "m" -> {
            if (parts.size != 4) return null
            val x = parts[2].toIntOrNull() ?: return null
            val y = parts[3].toIntOrNull() ?: return null
            SkirmishCommand.Move(parts[1], Pos(x, y))
        }
        "shoot", "s" -> if (parts.size == 3) SkirmishCommand.Shoot(parts[1], parts[2]) else null
        "end", "e" -> SkirmishCommand.EndTurn
        else -> null
    }
}

// --- görüntüleme ---

private const val HELP = """Kampüs komutları:
  build <slot> <reactor|foundry|hydro|barracks>   inşaat sırasına ekle (slot 0-23)
  recruit <rifleman|heavy|medic>                  asker eğitimi (barracks gerekir)
  dispatch <bölge> <asker-id>...                  bölgeye sevk (örn: dispatch dock soldier-1)
  draft                                           taslağı göster
  clear                                           taslağı temizle
  commit                                          taslağı uygula, epoch'u bitir
  quit                                            çık"""

private const val HELP_TACTICAL = """Taktik komutlar:
  move <id> <x> <y>   shoot <id> <hedef>   end   status   quit"""

fun renderCampus(s: GameState): String {
    val c = s.campus
    val sb = StringBuilder()
    sb.append("Stok: alloy=${c.stock.alloy} cells=${c.stock.cells} biomass=${c.stock.biomass}\n")
    sb.append("Izgara (${Content.GRID_COLS}x${Content.GRID_ROWS}, R/F/H/B = tesis, * = inşaat, . = boş):\n")
    for (row in 0 until Content.GRID_ROWS) {
        sb.append("  ")
        for (col in 0 until Content.GRID_COLS) {
            val slot = row * Content.GRID_COLS + col
            val glyph = when (c.grid[slot]?.type) {
                FacilityType.REACTOR -> 'R'
                FacilityType.FOUNDRY -> 'F'
                FacilityType.HYDROPONICS -> 'H'
                FacilityType.BARRACKS -> 'B'
                null -> if (c.queuedBuildAt(slot)) '*' else '.'
            }
            sb.append("%2d:%c ".format(slot, glyph))
        }
        sb.append('\n')
    }
    if (c.queue.isNotEmpty()) {
        sb.append("Kuyruk:\n")
        c.queue.forEach { sb.append("  ${describeOrder(it)}\n") }
    }
    sb.append("Askerler:\n")
    c.roster.forEach { sol ->
        val status = if (sol.injuryEpochs > 0) " [yaralı ${sol.injuryEpochs}]" else ""
        sb.append("  %-10s %-8s %-6s hp=%d/%d xp=%d%s\n".format(sol.id, sol.name, sol.cls, sol.hp, sol.stats.maxHp, sol.xp, status))
    }
    if (c.roster.isEmpty()) sb.append("  (yok)\n")
    sb.append("Bölgeler: " + Content.REGIONS.joinToString { "${it.id} (${it.name}, tehdit ${it.threat})" } + "\n")
    return sb.toString()
}

fun glyphOf(c: Combatant): Char = when (c.side) {
    Side.PLAYER -> c.id[1]
    Side.ALIEN -> 'A' + (c.id[1] - '1')
}

fun renderSkirmish(s: SkirmishState): String {
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
    sb.append("Lejant: 1-3 squad  A-C uzaylı  # duvar  l/h siper  r moloz  . zemin\n")
    s.combatants.forEach { c ->
        val tag = if (c.side == Side.PLAYER) "squad" else "alien"
        sb.append("  %-3s %-6s (%d,%d) hp=%d ap=%d\n".format(c.id, tag, c.pos.x, c.pos.y, c.hp, c.ap))
    }
    return sb.toString()
}

fun describeCommand(c: StrategicCommand): String = when (c) {
    is StrategicCommand.Build -> "inşa et: slot ${c.slotIndex} (${c.type})"
    is StrategicCommand.Recruit -> "eğit: ${c.cls}"
    is StrategicCommand.Dispatch -> "sevk: ${c.regionId} <- ${c.soldierIds.joinToString()}"
}

fun describeOrder(o: ProductionOrder): String = when (o) {
    is ProductionOrder.Build -> "inşaat slot ${o.slotIndex} (${o.type}), ${o.remainingEpochs} epoch"
    is ProductionOrder.Train -> "eğitim ${o.cls}, ${o.remainingEpochs} epoch"
}

fun describeStrategic(e: GameEvent): String = when (e) {
    is GameEvent.CommandRejected -> "REDDEDİLDİ: ${e.reason}"
    is GameEvent.FacilityQueued -> "inşaat sıraya girdi: slot ${e.slot} (${e.type})"
    is GameEvent.FacilityBuilt -> "tesis tamamlandı: slot ${e.slot} (${e.type})"
    is GameEvent.RecruitQueued -> "eğitim başladı: ${e.cls}"
    is GameEvent.SoldierRecruited -> "yeni asker: ${e.soldierId} ${e.name}"
    is GameEvent.ResourcesProduced -> "kaynak: +alloy ${e.amount.alloy} +cells ${e.amount.cells} +biomass ${e.amount.biomass}"
    is GameEvent.SquadDispatched -> "${e.size} asker ${e.regionId} bölgesine sevk edildi"
    is GameEvent.Tactical -> describe(e.event)
    is GameEvent.SoldierKilled -> "${e.soldierId} öldü"
    is GameEvent.SoldierInjured -> "${e.soldierId} yaralandı"
    is GameEvent.MissionEnded -> if (e.won) "GÖREV BAŞARILI" else "GÖREV BAŞARISIZ"
    is GameEvent.EpochStarted -> "--- Epoch ${e.epoch} ---"
}

fun describe(e: SkirmishEvent): String = when (e) {
    is SkirmishEvent.Moved -> "${e.unitId} -> (${e.to.x},${e.to.y})"
    is SkirmishEvent.Shot ->
        if (e.hit) "${e.attackerId} ${e.targetId}'i vurdu (-${e.damage})" else "${e.attackerId} ıskaladı (${e.targetId})"
    is SkirmishEvent.Died -> "${e.unitId} öldü"
    is SkirmishEvent.TurnEnded -> "Tur ${e.round} bitti. Uzaylılar hareket ediyor."
    is SkirmishEvent.Victory -> "Savaş bitti: ${if (e.side == Side.PLAYER) "squad kazandı" else "uzaylılar kazandı"} (${e.reason})"
}
