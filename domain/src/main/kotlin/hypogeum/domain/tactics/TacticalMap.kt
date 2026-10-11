package hypogeum.domain.tactics

import hypogeum.domain.core.Pos
import kotlinx.serialization.Serializable

@Serializable
enum class Terrain(
    val glyph: Char,
    val moveCost: Int,
    val blocksMove: Boolean,
    val blocksSight: Boolean,
    /** Bu karedeki birime yönelik isabet olasılığından düşülen değer (yüzde puan). */
    val coverBonus: Int,
) {
    FLOOR('.', 1, blocksMove = false, blocksSight = false, coverBonus = 0),
    RUBBLE('r', 2, blocksMove = false, blocksSight = false, coverBonus = 0),
    LOW_COVER('l', 1, blocksMove = false, blocksSight = false, coverBonus = 20),
    HIGH_COVER('h', 1, blocksMove = false, blocksSight = false, coverBonus = 40),
    WALL('#', 1, blocksMove = true, blocksSight = true, coverBonus = 0),
    ;

    companion object {
        fun fromGlyph(c: Char): Terrain = entries.firstOrNull { it.glyph == c }
            ?: throw IllegalArgumentException("Unknown terrain glyph '$c'")
    }
}

@Serializable
data class TacticalMap(val width: Int, val height: Int, val tiles: List<Terrain>) {
    init {
        require(tiles.size == width * height) { "tiles size must be width*height" }
    }

    fun inside(p: Pos): Boolean = p.x in 0 until width && p.y in 0 until height

    fun terrainAt(p: Pos): Terrain = tiles[index(p)]

    fun index(p: Pos): Int = p.y * width + p.x

    fun posOf(index: Int): Pos = Pos(index % width, index / width)

    companion object {
        /** Satırlar üstten alta; her karakter bir kare. */
        fun parse(rows: List<String>): TacticalMap {
            require(rows.isNotEmpty()) { "map needs at least one row" }
            val w = rows[0].length
            require(rows.all { it.length == w }) { "all rows must have equal length" }
            return TacticalMap(w, rows.size, rows.flatMap { row -> row.map(Terrain::fromGlyph) })
        }
    }
}
