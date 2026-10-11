package hypogeum.data.sync

import hypogeum.domain.core.PlayerId
import hypogeum.domain.core.peer

/**
 * Ağ arayüzü. M3'te senkron (poll) model; FirebaseSync (M6) aynı sözleşmeyi Flow ile sunacak.
 * Sıralama sağlanır: bir gönderenin mesajları gönderim sırasıyla gelir.
 */
interface GameSync {
    fun send(message: SyncMessage)
    fun poll(): List<SyncMessage>
}

/** Aynı süreçteki iki koltuk arasında bellek içi taşıma. */
class LocalHotseatSync {
    private val inbox = mapOf(
        PlayerId.P1 to ArrayDeque<SyncMessage>(),
        PlayerId.P2 to ArrayDeque<SyncMessage>(),
    )

    fun endpoint(seat: PlayerId): GameSync = object : GameSync {
        override fun send(message: SyncMessage) {
            inbox.getValue(seat.peer()).addLast(message)
        }

        override fun poll(): List<SyncMessage> {
            val mine = inbox.getValue(seat)
            val out = mine.toList()
            mine.clear()
            return out
        }
    }

    fun hasPending(): Boolean = inbox.values.any { it.isNotEmpty() }
}
