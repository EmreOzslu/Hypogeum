package hypogeum.apk

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import hypogeum.data.session.HotseatSession
import hypogeum.domain.campus.FacilityType
import hypogeum.domain.content.Content
import hypogeum.domain.core.PlayerId
import hypogeum.domain.core.Pos
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.MissionKind
import hypogeum.domain.game.Phase
import hypogeum.domain.roster.SoldierClass
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.Side
import hypogeum.domain.tactics.SkirmishCommand

/**
 * Tek cihazda iki koltuk. Çerçeve görünümleri (Compose değil): derleme ortamında Compose
 * kütüphaneleri bulunmadığı için. Oyun kuralları HotseatSession üzerinden gelir.
 */
class MainActivity : Activity() {
    private lateinit var session: HotseatSession
    private var handoff = false
    private var selected: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        newGame(System.currentTimeMillis() % 100_000)
    }

    private fun newGame(seed: Long) {
        session = HotseatSession(seed)
        handoff = false
        selected = null
        render()
    }

    private fun render() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        col.addView(text("Hypogeum · epoch ${session.state.epoch}", size = 22f, bold = true))
        when {
            handoff -> renderHandoff(col)
            session.state.phase == Phase.TACTICAL -> renderTactics(col)
            else -> renderPlanning(col)
        }
        session.lastError?.let { col.addView(text(it, color = Color.RED)) }
        renderEvents(col)
        col.addView(button("Yeni oyun") { newGame(System.currentTimeMillis() % 100_000) })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun renderHandoff(col: LinearLayout) {
        col.addView(text("Koltuk ${session.seat.other()} sırada. Planını yazmadan önce ekranı ona devret."))
        col.addView(button("${session.seat} hazır") {
            handoff = false
            render()
        })
    }

    private fun renderPlanning(col: LinearLayout) {
        val seat = session.seat
        val state = session.state
        val campus = state.campuses[seat]
        val peer = state.campuses[seat.other()]
        col.addView(text("Koltuk $seat · taslak ${session.draft().size} komut", bold = true))
        col.addView(text("Stok: alloy ${campus.stock.alloy} · cells ${campus.stock.cells} · biomass ${campus.stock.biomass}"))
        col.addView(text("Tesisler: ${campus.grid.count { it != null }} · Askerler: " +
            campus.roster.joinToString { "${it.name} (${it.cls.name.lowercase()}${if (it.injuryEpochs > 0) ", yaralı" else ""})" }))
        col.addView(text("Tamamlanan araştırma: ${campus.research.completed.ifEmpty { listOf("yok") }.joinToString()}"))
        col.addView(text("Rakip: ${peer.grid.count { it != null }} tesis, ${peer.roster.size} asker (stok gizli)"))
        col.addView(text("Dünya: " + state.world.regions.joinToString { "${it.id} tehdit ${it.threat}" }))

        col.addView(row(
            button("Asker") { add(StrategicCommand.Recruit(SoldierClass.RIFLEMAN)) },
            button("Barakka") { addBuild(FacilityType.BARRACKS) },
            button("Lab") { addBuild(FacilityType.LAB) },
            button("Röle") { addBuild(FacilityType.RELAY) },
        ))
        col.addView(row(
            button("Sevk: dock") { addDispatch("dock") },
            button("Sevk: gate") { addDispatch("gate") },
            button("Talep") { addClaim() },
        ))
        col.addView(row(
            button("Araştır") { addResearch() },
            button("Taslağı temizle") {
                session.clearDraft()
                render()
            },
            button("Commit") { commit() },
        ))
        col.addView(text("Taslak:\n" + session.draft().joinToString("\n") { "• ${it.describe()}" }))
    }

    private fun add(cmd: StrategicCommand) {
        session.addDraft(cmd)
        render()
    }

    private fun addBuild(type: FacilityType) {
        val campus = session.state.campuses[session.seat]
        val slot = campus.grid.indices.firstOrNull { campus.grid[it] == null && !campus.queuedBuildAt(it) } ?: -1
        add(StrategicCommand.Build(slot, type))
    }

    private fun addDispatch(region: String) {
        val soldier = session.state.campuses[session.seat].roster.firstOrNull { it.injuryEpochs == 0 }
        add(StrategicCommand.Dispatch(region, listOf(soldier?.id ?: "yok")))
    }

    private fun addClaim() {
        val node = session.state.world.regions.flatMap { it.nodes }.firstOrNull { it.claimedBy == null }
        add(StrategicCommand.ClaimNode(node?.id ?: "yok"))
    }

    private fun addResearch() {
        val campus = session.state.campuses[session.seat]
        val tech = Content.TECHS.firstOrNull { it.id !in campus.research.completed }?.id ?: "yok"
        add(StrategicCommand.StartResearch(tech))
    }

    private fun commit() {
        session.commit()
        handoff = session.awaitingHandoff
        selected = null
        render()
    }

    private fun renderTactics(col: LinearLayout) {
        val m = session.state.missions.first()
        val st = m.skirmish
        col.addView(text("${if (m.kind == MissionKind.DEFENSE) "Savunma" else "Görev"} ${m.regionId} · sıra ${session.activeSeat} · tur ${st.round}", bold = true))
        col.addView(text(selected?.let { "Seçili: $it" } ?: "Bir askerine dokun, sonra bir kareye (yürü) ya da düşmana (ateş) dokun."))
        val grid = GridLayout(this).apply {
            columnCount = st.map.width
            rowCount = st.map.height
        }
        for (y in 0 until st.map.height) {
            for (x in 0 until st.map.width) {
                val p = Pos(x, y)
                val unit = st.combatants.firstOrNull { it.pos == p }
                val bg = when {
                    unit == null -> Color.parseColor("#E8E4DA")
                    unit.side == Side.ALIEN -> Color.parseColor("#C0504D")
                    unit.id == selected -> Color.parseColor("#2E7D8C")
                    else -> Color.parseColor("#8DB7C8")
                }
                val cell = Button(this).apply {
                    text = unit?.id ?: st.map.terrainAt(p).glyph.toString()
                    textSize = 9f
                    setPadding(0, 0, 0, 0)
                    minWidth = 0
                    minimumWidth = 0
                    setBackgroundColor(bg)
                    setTextColor(Color.BLACK)
                    setOnClickListener { tap(p) }
                }
                grid.addView(cell)
            }
        }
        col.addView(grid)
        col.addView(button("Fazı bitir") {
            session.tacticalCommand(SkirmishCommand.EndTurn)
            render()
        })
    }

    private fun tap(p: Pos) {
        val st = session.state.missions.firstOrNull()?.skirmish ?: return
        val at = st.combatants.firstOrNull { it.pos == p }
        if (at != null && at.side == Side.PLAYER && at.owner == session.activeSeat) {
            selected = at.id
            render()
            return
        }
        val sel = selected ?: return
        val cmd = if (at != null && at.side == Side.ALIEN) SkirmishCommand.Shoot(sel, at.id) else SkirmishCommand.Move(sel, p)
        session.tacticalCommand(cmd)
        if (session.state.phase != Phase.TACTICAL) selected = null
        render()
    }

    private fun renderEvents(col: LinearLayout) {
        val events = session.lastEvents
        if (events.isEmpty()) return
        col.addView(text("Olaylar", bold = true))
        events.takeLast(12).forEach { col.addView(text("• ${it.label()}")) }
    }

    private fun text(s: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.BLACK): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            gravity = Gravity.START
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun PlayerId.other(): PlayerId = if (this == PlayerId.P1) PlayerId.P2 else PlayerId.P1

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
        else -> this::class.java.simpleName
    }
}
