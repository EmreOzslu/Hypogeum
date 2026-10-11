package hypogeum.domain.content

import hypogeum.domain.campus.FacilityType
import hypogeum.domain.campus.Resources
import hypogeum.domain.roster.SoldierClass
import hypogeum.domain.world.ResourceKind

/**
 * Denge verisi. Kotlin kodu olarak tutulur (derleyici kontrolü, engineVersion ile sürümleme).
 */
object Content {
    const val GRID_COLS = 6
    const val GRID_ROWS = 4
    const val SLOTS = GRID_COLS * GRID_ROWS
    const val MAX_SQUAD = 3
    const val SOLDIER_RANGE = 6

    val START_STOCK = Resources(alloy = 8, cells = 4, biomass = 4)
    val RECRUIT_COST = Resources(cells = 1, biomass = 1)
    const val RECRUIT_EPOCHS = 2

    val CLAIM_COST = Resources(alloy = 2)
    const val MAX_THREAT = 3
    const val MIN_THREAT = 1
    const val MAX_ALIENS = 4

    data class NodeSpec(val id: String, val kind: ResourceKind, val yieldPerEpoch: Int)

    data class RegionSpec(val id: String, val name: String, val baseThreat: Int, val nodes: List<NodeSpec>) {
        /** Uzaylı sayısı: tehdit + katılımcı sayısı, en fazla MAX_ALIENS. */
        fun alienCount(threat: Int, participants: Int): Int = minOf(threat + participants, MAX_ALIENS)
        fun reward(threat: Int): Int = threat * 2
    }

    val REGIONS: List<RegionSpec> = listOf(
        RegionSpec("dock", "Liman Deposu", baseThreat = 1, nodes = listOf(NodeSpec("dock-alloy", ResourceKind.ALLOY, 1))),
        RegionSpec(
            "gate", "Eski Geçit", baseThreat = 2,
            nodes = listOf(
                NodeSpec("gate-cells", ResourceKind.CELLS, 1),
                NodeSpec("gate-bio", ResourceKind.BIOMASS, 1),
            ),
        ),
    )

    fun regionSpec(id: String): RegionSpec? = REGIONS.firstOrNull { it.id == id }

    data class FacilitySpec(val cost: Resources, val buildEpochs: Int, val output: Resources)

    fun facility(type: FacilityType): FacilitySpec = when (type) {
        FacilityType.REACTOR -> FacilitySpec(Resources(alloy = 2), buildEpochs = 2, output = Resources(cells = 2))
        FacilityType.FOUNDRY -> FacilitySpec(Resources(alloy = 1, cells = 1), buildEpochs = 2, output = Resources(alloy = 2))
        FacilityType.HYDROPONICS -> FacilitySpec(Resources(alloy = 1), buildEpochs = 1, output = Resources(biomass = 1))
        FacilityType.BARRACKS -> FacilitySpec(Resources(alloy = 2), buildEpochs = 2, output = Resources())
    }

    data class ClassSpec(val maxHp: Int, val aim: Int, val damage: Int)

    fun classSpec(cls: SoldierClass): ClassSpec = when (cls) {
        SoldierClass.RIFLEMAN -> ClassSpec(maxHp = 4, aim = 70, damage = 2)
        SoldierClass.HEAVY -> ClassSpec(maxHp = 5, aim = 60, damage = 3)
        SoldierClass.MEDIC -> ClassSpec(maxHp = 3, aim = 65, damage = 1)
    }

    val NAMES: List<String> = listOf("Vera", "Dorn", "Ilse", "Marek", "Sela", "Tomas", "Ada", "Kohl")

    /** Yaralanma süresi (epoch). Yaralıyken sevk edilemez. */
    const val INJURY_EPOCHS = 2
    /** Bu hp'nin altına düşen asker mission sonunda yaralanır. */
    const val INJURY_HP_THRESHOLD = 1
}
