package hypogeum.domain.strategy

import hypogeum.domain.campus.Campus
import hypogeum.domain.campus.Facility
import hypogeum.domain.campus.FacilityType
import hypogeum.domain.campus.ProductionOrder
import hypogeum.domain.campus.ActiveResearch
import hypogeum.domain.campus.Resources
import hypogeum.domain.content.Content
import hypogeum.domain.core.IdCounter
import hypogeum.domain.core.PerPlayer
import hypogeum.domain.core.PlayerId
import hypogeum.domain.core.Rng
import hypogeum.domain.core.RngState
import hypogeum.domain.core.peer
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.game.Mission
import hypogeum.domain.game.MissionKind
import hypogeum.domain.game.PendingDispatch
import hypogeum.domain.game.Phase
import hypogeum.domain.game.SquadLink
import hypogeum.domain.roster.Soldier
import hypogeum.domain.roster.SoldierFactory
import hypogeum.domain.tactics.Combatant
import hypogeum.domain.tactics.Side
import hypogeum.domain.tactics.Skirmish
import hypogeum.domain.world.Incursion
import hypogeum.domain.game.Shipment
import hypogeum.domain.world.ResourceKind
import hypogeum.domain.world.World
import hypogeum.domain.world.WorldRegion

/** Stratejik kurallar: komut doğrulama, sevk, epoch sonu üretimi, dünya tiki ve görev sonucu. */
internal object Strategy {
    fun apply(s: GameState, actor: PlayerId, cmd: StrategicCommand): GameStep = when (cmd) {
        is StrategicCommand.Build -> build(s, actor, cmd)
        is StrategicCommand.Recruit -> recruit(s, actor, cmd)
        is StrategicCommand.Dispatch -> dispatch(s, actor, cmd)
        is StrategicCommand.ClaimNode -> claim(s, actor, cmd)
        is StrategicCommand.StartResearch -> research(s, actor, cmd)
        is StrategicCommand.SendShipment -> shipment(s, actor, cmd)
    }

    private fun build(s: GameState, actor: PlayerId, cmd: StrategicCommand.Build): GameStep {
        val c = s.campuses[actor]
        if (cmd.slotIndex !in c.grid.indices) return GameStep.Rejected("slot out of range")
        if (c.grid[cmd.slotIndex] != null || c.queuedBuildAt(cmd.slotIndex)) return GameStep.Rejected("slot occupied")
        val spec = Content.facility(cmd.type)
        if (!c.stock.covers(spec.cost)) return GameStep.Rejected("not enough resources for ${cmd.type}")
        val campus = c.copy(
            stock = c.stock - spec.cost,
            queue = c.queue + ProductionOrder.Build(cmd.slotIndex, cmd.type, spec.buildEpochs),
        )
        return GameStep.Ok(withCampus(s, actor, campus), listOf(GameEvent.FacilityQueued(actor, cmd.slotIndex, cmd.type)))
    }

    private fun recruit(s: GameState, actor: PlayerId, cmd: StrategicCommand.Recruit): GameStep {
        val c = s.campuses[actor]
        val barracks = c.built(FacilityType.BARRACKS)
        if (barracks == 0) return GameStep.Rejected("build a barracks first")
        if (c.trainCount() >= barracks) return GameStep.Rejected("all barracks are busy")
        if (!c.stock.covers(Content.RECRUIT_COST)) return GameStep.Rejected("not enough resources to recruit")
        val campus = c.copy(
            stock = c.stock - Content.RECRUIT_COST,
            queue = c.queue + ProductionOrder.Train(cmd.cls, Content.RECRUIT_EPOCHS),
        )
        return GameStep.Ok(withCampus(s, actor, campus), listOf(GameEvent.RecruitQueued(actor, cmd.cls)))
    }

