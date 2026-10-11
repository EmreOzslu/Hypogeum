package hypogeum.data.sync

import hypogeum.domain.core.PlayerId
import hypogeum.domain.game.ENGINE_VERSION
import hypogeum.domain.game.SCHEMA_VERSION
import kotlinx.serialization.Serializable
import java.util.TreeMap

/** Tel formatı. Data mesajları sıralıdır; Hello ve Resend kontrol mesajlarıdır. */
@Serializable
sealed interface WireMessage {
    @Serializable data class Data(val seq: Int, val payload: SyncMessage) : WireMessage
    /** Gönderenin son teslim edilen seq'i. Karşı taraf bundan sonrasını yeniden gönderir. */
    @Serializable data class Hello(
        val matchId: String,
        val seat: PlayerId,
        val engineVersion: String,
        val schemaVersion: Int,
        val lastDelivered: Int,
    ) : WireMessage
    /** Alıcı bir boşluk gördü: fromSeq'ten itibaren yeniden gönderilsin. */
    @Serializable data class Resend(val fromSeq: Int) : WireMessage
}

class IncompatibleVersionException(message: String) : RuntimeException(message)

/**
 * Sıralı, tek-teslim, yeniden iletimli kanal.
 * - Gönderen her mesajı seq ile numaralar ve tümünü saklar (outbox).
 * - Alıcı yalnızca beklenen seq'i verir, daha sonrakileri tamponlar, kopyaları atar.
 * - Boşluk görülünce Resend ister; yeniden bağlanmada Hello ile eksikler istenir.
 */
class ReliableChannel(
    private val seat: PlayerId,
    private val matchId: String,
    private val link: Link<WireMessage>,
    private val engineVersion: String = ENGINE_VERSION,
    private val schemaVersion: Int = SCHEMA_VERSION,
) : GameSync {
    private var nextSeq = 1
    private val outbox = mutableListOf<WireMessage.Data>()
    private var expected = 1
    private val buffered = TreeMap<Int, SyncMessage>()
    private var requestedGapAt: Int? = null
    private val delivered = mutableListOf<SyncMessage>()

    override fun send(message: SyncMessage) {
        val data = WireMessage.Data(nextSeq++, message)
        outbox += data
        link.send(data)
    }

    override fun poll(): List<SyncMessage> {
        for (w in link.poll()) handle(w)
        val out = delivered.toList()
        delivered.clear()
        return out
    }

    /** Kendi son teslim noktamızı bildirir; karşı taraf eksikleri yeniden gönderir. */
    fun hello() {
        link.send(WireMessage.Hello(matchId, seat, engineVersion, schemaVersion, lastDelivered = expected - 1))
    }

    private fun handle(w: WireMessage) {
        when (w) {
            is WireMessage.Hello -> {
                if (w.matchId != matchId || w.engineVersion != engineVersion || w.schemaVersion != schemaVersion) {
                    throw IncompatibleVersionException(
                        "peer ${w.seat} runs engine ${w.engineVersion}/schema ${w.schemaVersion}, local is $engineVersion/$schemaVersion",
                    )
                }
                resendFrom(w.lastDelivered + 1)
            }
            is WireMessage.Resend -> resendFrom(w.fromSeq)
            is WireMessage.Data -> onData(w)
        }
    }

    private fun onData(w: WireMessage.Data) {
        when {
            w.seq < expected -> return
            w.seq == expected -> {
                accept(w.payload)
                while (buffered.containsKey(expected)) accept(buffered.remove(expected)!!)
            }
            else -> {
                buffered[w.seq] = w.payload
                if (requestedGapAt != expected) {
                    requestedGapAt = expected
                    link.send(WireMessage.Resend(expected))
                }
            }
        }
    }

    private fun accept(payload: SyncMessage) {
        delivered += payload
        expected++
        requestedGapAt = null
    }

    private fun resendFrom(seq: Int) {
        for (d in outbox) if (d.seq >= seq) link.send(d)
    }
}
