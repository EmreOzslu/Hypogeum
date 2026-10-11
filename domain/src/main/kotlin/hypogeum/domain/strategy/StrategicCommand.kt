package hypogeum.domain.strategy

import hypogeum.domain.campus.FacilityType
import hypogeum.domain.campus.Resources
import hypogeum.domain.roster.SoldierClass
import kotlinx.serialization.Serializable

/**
 * Planlama fazında toplanan stratejik komutlar. Aktör, komutu gönderen batch'ten gelir.
 * ClaimNode ve SendShipment paylaşılan aksiyonlardır: çakışmada seed'li sırada önce gelen kazanır / hakkı alır.
 */
@Serializable
sealed interface StrategicCommand {
    @Serializable data class Build(val slotIndex: Int, val type: FacilityType) : StrategicCommand
    @Serializable data class Recruit(val cls: SoldierClass) : StrategicCommand
    @Serializable data class Dispatch(val regionId: String, val soldierIds: List<String>) : StrategicCommand
    @Serializable data class ClaimNode(val nodeId: String) : StrategicCommand
    @Serializable data class StartResearch(val tech: String) : StrategicCommand
    @Serializable data class SendShipment(val resources: Resources, val soldierIds: List<String>) : StrategicCommand
}
