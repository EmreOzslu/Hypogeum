package hypogeum.domain.roster

import hypogeum.domain.content.Content
import hypogeum.domain.core.IdCounter
import hypogeum.domain.core.Rng
import hypogeum.domain.core.RngState
import kotlinx.serialization.Serializable

@Serializable
enum class SoldierClass { RIFLEMAN, HEAVY, MEDIC }

@Serializable
data class Stats(val maxHp: Int, val aim: Int, val damage: Int)

@Serializable
data class Soldier(
    val id: String,
    val name: String,
    val cls: SoldierClass,
    val level: Int,
    val xp: Int,
    val stats: Stats,
    val hp: Int,
    val injuryEpochs: Int,
)

data class NewSoldier(val soldier: Soldier, val rng: RngState, val ids: IdCounter)

/** Asker üretimi: isim ve nişan sapması seed'li RNG'den, ID sayaçtan. */
object SoldierFactory {
    fun make(rng: RngState, ids: IdCounter, cls: SoldierClass): NewSoldier {
        val base = Content.classSpec(cls)
        val (id, ids2) = ids.issue("soldier")
        val (nameIdx, r1) = Rng.nextInt(rng, Content.NAMES.size).let { it.value to it.next }
        val (aimRoll, r2) = Rng.nextInt(r1, 10).let { it.value to it.next }
        val soldier = Soldier(
            id = id,
            name = Content.NAMES[nameIdx],
            cls = cls,
            level = 1,
            xp = 0,
            stats = Stats(maxHp = base.maxHp, aim = base.aim + aimRoll - 4, damage = base.damage),
            hp = base.maxHp,
            injuryEpochs = 0,
        )
        return NewSoldier(soldier, r2, ids2)
    }
}
