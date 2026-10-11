package hypogeum.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hypogeum.domain.campus.FacilityType
import hypogeum.domain.content.Content
import hypogeum.domain.core.Pos
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.Phase
import hypogeum.domain.roster.SoldierClass
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.Side
import hypogeum.domain.core.peer

@Composable
fun HotseatScreen(vm: HotseatViewModel) {
    // Sayaç okunarak yeniden çizim tetiklenir.
    @Suppress("UNUSED_VARIABLE") val tick = vm.version
    val s = vm.session
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Hypogeum · epoch ${s.state.epoch}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        when {
            vm.handoff -> Handoff(vm)
            s.state.phase == Phase.TACTICAL -> Tactics(vm)
            else -> Planning(vm)
        }
        s.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        EventLog(s.lastEvents)
        OutlinedButton(onClick = { vm.restart(System.currentTimeMillis() % 100_000) }) { Text("Yeni oyun") }
    }
}

@Composable
private fun Handoff(vm: HotseatViewModel) {
    Text("Koltuk ${vm.session.seat.peer()} sırada. Planını yazmadan önce ekranı ona devret.")
    Button(onClick = { vm.confirmHandoff() }) { Text("${vm.session.seat} hazır") }
}

@Composable
private fun Planning(vm: HotseatViewModel) {
    val s = vm.session
    val seat = s.seat
    val state = s.state
    val campus = state.campuses[seat]
    Text("Koltuk $seat · taslak ${s.draft().size} komut", fontWeight = FontWeight.SemiBold)
    Text("Stok: alloy ${campus.stock.alloy} · cells ${campus.stock.cells} · biomass ${campus.stock.biomass}")
    Text("Tesisler: ${campus.grid.count { it != null }} · Askerler: ${campus.roster.joinToString { "${it.name}(${it.cls.name.lowercase()}${if (it.injuryEpochs > 0) ",yaralı" else ""})" }}")
    Text("Tamamlanan araştırma: ${campus.research.completed.ifEmpty { listOf("yok") }.joinToString()}")
    Text("Rakip: ${state.campuses[seat.peer()].grid.count { it != null }} tesis, ${state.campuses[seat.peer()].roster.size} asker (stok gizli)")
    Text("Dünya: " + state.world.regions.joinToString { "${it.id} tehdit ${it.threat}" })

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { vm.act { addDraft(StrategicCommand.Recruit(SoldierClass.RIFLEMAN)) } }) { Text("Asker") }
        Button(onClick = { addBuild(vm, FacilityType.BARRACKS) }) { Text("Barakka") }
        Button(onClick = { addBuild(vm, FacilityType.LAB) }) { Text("Lab") }
        Button(onClick = { addBuild(vm, FacilityType.RELAY) }) { Text("Röle") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { addDispatch(vm, "dock") }) { Text("Sevk: dock") }
        Button(onClick = { addDispatch(vm, "gate") }) { Text("Sevk: gate") }
        Button(onClick = { addClaim(vm) }) { Text("Talep") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { addResearch(vm) }) { Text("Araştır") }
        OutlinedButton(onClick = { vm.act { clearDraft() } }) { Text("Taslağı temizle") }
        Button(onClick = { vm.commit() }) { Text("Commit") }
    }
    Text("Taslak:\n" + s.draft().joinToString("\n") { "• ${it.describe()}" })
}

private fun addBuild(vm: HotseatViewModel, type: FacilityType) {
    val campus = vm.session.state.campuses[vm.session.seat]
    val slot = campus.grid.indices.firstOrNull { campus.grid[it] == null && !campus.queuedBuildAt(it) }
    if (slot == null) {
        vm.act { addDraft(StrategicCommand.Build(-1, type)) } // çıkış: reddedilecek ve sebebi gösterilecek
    } else {
        vm.act { addDraft(StrategicCommand.Build(slot, type)) }
    }
}

private fun addDispatch(vm: HotseatViewModel, region: String) {
    val campus = vm.session.state.campuses[vm.session.seat]
    val soldier = campus.roster.firstOrNull { it.injuryEpochs == 0 }
    vm.act {
        addDraft(StrategicCommand.Dispatch(region, listOfNotNull(soldier?.id ?: "yok")))
    }
}

