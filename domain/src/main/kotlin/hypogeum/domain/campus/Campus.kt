package hypogeum.domain.campus

import hypogeum.domain.content.Content
import hypogeum.domain.roster.Soldier
import hypogeum.domain.roster.SoldierClass
import kotlinx.serialization.Serializable

@Serializable
enum class FacilityType { REACTOR, FOUNDRY, HYDROPONICS, BARRACKS }

@Serializable
data class Resources(val alloy: Int = 0, val cells: Int = 0, val biomass: Int = 0) {
    operator fun plus(o: Resources) = Resources(alloy + o.alloy, cells + o.cells, biomass + o.biomass)
    operator fun minus(o: Resources) = Resources(alloy - o.alloy, cells - o.cells, biomass - o.biomass)
    fun covers(cost: Resources) = alloy >= cost.alloy && cells >= cost.cells && biomass >= cost.biomass
}

@Serializable
data class Facility(val type: FacilityType)

@Serializable
sealed interface ProductionOrder {
    val remainingEpochs: Int

    @Serializable
    data class Build(val slotIndex: Int, val type: FacilityType, override val remainingEpochs: Int) : ProductionOrder

    @Serializable
    data class Train(val cls: SoldierClass, override val remainingEpochs: Int) : ProductionOrder
}

/** Kampüs: sabit boyutlu ızgara, özel stok, asker listesi ve üretim kuyruğu. */
@Serializable
data class Campus(
    val grid: List<Facility?>,
    val stock: Resources,
    val roster: List<Soldier>,
    val queue: List<ProductionOrder>,
) {
    init {
        require(grid.size == Content.SLOTS) { "grid must have ${Content.SLOTS} slots" }
    }

    fun built(type: FacilityType): Int = grid.count { it?.type == type }

    fun queuedBuildAt(slot: Int): Boolean =
        queue.any { it is ProductionOrder.Build && it.slotIndex == slot }

    fun trainCount(): Int = queue.count { it is ProductionOrder.Train }

    fun soldier(id: String): Soldier? = roster.firstOrNull { it.id == id }

    companion object {
        fun empty(roster: List<Soldier>): Campus = Campus(
            grid = List(Content.SLOTS) { null },
            stock = Content.START_STOCK,
            roster = roster,
            queue = emptyList(),
        )
    }
}
