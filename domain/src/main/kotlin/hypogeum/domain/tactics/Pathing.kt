package hypogeum.domain.tactics

import hypogeum.domain.core.Pos

/**
 * Bütçeli Dijkstra. Komşu sırası sabit (N, E, S, W) ve eşitlikte düşük indeks önce seçilir;
 * böylece sonuç her cihazda aynıdır.
 */
internal object Pathing {
    private val DIRS = arrayOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)

    /** from'dan budget'a kadar maliyetle ulaşılabilen kareler ve maliyetleri (indeks sırasıyla). */
    fun reachable(map: TacticalMap, from: Pos, blocked: Set<Pos>, budget: Int): Map<Pos, Int> {
        val n = map.width * map.height
        val dist = IntArray(n) { -1 }
        val done = BooleanArray(n)
        dist[map.index(from)] = 0
        while (true) {
            var best = -1
            for (i in 0 until n) {
                if (!done[i] && dist[i] >= 0 && (best < 0 || dist[i] < dist[best])) best = i
            }
            if (best < 0) break
            done[best] = true
            val p = map.posOf(best)
            for ((dx, dy) in DIRS) {
                val q = Pos(p.x + dx, p.y + dy)
                if (!map.inside(q) || q in blocked) continue
                val terrain = map.terrainAt(q)
                if (terrain.blocksMove) continue
                val nd = dist[best] + terrain.moveCost
                if (nd > budget) continue
                val qi = map.index(q)
                if (dist[qi] < 0 || nd < dist[qi]) dist[qi] = nd
            }
        }
        val out = LinkedHashMap<Pos, Int>()
        for (i in 0 until n) if (dist[i] >= 0) out[map.posOf(i)] = dist[i]
        return out
    }
}
