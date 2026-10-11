package hypogeum.domain.game

import hypogeum.domain.campus.Campus
import hypogeum.domain.campus.FacilityType
import hypogeum.domain.campus.Resources
import hypogeum.domain.content.Content
import hypogeum.domain.core.IdCounter
import hypogeum.domain.core.PerPlayer
import hypogeum.domain.core.PlayerId
import hypogeum.domain.core.Rng
import hypogeum.domain.core.RngState
import hypogeum.domain.roster.SoldierClass
import hypogeum.domain.roster.Soldier
import hypogeum.domain.roster.SoldierFactory
import hypogeum.domain.strategy.Strategy
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.Skirmish
import hypogeum.domain.tactics.SkirmishCommand
import hypogeum.domain.tactics.SkirmishEvent
import hypogeum.domain.tactics.SkirmishState
import hypogeum.domain.tactics.StepResult
import hypogeum.domain.world.TransitLink
import hypogeum.domain.world.World
import hypogeum.domain.world.WorldRegion
import hypogeum.domain.world.ResourceNode
import kotlinx.serialization.Serializable

/** Denge/kural sürümü. Denge veya şema değişince artırılır. */
const val ENGINE_VERSION = "0.5.0"
const val SCHEMA_VERSION = 5

@Serializable
enum class Phase { PLANNING, TACTICAL }

@Serializable
data class SquadLink(val owner: PlayerId, val combatantId: String, val soldierId: String)

/**
 * Bir bölgeye giden ortak görev. Aynı epoch'ta aynı bölgeye sevk eden oyuncuların squad'ları tek görevde birleşir.
 * rewardAlloy her kazanan katılımcıya ayrı ayrı ödenir. missions listesinde ilk eleman aktif olandır.
 */
@Serializable
data class Mission(
    val participants: List<PlayerId>,
    val regionId: String,
    val squads: List<SquadLink>,
    val rewardAlloy: Int,
    val skirmish: SkirmishState,
)

/** Hat üzerindeki sevkiyat. delivered: taşıma kaybı düşüldükten sonra varan kaynak. */
@Serializable
data class Shipment(
    val id: String,
    val from: PlayerId,
    val to: PlayerId,
    val delivered: Resources,
    val soldiers: List<Soldier>,
    val arrivalEpoch: Int,
)

/** Planlama çözümü sırasında bekleyen sevk. Çözüm bitince görevlere dönüşür ve liste boşalır. */
@Serializable
data class PendingDispatch(val owner: PlayerId, val regionId: String, val soldierIds: List<String>)

@Serializable
data class GameState(
    val schemaVersion: Int,
    val engineVersion: String,
    val matchId: String,
    val seed: Long,
    val rng: RngState,
    val ids: IdCounter,
    val epoch: Int,
    val phase: Phase,
    val campuses: PerPlayer<Campus>,
    val world: World,
    val missions: List<Mission>,
    val pendingDispatches: List<PendingDispatch>,
    val shipments: List<Shipment>,
    /** Bu epoch'ta kazanılan bölgeler (tehdit düşürür). Epoch sonunda temizlenir. */
    val victories: List<String>,
)

sealed interface GameEvent {
    data class CommandRejected(val actor: PlayerId, val reason: String) : GameEvent
    data class NodeClaimed(val owner: PlayerId, val nodeId: String) : GameEvent
    data class FacilityQueued(val owner: PlayerId, val slot: Int, val type: FacilityType) : GameEvent
    data class FacilityBuilt(val owner: PlayerId, val slot: Int, val type: FacilityType) : GameEvent
    data class RecruitQueued(val owner: PlayerId, val cls: SoldierClass) : GameEvent
    data class SoldierRecruited(val owner: PlayerId, val soldierId: String, val name: String) : GameEvent
    data class ResourcesProduced(val owner: PlayerId, val amount: Resources) : GameEvent
    data class SquadDispatched(val owner: PlayerId, val regionId: String, val size: Int) : GameEvent
    data class MissionLaunched(val regionId: String, val participants: List<PlayerId>) : GameEvent
    data class Tactical(val owner: PlayerId, val event: SkirmishEvent) : GameEvent
    data class SoldierKilled(val owner: PlayerId, val soldierId: String) : GameEvent
    data class SoldierInjured(val owner: PlayerId, val soldierId: String) : GameEvent
    data class MissionEnded(val owner: PlayerId, val won: Boolean) : GameEvent
    data class ShipmentSent(val from: PlayerId, val to: PlayerId, val delivered: Resources, val soldierCount: Int, val arrivalEpoch: Int) : GameEvent
    data class ShipmentArrived(val to: PlayerId, val delivered: Resources, val soldierCount: Int) : GameEvent
    data class ResearchStarted(val owner: PlayerId, val tech: String) : GameEvent
    data class ResearchCompleted(val owner: PlayerId, val tech: String) : GameEvent
    data class IncursionWarning(val regionId: String, val arrivalEpoch: Int) : GameEvent
    data class IncursionArrived(val regionId: String) : GameEvent
    data class EpochStarted(val epoch: Int) : GameEvent
}

sealed interface GameStep {
    data class Ok(val state: GameState, val events: List<GameEvent>) : GameStep
    data class Rejected(val reason: String) : GameStep
}

