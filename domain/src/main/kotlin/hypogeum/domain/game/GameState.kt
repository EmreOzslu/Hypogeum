package hypogeum.domain.game

import hypogeum.domain.campus.Campus
import hypogeum.domain.campus.FacilityType
import hypogeum.domain.campus.Resources
import hypogeum.domain.content.Content
import hypogeum.domain.core.IdCounter
import hypogeum.domain.core.RngState
import hypogeum.domain.roster.SoldierClass
import hypogeum.domain.roster.SoldierFactory
import hypogeum.domain.strategy.Strategy
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.SkirmishCommand
import hypogeum.domain.tactics.SkirmishEvent
import hypogeum.domain.tactics.SkirmishState
import hypogeum.domain.tactics.Skirmish
import hypogeum.domain.tactics.StepResult
import kotlinx.serialization.Serializable

/** Denge/kural sürümü. Denge veya şema değişince artırılır. */
const val ENGINE_VERSION = "0.2.0"
const val SCHEMA_VERSION = 2

@Serializable
enum class Phase { PLANNING, TACTICAL }

@Serializable
data class SquadLink(val combatantId: String, val soldierId: String)

@Serializable
data class Mission(
    val regionId: String,
    val squad: List<SquadLink>,
    val rewardAlloy: Int,
    val skirmish: SkirmishState,
)

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
    val campus: Campus,
    val mission: Mission?,
)

sealed interface GameEvent {
    data class CommandRejected(val reason: String) : GameEvent
    data class FacilityQueued(val slot: Int, val type: FacilityType) : GameEvent
    data class FacilityBuilt(val slot: Int, val type: FacilityType) : GameEvent
    data class RecruitQueued(val cls: SoldierClass) : GameEvent
    data class SoldierRecruited(val soldierId: String, val name: String) : GameEvent
    data class ResourcesProduced(val amount: Resources) : GameEvent
    data class SquadDispatched(val regionId: String, val size: Int) : GameEvent
    data class Tactical(val event: SkirmishEvent) : GameEvent
    data class SoldierKilled(val soldierId: String) : GameEvent
    data class SoldierInjured(val soldierId: String) : GameEvent
    data class MissionEnded(val won: Boolean) : GameEvent
    data class EpochStarted(val epoch: Int) : GameEvent
}

sealed interface GameStep {
    data class Ok(val state: GameState, val events: List<GameEvent>) : GameStep
    data class Rejected(val reason: String) : GameStep
}

object GameEngine {
    fun newGame(matchId: String, seed: Long): GameState {
        var rng = RngState(seed)
        var ids = IdCounter()
        val roster = listOf(SoldierClass.RIFLEMAN, SoldierClass.HEAVY).map { cls ->
            val made = SoldierFactory.make(rng, ids, cls)
            rng = made.rng
            ids = made.ids
            made.soldier
        }
        return GameState(
            schemaVersion = SCHEMA_VERSION,
            engineVersion = ENGINE_VERSION,
            matchId = matchId,
            seed = seed,
            rng = rng,
            ids = ids,
            epoch = 1,
            phase = Phase.PLANNING,
            campus = Campus.empty(roster),
            mission = null,
        )
    }

    /**
     * Planlama batch'ini sırayla uygular. Reddedilen komut olay olarak kaydedilir, batch'i durdurmaz.
     * Sevk yoksa epoch hemen biter; sevk varsa taktik faz başlar.
     */
    fun commit(state: GameState, batch: List<StrategicCommand>): GameStep {
        if (state.phase != Phase.PLANNING) return GameStep.Rejected("not in planning phase")
        var s = state
        val events = mutableListOf<GameEvent>()
        for (cmd in batch) {
            if (s.mission != null) {
                events += GameEvent.CommandRejected("mission in progress")
                continue
            }
            when (val r = Strategy.apply(s, cmd)) {
                is GameStep.Ok -> {
                    s = r.state
                    events += r.events
                }
                is GameStep.Rejected -> events += GameEvent.CommandRejected(r.reason)
            }
        }
        if (s.mission != null) return GameStep.Ok(s, events)
        val ended = Strategy.endEpoch(s)
        return GameStep.Ok(ended.state, events + ended.events)
    }

    /** Taktik faz komutu: skirmish kurallarına devredilir; mission bitince sonuç uygulanır. */
    fun tacticalCommand(state: GameState, cmd: SkirmishCommand): GameStep {
        val m = state.mission ?: return GameStep.Rejected("no mission in progress")
        return when (val r = Skirmish.apply(m.skirmish, cmd)) {
            is StepResult.Rejected -> GameStep.Rejected(r.reason)
            is StepResult.Ok -> {
                val events = r.events.map { GameEvent.Tactical(it) }
                val next = state.copy(mission = m.copy(skirmish = r.state))
                if (r.state.winner == null) return GameStep.Ok(next, events)
                val finished = Strategy.finishMission(next)
                GameStep.Ok(finished.state, events + finished.events)
            }
        }
    }
}
