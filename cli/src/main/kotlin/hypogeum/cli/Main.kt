package hypogeum.cli

import hypogeum.data.sync.HotseatMatch
import hypogeum.domain.campus.FacilityType
import hypogeum.domain.campus.ProductionOrder
import hypogeum.domain.content.Content
import hypogeum.domain.core.PlayerId
import hypogeum.domain.core.Pos
import hypogeum.domain.core.peer
import hypogeum.domain.game.GameEngine
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.game.MissionKind
import hypogeum.domain.game.Phase
import hypogeum.domain.roster.SoldierClass
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.Combatant
import hypogeum.domain.tactics.Side
import hypogeum.domain.tactics.SkirmishCommand
import hypogeum.domain.tactics.SkirmishEvent
import hypogeum.domain.tactics.SkirmishState

/**
 * Tek cihazda iki oyunculu (hotseat) oynanış. Koltuklar sırayla planlar; ikisi de commit edince epoch çözülür.
 * Taktik faz: görevin sahibi oynar.
 */
fun main(args: Array<String>) {
    val seed = args.firstOrNull()?.toLongOrNull() ?: 1L
    val match = HotseatMatch.start(GameEngine.newGame("hotseat-$seed", seed))
    val drafts = mapOf(PlayerId.P1 to mutableListOf<StrategicCommand>(), PlayerId.P2 to mutableListOf<StrategicCommand>())
    val committed = mutableSetOf<PlayerId>()
    var seat = PlayerId.P1
    println("HYPOGEUM — İki Koltuk (seed=$seed)")
    println(HELP)
    println(renderWorld(match.state(seat)))

    while (true) {
        val state = match.state(seat)
        if (state.phase == Phase.TACTICAL) {
            seat = state.missions.first().skirmish.activeOwner
            print("[görev ${state.missions.first().regionId} · $seat · tur ${state.missions.first().skirmish.round}] > ")
        } else {
            print("[$seat · epoch ${state.epoch} · taslak ${drafts.getValue(seat).size}] > ")
        }
        val line = readlnOrNull() ?: break
        val trimmed = line.trim()
        if (trimmed == "quit" || trimmed == "q") break
        if (trimmed.isEmpty()) continue

        if (state.phase == Phase.TACTICAL) {
            tacticalLine(match, seat, trimmed)
            continue
        }
        if (trimmed.lowercase().split(Regex("\\s+"))[0] !in setOf("commit", "c")) {
            planningLine(match, seat, trimmed, drafts.getValue(seat))
            continue
        }
        if (seat in committed) {
            println("Bu koltuk zaten commit etti.")
            continue
        }
        val events = match.commitPlanning(seat, drafts.getValue(seat).toList())
        drafts.getValue(seat).clear()
        committed += seat
        if (committed.size == 2) {
            // İki koltuk da commit etti: epoch çözüldü.
            committed.clear()
            events.forEach { println("  " + describe(it)) }
            val after = match.state(PlayerId.P1)
            if (after.phase == Phase.TACTICAL) {
                println(renderSkirmish(after.missions.first().skirmish))
            } else {
                println(renderWorld(after))
            }
            seat = PlayerId.P1
        } else {
            println("${seat} commit etti. Ekranı ${seat.peer()}'ye devret.")
            seat = seat.peer()
        }
    }
    println("Çıkış. Epoch ${match.state(PlayerId.P1).epoch}.")
}

/** Planlama komutları: taslağa ekler ya da bilgi basar. Commit burada ele alınmaz. */
private fun planningLine(match: HotseatMatch, seat: PlayerId, line: String, draft: MutableList<StrategicCommand>) {
    val parts = line.split(Regex("\\s+"))
    when (parts[0].lowercase()) {
        "help", "?" -> println(HELP)
        "status" -> println(renderCampus(match.state(seat), seat) + "\n" + renderWorld(match.state(seat)))
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
        "research" -> {
            if (parts.size != 2) println("Kullanım: research <${Content.TECHS.joinToString("|") { it.id }}>")
            else draft += StrategicCommand.StartResearch(parts[1])
        }
        "ship" -> {
            val a = parts.getOrNull(1)?.toIntOrNull()
            val c = parts.getOrNull(2)?.toIntOrNull()
            val b = parts.getOrNull(3)?.toIntOrNull()
            if (a == null || c == null || b == null || parts.size < 4) {
                println("Kullanım: ship <alloy> <cells> <biomass> [asker-id...]   (röle gerekir)")
            } else {
                draft += StrategicCommand.SendShipment(hypogeum.domain.campus.Resources(a, c, b), parts.drop(4))
            }
        }
        "claim" -> {
            if (parts.size != 2) println("Kullanım: claim <düğüm-id>   (düğümler: ${Content.REGIONS.flatMap { r -> r.nodes.map { it.id } }.joinToString()})")
            else draft += StrategicCommand.ClaimNode(parts[1])
        }
        else -> println("Anlaşılmadı. 'help' yazabilirsin.")
    }
}