    private fun claim(s: GameState, actor: PlayerId, cmd: StrategicCommand.ClaimNode): GameStep {
        val (region, node) = s.world.findNode(cmd.nodeId) ?: return GameStep.Rejected("unknown node ${cmd.nodeId}")
        if (node.claimedBy != null) return GameStep.Rejected("node already claimed")
        val c = s.campuses[actor]
        if (!c.stock.covers(Content.CLAIM_COST)) return GameStep.Rejected("not enough resources to claim")
        val claimed = region.copy(nodes = region.nodes.map { if (it.id == node.id) it.copy(claimedBy = actor) else it })
        val next = withCampus(s.copy(world = s.world.withRegion(claimed)), actor, c.copy(stock = c.stock - Content.CLAIM_COST))
        return GameStep.Ok(next, listOf(GameEvent.NodeClaimed(actor, node.id)))
    }

    private fun research(s: GameState, actor: PlayerId, cmd: StrategicCommand.StartResearch): GameStep {
        val c = s.campuses[actor]
        val spec = Content.tech(cmd.tech) ?: return GameStep.Rejected("unknown tech ${cmd.tech}")
        if (cmd.tech in c.research.completed) return GameStep.Rejected("${spec.name} already researched")
        if (c.research.active != null) return GameStep.Rejected("research already running")
        if (!c.has(FacilityType.LAB)) return GameStep.Rejected("build a lab first")
        if (!c.stock.covers(spec.cost)) return GameStep.Rejected("not enough resources for ${spec.name}")
        val campus = c.copy(
            stock = c.stock - spec.cost,
            research = c.research.copy(active = ActiveResearch(cmd.tech, spec.epochs)),
        )
        return GameStep.Ok(withCampus(s, actor, campus), listOf(GameEvent.ResearchStarted(actor, cmd.tech)))
    }

    /**
     * Sevkiyat: kaynak ve asker peer'in kampüsüne gider. Kaynağın Content.TRANSIT_LOSS_PERCENT'i yolda kaybolur.
     * Gönderen stoktan ve kadrodan hemen düşer; varış transitEpochs sonra olur. Hat kapasitesi paylaşılır.
     */
    private fun shipment(s: GameState, actor: PlayerId, cmd: StrategicCommand.SendShipment): GameStep {
        val c = s.campuses[actor]
        if (s.world.transit.usedThisEpoch >= s.world.transit.capacityPerEpoch) return GameStep.Rejected("transit capacity full")
        if (!c.has(FacilityType.RELAY)) return GameStep.Rejected("build a relay to ship")
        if (cmd.resources.isZero() && cmd.soldierIds.isEmpty()) return GameStep.Rejected("empty shipment")
        if (!cmd.resources.isNonNegative()) return GameStep.Rejected("negative amount")
        if (cmd.soldierIds.size > Content.MAX_SQUAD) return GameStep.Rejected("at most ${Content.MAX_SQUAD} soldiers per shipment")
        if (cmd.soldierIds.distinct().size != cmd.soldierIds.size) return GameStep.Rejected("duplicate soldier in shipment")
        if (!c.stock.covers(cmd.resources)) return GameStep.Rejected("not enough resources to ship")
        val soldiers = cmd.soldierIds.map { id -> c.soldier(id) ?: return GameStep.Rejected("no soldier $id") }
        soldiers.firstOrNull { it.injuryEpochs > 0 }?.let { return GameStep.Rejected("${it.id} is injured") }
        soldiers.firstOrNull { isBusy(s, it.id) }?.let { return GameStep.Rejected("${it.id} is already on a mission") }

        val loss = cmd.resources.mapEach { (it * Content.TRANSIT_LOSS_PERCENT + 99) / 100 }
        val delivered = cmd.resources - loss
        val (shipId, ids) = s.ids.issue("shipment")
        val shipment = Shipment(
            id = shipId,
            from = actor,
            to = actor.peer(),
            delivered = delivered,
            soldiers = soldiers,
            arrivalEpoch = s.epoch + s.world.transit.transitEpochs,
        )
        val sender = c.copy(
            stock = c.stock - cmd.resources,
            roster = c.roster.filterNot { it.id in cmd.soldierIds },
        )
        val next = withCampus(s, actor, sender).copy(
            world = s.world.copy(transit = s.world.transit.copy(usedThisEpoch = s.world.transit.usedThisEpoch + 1)),
            shipments = s.shipments + shipment,
            ids = ids,
        )
        return GameStep.Ok(next, listOf(GameEvent.ShipmentSent(actor, shipment.to, delivered, soldiers.size, shipment.arrivalEpoch)))
    }

