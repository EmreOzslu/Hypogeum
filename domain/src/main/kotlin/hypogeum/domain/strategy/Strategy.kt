package hypogeum.domain.strategy

import hypogeum.domain.campus.Campus
import hypogeum.domain.campus.Facility
import hypogeum.domain.campus.ProductionOrder
import hypogeum.domain.campus.Resources
import hypogeum.domain.content.Content
import hypogeum.domain.core.IdCounter
import hypogeum.domain.core.RngState
import hypogeum.domain.campus.FacilityType
import hypogeum.domain.game.GameEvent
import hypogeum.domain.game.GameState
import hypogeum.domain.game.GameStep
import hypogeum.domain.game.Mission
import hypogeum.domain.game.Phase
import hypogeum.domain.game.SquadLink
import hypogeum.domain.roster.Soldier
import hypogeum.domain.roster.SoldierFactory
import hypogeum.domain.tactics.Combatant
import hypogeum.domain.tactics.PLAYER_AP
import hypogeum.domain.tactics.Side
import hypogeum.domain.tactics.Skirmish
import hypogeum.domain.core.Rng

/** Stratejik kurallar: komut doğrulama, sevk, epoch sonu üretimi ve mission sonucu. */
internal object Strategy {
    fun apply(s: GameState, cmd: StrategicCommand): GameStep = when (cmd) {
        is StrategicCommand.Build -> build(s, cmd)
        is StrategicCommand.Recruit -> recruit(s, cmd)
        is StrategicCommand.Dispatch -> dispatch(s, cmd)
    }

    private fun build(s: GameState, cmd: StrategicCommand.Build): GameStep {
        val c = s.campus
        if (cmd.slotIndex !in c.grid.indices) return GameStep.Rejected("slot out of range")
        if (c.grid[cmd.slotIndex] != null || c.queuedBuildAt(cmd.slotIndex)) return GameStep.Rejected("slot occupied")
        val spec = Content.facility(cmd.type)
        if (!c.stock.covers(spec.cost)) return GameStep.Rejected("not enough resources for ${cmd.type}")
        val campus = c.copy(
            stock = c.stock - spec.cost,
            queue = c.queue + ProductionOrder.Build(cmd.slotIndex, cmd.type, spec.buildEpochs),
        )
        return GameStep.Ok(s.copy(campus = campus), listOf(GameEvent.FacilityQueued(cmd.slotIndex, cmd.type)))
    }

    private fun recruit(s: GameState, cmd: StrategicCommand.Recruit): GameStep {
        val c = s.campus
        val barracks = c.built(FacilityType.BARRACKS)
        if (barracks == 0) return GameStep.Rejected("build a barracks first")
        if (c.trainCount() >= barracks) return GameStep.Rejected("all barracks are busy")
        if (!c.stock.covers(Content.RECRUIT_COST)) return GameStep.Rejected("not enough resources to recruit")
        val campus = c.copy(
            stock = c.stock - Content.RECRUIT_COST,
            queue = c.queue + ProductionOrder.Train(cmd.cls, Content.RECRUIT_EPOCHS),
        )
        return GameStep.Ok(s.copy(campus = campus), listOf(GameEvent.RecruitQueued(cmd.cls)))
    }

    private fun dispatch(s: GameState, cmd: StrategicCommand.Dispatch): GameStep {
        val c = s.campus
        val ids = cmd.soldierIds
        if (ids.isEmpty() || ids.size > Content.MAX_SQUAD) return GameStep.Rejected("squad size must be 1..${Content.MAX_SQUAD}")
        if (ids.distinct().size != ids.size) return GameStep.Rejected("duplicate soldier in squad")
        val region = Content.region(cmd.regionId) ?: return GameStep.Rejected("unknown region ${cmd.regionId}")
        val squad = ids.map { id -> c.soldier(id) ?: return GameStep.Rejected("no soldier $id") }
        squad.firstOrNull { it.injuryEpochs > 0 }?.let { return GameStep.Rejected("${it.id} is injured") }

        val (seed, rng2) = Rng.nextLong(s.rng)
        val links = squad.mapIndexed { i, sol -> SquadLink("p${i + 1}", sol.id) }
        val players = squad.mapIndexed { i, sol ->
            Skirmish.squadmate(
                id = "p${i + 1}",
                pos = Skirmish.DEPLOY_PLAYER[i],
                hp = sol.hp,
                aim = sol.stats.aim,
                damage = sol.stats.damage,
            )
        }
        val aliens = (0 until region.alienCount).map { i -> Skirmish.alien("a${i + 1}", Skirmish.DEPLOY_ALIEN[i]) }
        val mission = Mission(
            regionId = region.id,
            squad = links,
            rewardAlloy = region.rewardAlloy,
            skirmish = Skirmish.start(seed, Skirmish.defaultMap(), players + aliens),
        )
        val next = s.copy(rng = rng2, mission = mission, phase = Phase.TACTICAL)
        return GameStep.Ok(next, listOf(GameEvent.SquadDispatched(region.id, squad.size)))
    }