private fun addClaim(vm: HotseatViewModel) {
    val node = vm.session.state.world.regions.flatMap { it.nodes }.firstOrNull { it.claimedBy == null }
    vm.act { addDraft(StrategicCommand.ClaimNode(node?.id ?: "yok")) }
}

private fun addResearch(vm: HotseatViewModel) {
    val campus = vm.session.state.campuses[vm.session.seat]
    val tech = Content.TECHS.firstOrNull { it.id !in campus.research.completed }?.id ?: "yok"
    vm.act { addDraft(StrategicCommand.StartResearch(tech)) }
}

@Composable
private fun Tactics(vm: HotseatViewModel) {
    val s = vm.session
    val m = s.state.missions.first()
    val st = m.skirmish
    Text("Görev ${m.regionId} · sıra ${s.activeSeat} · tur ${st.round}", fontWeight = FontWeight.SemiBold)
    Text(if (vm.selectedUnit == null) "Bir askerine dokun, sonra bir kareye (yürü) ya da düşmana (ateş)." else "Seçili: ${vm.selectedUnit}")
    Column {
        for (y in 0 until st.map.height) {
            Row {
                for (x in 0 until st.map.width) {
                    val p = Pos(x, y)
                    val unit = st.combatants.firstOrNull { it.pos == p }
                    val bg = when {
                        unit == null -> Color(0xFFE8E4DA)
                        unit.side == Side.ALIEN -> Color(0xFFC0504D)
                        unit.id == vm.selectedUnit -> Color(0xFF2E7D8C)
                        else -> Color(0xFF8DB7C8)
                    }
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(bg)
                            .clickable { vm.tapCell(p) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(unit?.id ?: st.map.terrainAt(p).glyph.toString(), color = Color.Black)
                    }
                }
            }
        }
    }
    Button(onClick = { vm.endTurn() }, modifier = Modifier.fillMaxWidth()) { Text("Fazı bitir") }
}

@Composable
private fun EventLog(events: List<GameEvent>) {
    if (events.isEmpty()) return
    Column {
        Text("Olaylar", fontWeight = FontWeight.SemiBold)
        events.takeLast(12).forEach { Text("• ${it.label()}") }
    }
}

private fun StrategicCommand.describe(): String = when (this) {
    is StrategicCommand.Build -> "inşa: slot $slotIndex ($type)"
    is StrategicCommand.Recruit -> "asker: $cls"
    is StrategicCommand.Dispatch -> "sevk: $regionId ← ${soldierIds.joinToString()}"
    is StrategicCommand.ClaimNode -> "talep: $nodeId"
    is StrategicCommand.StartResearch -> "araştır: $tech"
    is StrategicCommand.SendShipment -> "sevkiyat"
}

private fun GameEvent.label(): String = when (this) {
    is GameEvent.CommandRejected -> "[$actor] reddedildi: $reason"
    is GameEvent.NodeClaimed -> "[$owner] düğüm talep edildi: $nodeId"
    is GameEvent.FacilityBuilt -> "[$owner] tesis tamamlandı: $type"
    is GameEvent.SoldierRecruited -> "[$owner] yeni asker: $name"
    is GameEvent.SquadDispatched -> "[$owner] $size asker $regionId'e sevk edildi"
    is GameEvent.MissionLaunched -> "görev başladı: $regionId"
    is GameEvent.MissionEnded -> "[$owner] görev ${if (won) "kazanıldı" else "kaybedildi"}"
    is GameEvent.DefenseFailed -> "[$owner] savunma kaybedildi: $regionId"
    is GameEvent.IncursionWarning -> "uyarı: $regionId epoch $arrivalEpoch'de saldırıya uğrayabilir"
    is GameEvent.IncursionArrived -> "saldırı: $regionId"
    is GameEvent.EpochStarted -> "--- epoch $epoch ---"
    else -> this::class.simpleName ?: "olay"
}