    private fun dispatch(s: GameState, actor: PlayerId, cmd: StrategicCommand.Dispatch): GameStep {
        val c = s.campuses[actor]
        val ids = cmd.soldierIds
        if (ids.isEmpty() || ids.size > Content.MAX_SQUAD) return GameStep.Rejected("squad size must be 1..${Content.MAX_SQUAD}")
        if (ids.distinct().size != ids.size) return GameStep.Rejected("duplicate soldier in squad")
        if (s.pendingDispatches.any { it.owner == actor }) return GameStep.Rejected("one squad per seat per epoch")
        if (s.world.region(cmd.regionId) == null) return GameStep.Rejected("unknown region ${cmd.regionId}")
        val squad = ids.map { id -> c.soldier(id) ?: return GameStep.Rejected("no soldier $id") }
        squad.firstOrNull { it.injuryEpochs > 0 }?.let { return GameStep.Rejected("${it.id} is injured") }
        squad.firstOrNull { sol -> isBusy(s, sol.id) }?.let { return GameStep.Rejected("${it.id} is already on a mission") }
        val next = s.copy(pendingDispatches = s.pendingDispatches + PendingDispatch(actor, cmd.regionId, ids))
        return GameStep.Ok(next, listOf(GameEvent.SquadDispatched(actor, cmd.regionId, ids.size)))
    }

    private fun isBusy(s: GameState, soldierId: String): Boolean =
        s.missions.any { m -> m.squads.any { it.soldierId == soldierId } } ||
            s.pendingDispatches.any { soldierId in it.soldierIds }

    /**
     * Çözüm sonu: bekleyen sevkleri bölgeye göre gruplar. Aynı bölgeye giden squad'lar tek ortak görev olur.
     * Görev sırası, bölgelerin ilk sevk sırasıdır. Görevler mevcut listenin sonuna eklenir.
     */
    fun launchMissions(s: GameState): GameState {
        val (missions, rng) = buildMissions(s, s.pendingDispatches, MissionKind.ASSAULT, s.rng)
        return s.copy(rng = rng, missions = s.missions + missions, pendingDispatches = emptyList())
    }

    /** Sevk listesinden bölge başına bir görev kurar. Squad'lar aynı kuralla konuşlanır. */
    private fun buildMissions(
        s: GameState,
        dispatches: List<PendingDispatch>,
        kind: MissionKind,
        rng0: RngState,
    ): Pair<List<Mission>, RngState> {
        var rng = rng0
        val regionOrder = dispatches.map { it.regionId }.distinct()
        val missions = regionOrder.map { regionId ->
            val group = dispatches.filter { it.regionId == regionId }.sortedBy { it.owner.ordinal }
            val region = requireNotNull(s.world.region(regionId))
            val spec = requireNotNull(Content.regionSpec(regionId))
            val (seed, next) = Rng.nextLong(rng)
            rng = next

            val links = mutableListOf<SquadLink>()
            val combatants = mutableListOf<Combatant>()
            var n = 0
            for (d in group) {
                val c = s.campuses[d.owner]
                val done = c.research.completed
                for ((i, soldierId) in d.soldierIds.withIndex()) {
                    n++
                    val sol = requireNotNull(c.soldier(soldierId))
                    combatants += Skirmish.squadmate(
                        id = "p$n",
                        owner = d.owner,
                        pos = Skirmish.DEPLOY_SQUAD[d.owner][i],
                        hp = sol.hp,
                        aim = sol.stats.aim + if ("ballistics" in done) Content.BALLISTICS_AIM_BONUS else 0,
                        damage = sol.stats.damage + if ("reinforced_hulls" in done) Content.REINFORCED_DAMAGE_BONUS else 0,
                    )
                    links += SquadLink(d.owner, "p$n", soldierId)
                }
            }
            val participants = group.map { it.owner }
            val aliens = (0 until spec.alienCount(region.threat, participants.size)).map { i ->
                Skirmish.alien("a${i + 1}", Skirmish.DEPLOY_ALIEN[i])
            }
            Mission(
                kind = kind,
                participants = participants,
                regionId = regionId,
                squads = links,
                rewardAlloy = spec.reward(region.threat),
                skirmish = Skirmish.start(seed, Skirmish.defaultMap(), combatants + aliens),
            )
        }
        return missions to rng
    }