private fun tacticalLine(match: HotseatMatch, owner: PlayerId, line: String) {
    val cmd = parseTactical(line)
    if (cmd == null) {
        when (line.lowercase()) {
            "help", "?" -> println(HELP_TACTICAL)
            "status" -> println(renderSkirmish(match.state(owner).missions.first().skirmish))
            else -> println("Anlaşılmadı. 'help' yazabilirsin.")
        }
        return
    }
    when (val r = match.tacticalCommand(owner, cmd)) {
        is GameStep.Rejected -> println("Olmadı: ${r.reason}")
        is GameStep.Ok -> {
            r.events.forEach { println("  " + describe(it)) }
            val after = match.state(owner)
            if (after.phase == Phase.PLANNING) println(renderWorld(after))
            else if (cmd is SkirmishCommand.EndTurn && after.missions.first().skirmish.winner == null) {
                println(renderSkirmish(after.missions.first().skirmish))
            }
        }
    }
}

private val HELP = """Komutlar (planlama):
  build <slot> <reactor|foundry|hydro|barracks>   inşaat sırasına ekle (slot 0-23)
  recruit <rifleman|heavy|medic>                  asker eğitimi (barracks gerekir)
  dispatch <bölge> <asker-id>...                  bölgeye sevk
  claim <düğüm>                                   kaynak düğümünü talep et (paylaşılan)
  draft | clear | status                          taslak / temizle / durum
  commit                                          taslağı kilitle; ikinci koltuk commit edince epoch çözülür
  quit                                            çık"""

private const val HELP_TACTICAL = """Taktik komutlar (görev sahibi):
  move <id> <x> <y>   shoot <id> <hedef>   end   status   quit"""

// --- metin ayrıştırma ---

private fun facilityOf(s: String): FacilityType? = when (s.lowercase()) {
    "reactor" -> FacilityType.REACTOR
    "foundry" -> FacilityType.FOUNDRY
    "hydro" -> FacilityType.HYDROPONICS
    "barracks" -> FacilityType.BARRACKS
    "lab" -> FacilityType.LAB
    "relay" -> FacilityType.RELAY
    "infirmary" -> FacilityType.INFIRMARY
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

fun renderWorld(s: GameState): String {
    val sb = StringBuilder("Dünya:\n")
    for (r in s.world.regions) {
        sb.append("  ${r.id} (${r.name}) tehdit=${r.threat}\n")
        for (n in r.nodes) {
            val owner = n.claimedBy?.toString() ?: "sahipsiz"
            sb.append("    düğüm ${n.id} [${n.kind} +${n.yieldPerEpoch}/epoch] — $owner\n")
        }
    }
    sb.append("Hat: ${s.world.transit.usedThisEpoch}/${s.world.transit.capacityPerEpoch} sevkiyat kapasitesi kullanıldı, ${s.shipments.size} sevkiyat yolda\n")
    for (inc in s.world.incursions) {
        sb.append("  UYARI: ${inc.regionId} bölgesine epoch ${inc.arrivalEpoch}'de saldırı\n")
    }
    return sb.toString()
}

fun renderCampus(s: GameState, seat: PlayerId): String {
    val c = s.campuses[seat]
    val sb = StringBuilder("Kampüs $seat${if (c.isolated) " [İZOLE: röle yok]" else ""}\n")
    val active = c.research.active
    sb.append("Araştırma: tamamlanan=${c.research.completed.ifEmpty { listOf("yok") }.joinToString()}" +
        (if (active != null) ", devam eden=${active.tech} (${active.remainingEpochs} epoch)" else "") + "\n")
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
                FacilityType.LAB -> 'L'
                FacilityType.RELAY -> 'T'
                FacilityType.INFIRMARY -> 'I'
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
        sb.append("  %-10s %-8s %-8s hp=%d/%d xp=%d%s\n".format(sol.id, sol.name, sol.cls, sol.hp, sol.stats.maxHp, sol.xp, status))
    }
    if (c.roster.isEmpty()) sb.append("  (yok)\n")
    val peer = s.campuses[seat.peer()]
    sb.append("Rakip kampüs: ${peer.grid.count { it != null }} tesis, ${peer.roster.size} asker (stok gizli)\n")
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
    is StrategicCommand.ClaimNode -> "talep: ${c.nodeId}"
    is StrategicCommand.StartResearch -> "araştır: ${c.tech}"
    is StrategicCommand.SendShipment -> "sevk et: alloy ${c.resources.alloy} cells ${c.resources.cells} biomass ${c.resources.biomass} + ${c.soldierIds.joinToString()}"
}