object GameEngine {
    fun newGame(matchId: String, seed: Long): GameState {
        val first = startingRoster(RngState(seed), IdCounter())
        val second = startingRoster(first.rng, first.ids)
        val rosters = PerPlayer(first.soldiers, second.soldiers)
        val world = World(
            regions = Content.REGIONS.map { spec ->
                WorldRegion(
                    id = spec.id,
                    name = spec.name,
                    baseThreat = spec.baseThreat,
                    threat = spec.baseThreat,
                    nodes = spec.nodes.map { ResourceNode(it.id, it.kind, it.yieldPerEpoch, claimedBy = null) },
                )
            },
            incursions = emptyList(),
            transit = TransitLink(Content.TRANSIT_EPOCHS, Content.TRANSIT_CAPACITY, usedThisEpoch = 0),
        )
        return GameState(
            schemaVersion = SCHEMA_VERSION,
            engineVersion = ENGINE_VERSION,
            matchId = matchId,
            seed = seed,
            rng = second.rng,
            ids = second.ids,
            epoch = 1,
            phase = Phase.PLANNING,
            campuses = rosters.map { Campus.empty(it) },
            world = world,
            missions = emptyList(),
            pendingDispatches = emptyList(),
            shipments = emptyList(),
            victories = emptyList(),
        )
    }

    /**
     * Her iki oyuncunun planlama batch'ini çözer. Sıra (kanonik, deterministik):
     *  1. ClaimNode komutları, seed'li coin flip ile belirlenen sırada (çakışmada ilk gelen kazanır),
     *  2. Kampüse özel komutlar: P1 batch'i, sonra P2 batch'i (kendi batch'i içinde gönderim sırasıyla),
     *  3. Sevk varsa taktik faz, yoksa epoch sonu.
     * Reddedilen komut olay olarak kaydedilir; kaynak yalnızca kabul edilende harcanır.
     */
    fun resolveEpoch(state: GameState, batches: PerPlayer<List<StrategicCommand>>): GameStep {
        if (state.phase != Phase.PLANNING) return GameStep.Rejected("not in planning phase")
        val (flip, rng) = Rng.nextBoolean(state.rng)
        var s = state.copy(rng = rng)
        val events = mutableListOf<GameEvent>()
        val contestOrder = if (flip) listOf(PlayerId.P1, PlayerId.P2) else listOf(PlayerId.P2, PlayerId.P1)

        for (actor in contestOrder) {
            for (cmd in batches[actor]) {
                if (isShared(cmd)) s = applyOne(s, actor, cmd, events)
            }
        }
        for (actor in listOf(PlayerId.P1, PlayerId.P2)) {
            for (cmd in batches[actor]) {
                if (!isShared(cmd)) s = applyOne(s, actor, cmd, events)
            }
        }

        if (s.pendingDispatches.isNotEmpty()) {
            val launched = Strategy.launchMissions(s)
            launched.missions.forEach { events += GameEvent.MissionLaunched(it.regionId, it.participants) }
            return GameStep.Ok(launched.copy(phase = Phase.TACTICAL), events)
        }
        val ended = Strategy.endEpoch(s)
        return GameStep.Ok(ended.state, events + ended.events)
    }

    /** Aktif görevin taktik komutu. Yalnızca o anki faz sahibi (actor) komut verebilir. */
    fun tacticalCommand(state: GameState, actor: PlayerId, cmd: SkirmishCommand): GameStep {
        val m = state.missions.firstOrNull() ?: return GameStep.Rejected("no mission in progress")
        if (actor !in m.participants) return GameStep.Rejected("not part of this mission")
        if (m.skirmish.activeOwner != actor) return GameStep.Rejected("not your turn")
        return when (val r = Skirmish.apply(m.skirmish, cmd)) {
            is StepResult.Rejected -> GameStep.Rejected(r.reason)
            is StepResult.Ok -> {
                val events = r.events.map { GameEvent.Tactical(actor, it) }
                val next = state.copy(missions = listOf(m.copy(skirmish = r.state)) + state.missions.drop(1))
                if (r.state.winner == null) return GameStep.Ok(next, events)
                val finished = Strategy.finishMission(next)
                GameStep.Ok(finished.state, events + finished.events)
            }
        }
    }

    /** Paylaşılan aksiyonlar: düğüm talebi ve hat kapasitesi. Seed'li sırada çözülür. */
    private fun isShared(cmd: StrategicCommand): Boolean =
        cmd is StrategicCommand.ClaimNode || cmd is StrategicCommand.SendShipment

    private fun applyOne(s: GameState, actor: PlayerId, cmd: StrategicCommand, events: MutableList<GameEvent>): GameState =
        when (val r = Strategy.apply(s, actor, cmd)) {
            is GameStep.Ok -> {
                events += r.events
                r.state
            }
            is GameStep.Rejected -> {
                events += GameEvent.CommandRejected(actor, r.reason)
                s
            }
        }

    private fun startingRoster(rng0: RngState, ids0: IdCounter): RosterResult {
        var rng = rng0
        var ids = ids0
        val soldiers = listOf(SoldierClass.RIFLEMAN, SoldierClass.HEAVY).map { cls ->
            val made = SoldierFactory.make(rng, ids, cls)
            rng = made.rng
            ids = made.ids
            made.soldier
        }
        return RosterResult(soldiers, rng, ids)
    }

    private data class RosterResult(
        val soldiers: List<Soldier>,
        val rng: RngState,
        val ids: IdCounter,
    )
}