    /**
     * Saldırı iner (arrivalEpoch == epoch). Sahipli bir bölgede her sahibin hazır askerleri savunma görevi kurar.
     * Sahipsiz bölgede yalnızca tehdit sıfırlanır. Hazır asker yoksa savunma kendiliğinden kaybedilir.
     */
    fun landIncursions(s: GameState, events: MutableList<GameEvent>): GameState {
        val arriving = s.world.incursions.filter { it.arrivalEpoch == s.epoch }
        if (arriving.isEmpty()) return s
        var st = s.copy(world = s.world.copy(incursions = s.world.incursions.filterNot { it.arrivalEpoch == s.epoch }))
        for (inc in arriving) {
            val r = st.world.region(inc.regionId) ?: continue
            events += GameEvent.IncursionArrived(r.id)
            val owners = r.nodes.mapNotNull { it.claimedBy }.distinct().sortedBy { it.ordinal }
            if (owners.isEmpty()) {
                st = st.copy(world = st.world.withRegion(r.copy(threat = Content.MIN_THREAT)))
                continue
            }
            val squads = owners.mapNotNull { owner ->
                readyDefenders(st, owner)?.let { PendingDispatch(owner, r.id, it) }
            }
            if (squads.isEmpty()) {
                st = loseRegion(st, r.id, owners, events)
                continue
            }
            val (missions, rng) = buildMissions(st, squads, MissionKind.DEFENSE, st.rng)
            st = st.copy(rng = rng, missions = st.missions + missions)
        }
        return st
    }

    /** Savunmaya girebilecek asker kimlikleri (en fazla bir kadro). Yoksa null. */
    private fun readyDefenders(s: GameState, owner: PlayerId): List<String>? {
        val ids = s.campuses[owner].roster
            .filter { it.injuryEpochs == 0 && it.hp > 0 && !isBusy(s, it.id) }
            .take(Content.MAX_SQUAD)
            .map { it.id }
        return ids.ifEmpty { null }
    }

    /** Savunma kaybedildi: bölgenin tüm sahiplikleri düşer, tehdit sıfırlanır, her kampüsten bir tesis yıkılır. */
    private fun loseRegion(s: GameState, regionId: String, owners: List<PlayerId>, events: MutableList<GameEvent>): GameState {
        val r = requireNotNull(s.world.region(regionId))
        var st = s.copy(
            world = s.world.withRegion(
                r.copy(threat = Content.MIN_THREAT, nodes = r.nodes.map { it.copy(claimedBy = null) }),
            ),
        )
        for (owner in owners) {
            events += GameEvent.DefenseFailed(owner, regionId)
            val (campus, rng) = damage(st.campuses[owner], owner, st.rng, events)
            st = st.copy(campuses = st.campuses.with(owner, campus), rng = rng)
        }
        return st
    }

    /** Rastgele bir tesisi yıkar. Tesis yoksa değişmez. */
    private fun damage(c: Campus, owner: PlayerId, rng0: RngState, events: MutableList<GameEvent>): Pair<Campus, RngState> {
        val built = c.grid.indices.filter { c.grid[it] != null }
        if (built.isEmpty()) return c to rng0
        val draw = Rng.nextInt(rng0, built.size)
        val slot = built[draw.value]
        val type = requireNotNull(c.grid[slot]).type
        events += GameEvent.FacilityDestroyed(owner, slot, type)
        val grid = c.grid.toMutableList()
        grid[slot] = null
        return c.copy(grid = grid) to draw.next
    }