fun describeOrder(o: ProductionOrder): String = when (o) {
    is ProductionOrder.Build -> "inşaat slot ${o.slotIndex} (${o.type}), ${o.remainingEpochs} epoch"
    is ProductionOrder.Train -> "eğitim ${o.cls}, ${o.remainingEpochs} epoch"
}

fun describe(e: GameEvent): String = when (e) {
    is GameEvent.CommandRejected -> "[${e.actor}] REDDEDİLDİ: ${e.reason}"
    is GameEvent.NodeClaimed -> "[${e.owner}] düğüm talep edildi: ${e.nodeId}"
    is GameEvent.FacilityQueued -> "[${e.owner}] inşaat sıraya girdi: slot ${e.slot} (${e.type})"
    is GameEvent.FacilityBuilt -> "[${e.owner}] tesis tamamlandı: slot ${e.slot} (${e.type})"
    is GameEvent.RecruitQueued -> "[${e.owner}] eğitim başladı: ${e.cls}"
    is GameEvent.SoldierRecruited -> "[${e.owner}] yeni asker: ${e.soldierId} ${e.name}"
    is GameEvent.ResourcesProduced -> "[${e.owner}] kaynak: +alloy ${e.amount.alloy} +cells ${e.amount.cells} +biomass ${e.amount.biomass}"
    is GameEvent.SquadDispatched -> "[${e.owner}] ${e.size} asker ${e.regionId} bölgesine sevk edildi"
    is GameEvent.Tactical -> describeSkirmish(e.event)
    is GameEvent.SoldierKilled -> "[${e.owner}] ${e.soldierId} öldü"
    is GameEvent.SoldierInjured -> "[${e.owner}] ${e.soldierId} yaralandı"
    is GameEvent.MissionEnded -> "[${e.owner}] ${if (e.won) "GÖREV BAŞARILI" else "GÖREV BAŞARISIZ"}"
    is GameEvent.IncursionWarning -> "UYARI: ${e.regionId} bölgesine epoch ${e.arrivalEpoch}'de saldırı"
    is GameEvent.IncursionArrived -> "SALDIRI: ${e.regionId} bölgesine saldırı indi"
    is GameEvent.ShipmentSent -> "[${e.from}] sevkiyat yola çıktı -> ${e.to} (alloy ${e.delivered.alloy}, cells ${e.delivered.cells}, biomass ${e.delivered.biomass} + ${e.soldierCount} asker), epoch ${e.arrivalEpoch}'de varır"
    is GameEvent.ShipmentArrived -> "[${e.to}] sevkiyat ulaştı (alloy ${e.delivered.alloy}, cells ${e.delivered.cells}, biomass ${e.delivered.biomass} + ${e.soldierCount} asker)"
    is GameEvent.ResearchStarted -> "[${e.owner}] araştırma başladı: ${e.tech}"
    is GameEvent.ResearchCompleted -> "[${e.owner}] araştırma tamamlandı: ${e.tech}"
    is GameEvent.MissionLaunched -> "${if (e.kind == MissionKind.DEFENSE) "SAVUNMA" else "GÖREV"} BAŞLADI: ${e.regionId} (${e.participants.joinToString(" + ")})"
    is GameEvent.DefenseFailed -> "[${e.owner}] savunma kaybedildi: ${e.regionId} — düğüm talepleri düştü"
    is GameEvent.FacilityDestroyed -> "[${e.owner}] tesis yıkıldı: slot ${e.slot} (${e.type})"
    is GameEvent.EpochStarted -> "--- Epoch ${e.epoch} ---"
}

fun describeSkirmish(e: SkirmishEvent): String = when (e) {
    is SkirmishEvent.Moved -> "${e.unitId} -> (${e.to.x},${e.to.y})"
    is SkirmishEvent.Shot ->
        if (e.hit) "${e.attackerId} ${e.targetId}'i vurdu (-${e.damage})" else "${e.attackerId} ıskaladı (${e.targetId})"
    is SkirmishEvent.Died -> "${e.unitId} öldü"
    is SkirmishEvent.PhaseEnded -> "${e.owner} fazını bitirdi"
    is SkirmishEvent.TurnEnded -> "Tur ${e.round} bitti. Uzaylılar hareket ediyor."
    is SkirmishEvent.Victory -> "Savaş bitti: ${if (e.side == Side.PLAYER) "squad kazandı" else "uzaylılar kazandı"} (${e.reason})"
}
