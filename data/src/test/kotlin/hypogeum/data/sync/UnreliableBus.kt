package hypogeum.data.sync

import hypogeum.domain.core.PlayerId
import kotlin.random.Random

/** Test taşıması: deterministik kayıp, tekrar ve kopma (partition) simülasyonu. */
class UnreliableBus<T>(seed: Long, private val dropPercent: Int, private val dupPercent: Int) : LocalBus<T>() {
    private val rnd = Random(seed)
    var partitioned: Boolean = false
    var dropped: Int = 0
        private set

    override fun deliver(to: PlayerId, message: T) {
        if (partitioned || rnd.nextInt(100) < dropPercent) {
            dropped++
            return
        }
        super.deliver(to, message)
        if (rnd.nextInt(100) < dupPercent) super.deliver(to, message)
    }
}