    /**
     * Epoch sonu: varan sevkiyatlar, üretim ve düğüm verimi, kuyruk, araştırma, iyileşme, dünya tiki.
     * Sonra kapasite sayacı sıfırlanır ve sıradaki epoch başlar.
     */
    fun endEpoch(s: GameState): GameStep.Ok {
        val events = mutableListOf<GameEvent>()
        var campuses = s.campuses
        var rng: RngState = s.rng
        var ids: IdCounter = s.ids

        val arriving = s.shipments.filter { it.arrivalEpoch == s.epoch }
        for (sh in arriving) {
            val c = campuses[sh.to]
            campuses = campuses.with(sh.to, c.copy(stock = c.stock + sh.delivered, roster = c.roster + sh.soldiers))
            events += GameEvent.ShipmentArrived(sh.to, sh.delivered, sh.soldiers.size)
        }
        val shipments = s.shipments.filterNot { it.arrivalEpoch == s.epoch }

        val p1 = upkeep(campuses.p1, PlayerId.P1, s.world, rng, ids, events)
        rng = p1.rng
        ids = p1.ids
        val p2 = upkeep(campuses.p2, PlayerId.P2, s.world, rng, ids, events)
        rng = p2.rng
        ids = p2.ids

        val world = tickWorld(s.world, s.epoch, s.victories, events)
        val next = s.copy(
            epoch = s.epoch + 1,
            phase = Phase.PLANNING,
            campuses = PerPlayer(p1.campus, p2.campus),
            world = world.copy(transit = world.transit.copy(usedThisEpoch = 0)),
            rng = rng,
            ids = ids,
            shipments = shipments,
            victories = emptyList(),
        )
        events += GameEvent.EpochStarted(next.epoch)
        return GameStep.Ok(next, events)
    }

    private data class Upkept(val campus: Campus, val rng: RngState, val ids: IdCounter)

    private fun upkeep(c: Campus, owner: PlayerId, world: World, rng0: RngState, ids0: IdCounter, events: MutableList<GameEvent>): Upkept {
        var rng = rng0
        var ids = ids0

        var gain = Resources()
        for (f in c.grid) if (f != null) gain += Content.facility(f.type).output
        for (region in world.regions) for (node in region.nodes) {
            if (node.claimedBy != owner) continue
            val amount = if (c.isolated) node.yieldPerEpoch / 2 else node.yieldPerEpoch
            gain += when (node.kind) {
                ResourceKind.ALLOY -> Resources(alloy = amount)
                ResourceKind.CELLS -> Resources(cells = amount)
                ResourceKind.BIOMASS -> Resources(biomass = amount)
            }
        }
        val stock = c.stock + gain
        if (gain != Resources()) events += GameEvent.ResourcesProduced(owner, gain)

        val grid = c.grid.toMutableList()
        val queue = mutableListOf<ProductionOrder>()
        val roster = c.roster.toMutableList()
        for (order in c.queue) {
            val left = order.remainingEpochs - 1
            if (left > 0) {
                queue += when (order) {
                    is ProductionOrder.Build -> order.copy(remainingEpochs = left)
                    is ProductionOrder.Train -> order.copy(remainingEpochs = left)
                }
                continue
            }
            when (order) {
                is ProductionOrder.Build -> {
                    grid[order.slotIndex] = Facility(order.type)
                    events += GameEvent.FacilityBuilt(owner, order.slotIndex, order.type)
                }
                is ProductionOrder.Train -> {
                    val made = SoldierFactory.make(rng, ids, order.cls)
                    rng = made.rng
                    ids = made.ids
                    roster += made.soldier
                    events += GameEvent.SoldierRecruited(owner, made.soldier.id, made.soldier.name)
                }
            }
        }

        var research = c.research
        research.active?.let { active ->
            val left = active.remainingEpochs - 1
            research = if (left > 0) {
                research.copy(active = active.copy(remainingEpochs = left))
            } else {
                events += GameEvent.ResearchCompleted(owner, active.tech)
                research.copy(completed = research.completed + active.tech, active = null)
            }
        }

        val heal = Content.BASE_HEAL_PER_EPOCH +
            (if (c.has(FacilityType.INFIRMARY)) Content.INFIRMARY_HEAL_BONUS else 0) +
            (if ("field_medicine" in research.completed) Content.FIELD_MEDICINE_HEAL_BONUS else 0)
        for (i in roster.indices) {
            val sol = roster[i]
            if (sol.injuryEpochs > 0) {
                val left = maxOf(0, sol.injuryEpochs - heal)
                roster[i] = sol.copy(injuryEpochs = left, hp = if (left == 0) sol.stats.maxHp else sol.hp)
            }
        }
        return Upkept(c.copy(stock = stock, grid = grid, queue = queue, roster = roster, research = research), rng, ids)
    }

