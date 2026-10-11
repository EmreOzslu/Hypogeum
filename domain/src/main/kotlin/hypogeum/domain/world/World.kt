package hypogeum.domain.world

import hypogeum.domain.core.PlayerId
import kotlinx.serialization.Serializable

@Serializable
enum class ResourceKind { ALLOY, CELLS, BIOMASS }

/** Bölgedeki kaynak düğümü. Sahibi olan kampüse her epoch verim verir. */
@Serializable
data class ResourceNode(
    val id: String,
    val kind: ResourceKind,
    val yieldPerEpoch: Int,
    val claimedBy: PlayerId?,
)

@Serializable
data class WorldRegion(
    val id: String,
    val name: String,
    val baseThreat: Int,
    val threat: Int,
    val nodes: List<ResourceNode>,
)

/** Uyarı: bölgeye arrivalEpoch'ta saldırı gelecek. Bir epoch önce görünür. */
@Serializable
data class Incursion(val regionId: String, val arrivalEpoch: Int)

/** İki oyuncu için ortak dünya: bölgeler, tehdit seviyeleri ve bekleyen saldırılar. */
@Serializable
data class World(
    val regions: List<WorldRegion>,
    val incursions: List<Incursion>,
) {
    fun region(id: String): WorldRegion? = regions.firstOrNull { it.id == id }

    fun findNode(nodeId: String): Pair<WorldRegion, ResourceNode>? {
        for (r in regions) {
            val n = r.nodes.firstOrNull { it.id == nodeId }
            if (n != null) return r to n
        }
        return null
    }

    fun withRegion(updated: WorldRegion): World =
        copy(regions = regions.map { if (it.id == updated.id) updated else it })
}
