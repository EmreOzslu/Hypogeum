package hypogeum.data.sync

import hypogeum.domain.core.PlayerId
import hypogeum.domain.core.peer

/**
 * Oyun mesajları için ağ arayüzü. Uygulama, sıralama ve yeniden iletim sorumluluğunu taşır:
 * alıcı, mesajları gönderim sırasıyla ve her biri tam bir kez verir.
 */
interface GameSync {
    fun send(message: SyncMessage)
    fun poll(): List<SyncMessage>
}

/** Ham taşıma: mesaj gönderir ve gelenleri alır. Sıra, kayıp ve tekrar garantisi yoktur. */
interface Link<T> {
    fun send(message: T)
    fun poll(): List<T>
}

/** Aynı süreçteki iki koltuk arasında bellek içi taşıma. Gönderim anında teslim eder. */
open class LocalBus<T> {
    private val inbox = mapOf(
        PlayerId.P1 to ArrayDeque<T>(),
        PlayerId.P2 to ArrayDeque<T>(),
    )

    fun endpoint(seat: PlayerId): Link<T> = object : Link<T> {
        override fun send(message: T) = deliver(seat.peer(), message)

        override fun poll(): List<T> {
            val mine = inbox.getValue(seat)
            val out = mine.toList()
            mine.clear()
            return out
        }
    }

    /** Alt sınıflar kayıp/tekrar simüle edebilir. */
    protected open fun deliver(to: PlayerId, message: T) {
        inbox.getValue(to).addLast(message)
    }

    fun hasPending(): Boolean = inbox.values.any { it.isNotEmpty() }
}