    /**
     * Dünya tiki: önce bekleyen saldırılar varır (düğümler sahipsiz kalır), sonra tehdit değişir,
     * en yüksek tehdide ulaşan bölge bir sonraki epoch için uyarı alır.
     */
    private fun tickWorld(world: World, epoch: Int, victories: List<String>, events: MutableList<GameEvent>): World {
        var w = world

        for (r in w.regions) {
            val threat = if (r.id in victories) {
                maxOf(Content.MIN_THREAT, r.threat - 1)
            } else {
                minOf(Content.MAX_THREAT, r.threat + 1)
            }
            w = w.withRegion(r.copy(threat = threat))
        }

        for (r in w.regions) {
            val pending = w.incursions.any { it.regionId == r.id }
            if (r.threat >= Content.MAX_THREAT && !pending) {
                w = w.copy(incursions = w.incursions + Incursion(r.id, epoch + 1))
                events += GameEvent.IncursionWarning(r.id, epoch + 1)
            }
        }
        return w
    }

    /** Aktif görev bitti: her katılımcının kadrosu için ölüm, yaralanma, XP, ödül; kalan görev varsa taktik sürer. */
    fun finishMission(s: GameState): GameStep.Ok {
        val m = s.missions.first()
        val winner = requireNotNull(m.skirmish.winner) { "mission not finished" }
        val won = winner == Side.PLAYER
        val survivors = m.skirmish.living(Side.PLAYER).associateBy { it.id }
        val events = mutableListOf<GameEvent>()
        var campuses = s.campuses

        for (owner in m.participants) {
            val c = campuses[owner]
            val roster = c.roster.toMutableList()
            val gone = mutableSetOf<String>()
            for (link in m.squads.filter { it.owner == owner }) {
                val idx = roster.indexOfFirst { it.id == link.soldierId }
                if (idx < 0) continue
                val sol = roster[idx]
                val combatant = survivors[link.combatantId]
                if (combatant == null) {
                    gone += sol.id
                    events += GameEvent.SoldierKilled(owner, sol.id)
                    continue
                }
                var updated = sol.copy(hp = combatant.hp, xp = sol.xp + if (won) 2 else 1)
                if (updated.hp <= Content.INJURY_HP_THRESHOLD) {
                    updated = updated.copy(injuryEpochs = Content.INJURY_EPOCHS)
                    events += GameEvent.SoldierInjured(owner, sol.id)
                }
                roster[idx] = updated
            }
            var stock = c.stock
            if (won) {
                stock += Resources(alloy = m.rewardAlloy)
                events += GameEvent.ResourcesProduced(owner, Resources(alloy = m.rewardAlloy))
            }
            events += GameEvent.MissionEnded(owner, won)
            campuses = campuses.with(owner, c.copy(stock = stock, roster = roster.filterNot { it.id in gone }))
        }

        val rest = s.missions.drop(1)
        var updated = s.copy(
            campuses = campuses,
            missions = rest,
            victories = if (won) s.victories + m.regionId else s.victories,
        )
        if (m.kind == MissionKind.DEFENSE && !won) {
            updated = loseRegion(updated, m.regionId, m.participants, events)
        }
        if (rest.isNotEmpty()) return GameStep.Ok(updated, events)
        val ended = endEpoch(updated.copy(phase = Phase.PLANNING))
        return GameStep.Ok(ended.state, events + ended.events)
    }

    private fun withCampus(s: GameState, actor: PlayerId, c: Campus): GameState =
        s.copy(campuses = s.campuses.with(actor, c))
}
