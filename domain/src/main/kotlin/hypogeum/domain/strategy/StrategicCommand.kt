package hypogeum.domain.strategy

import hypogeum.domain.campus.FacilityType
import hypogeum.domain.roster.SoldierClass
import kotlinx.serialization.Serializable

/** Planlama fazında toplanan, commit'te sırayla uygulanan stratejik komutlar. */
@Serializable
sealed interface StrategicCommand {
    @Serializable data class Build(val slotIndex: Int, val type: FacilityType) : StrategicCommand
    @Serializable data class Recruit(val cls: SoldierClass) : StrategicCommand
    @Serializable data class Dispatch(val regionId: String, val soldierIds: List<String>) : StrategicCommand
}