    /** Epoch sonu: üretim, kuyruk ilerlemesi, yaralı iyileşmesi, sonra sıradaki epoch. */
    fun endEpoch(s: GameState): GameStep.Ok {
        val events = mutableListOf<GameEvent>()
        var c = s.campus
        var rng: RngState = s.rng
        var ids: IdCounter = s.ids

        var gain = Resources()
        for (f in c.grid) if (f != null) gain += Content.facility(f.type).output
        c = c.copy(stock = c.stock + gain)
        if (gain != Resources()) events += GameEvent.ResourcesProduced(gain)

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
                    events += GameEvent.FacilityBuilt(order.slotIndex, order.type)
                }
                is ProductionOrder.Train -> {
                    val made = SoldierFactory.make(rng, ids, order.cls)
                    rng = made.rng
                    ids = made.ids
                    roster += made.soldier
                    events += GameEvent.SoldierRecruited(made.soldier.id, made.soldier.name)
                }
            }
        }

        for (i in roster.indices) {
            val sol = roster[i]
            if (sol.injuryEpochs > 0) {
                val left = sol.injuryEpochs - 1
                roster[i] = sol.copy(
                    injuryEpochs = left,
                    hp = if (left == 0) sol.stats.maxHp else sol.hp,
                )
            }
        }

        val next = s.copy(
            epoch = s.epoch + 1,
            phase = Phase.PLANNING,
            campus = c.copy(grid = grid, queue = queue, roster = roster),
            rng = rng,
            ids = ids,
        )
        events += GameEvent.EpochStarted(next.epoch)
        return GameStep.Ok(next, events)
    }

    /** Mission bitti: ölüm, yaralanma, XP, ödül; sonra epoch sonu. */
    fun finishMission(s: GameState): GameStep.Ok {
        val m = requireNotNull(s.mission) { "no mission to finish" }
        val sk = requireNotNull(m.skirmish.winner) { "mission not finished" }
        val won = sk == Side.PLAYER
        val survivors = m.skirmish.living(Side.PLAYER).associateBy { it.id }
        val events = mutableListOf<GameEvent>()
        val roster = s.campus.roster.toMutableList()
        val gone = mutableSetOf<String>()

        for (link in m.squad) {
            val idx = roster.indexOfFirst { it.id == link.soldierId }
            if (idx < 0) continue
            val sol = roster[idx]
            val combatant = survivors[link.combatantId]
            if (combatant == null) {
                gone += sol.id
                events += GameEvent.SoldierKilled(sol.id)
                continue
            }
            var updated = sol.copy(hp = combatant.hp, xp = sol.xp + if (won) 2 else 1)
            if (updated.hp <= Content.INJURY_HP_THRESHOLD) {
                updated = updated.copy(injuryEpochs = Content.INJURY_EPOCHS)
                events += GameEvent.SoldierInjured(sol.id)
            }
            roster[idx] = updated
        }

        var stock = s.campus.stock
        if (won) {
            stock += Resources(alloy = m.rewardAlloy)
            events += GameEvent.ResourcesProduced(Resources(alloy = m.rewardAlloy))
        }
        events += GameEvent.MissionEnded(won)

        val cleared = s.copy(
            campus = s.campus.copy(stock = stock, roster = roster.filterNot { it.id in gone }),
            mission = null,
        )
        val ended = endEpoch(cleared)
        return GameStep.Ok(ended.state, events + ended.events)
    }
}
