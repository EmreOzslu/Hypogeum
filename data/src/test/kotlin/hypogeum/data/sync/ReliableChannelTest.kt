package hypogeum.data.sync

import hypogeum.domain.core.PlayerId
import hypogeum.domain.game.ENGINE_VERSION
import hypogeum.domain.game.GameEngine
import hypogeum.domain.strategy.StrategicCommand
import hypogeum.domain.tactics.SkirmishCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReliableChannelTest {
    private fun pair(bus: LocalBus<WireMessage>): Pair<ReliableChannel, ReliableChannel> = Pair(
        ReliableChannel(PlayerId.P1, "m", bus.endpoint(PlayerId.P1)),
        ReliableChannel(PlayerId.P2, "m", bus.endpoint(PlayerId.P2)),
    )

    private fun msg(i: Int): SyncMessage = SyncMessage.Digest("h$i")

    @Test
    fun duplicates_are_delivered_once_and_in_order() {
        val bus = UnreliableBus<WireMessage>(seed = 1, dropPercent = 0, dupPercent = 100)
        val (a, b) = pair(bus)
        repeat(5) { a.send(msg(it)) }
        val got = b.poll()
        assertEquals((0 until 5).map { msg(it) }, got)
    }

    @Test
    fun lost_messages_are_recovered_by_resync_in_order() {
        val bus = UnreliableBus<WireMessage>(seed = 7, dropPercent = 40, dupPercent = 20)
        val (a, b) = pair(bus)
        repeat(30) { a.send(msg(it)) }
        val got = mutableListOf<SyncMessage>()
        repeat(300) {
            a.poll()
            got += b.poll()
            if (got.size >= 30) return@repeat
            // Her iki taraf da kendi teslim noktasını bildirir; karşı taraf eksikleri gönderir.
            a.hello()
            b.hello()
        }
        assertEquals((0 until 30).map { msg(it) }, got)
        assertTrue(bus.dropped > 0, "the test should actually lose messages")
    }

    @Test
    fun a_reconnect_replays_everything_the_peer_missed() {
        val bus = UnreliableBus<WireMessage>(seed = 3, dropPercent = 0, dupPercent = 0)
        val (a, b) = pair(bus)
        a.send(msg(0))
        assertEquals(listOf(msg(0)), b.poll())
        bus.partitioned = true
        repeat(4) { a.send(msg(it + 1)) }
        assertEquals(emptyList(), b.poll())
        bus.partitioned = false
        b.hello()
        a.poll()
        assertEquals((1..4).map { msg(it) }, b.poll())
    }

    @Test
    fun a_peer_with_another_engine_version_is_refused() {
        val bus = LocalBus<WireMessage>()
        val a = ReliableChannel(PlayerId.P1, "m", bus.endpoint(PlayerId.P1), engineVersion = "9.9.9")
        val b = ReliableChannel(PlayerId.P2, "m", bus.endpoint(PlayerId.P2))
        a.hello()
        assertFailsWith<IncompatibleVersionException> { b.poll() }
        assertTrue(ENGINE_VERSION != "9.9.9")
    }

    @Test
    fun a_peer_of_another_match_is_refused() {
        val bus = LocalBus<WireMessage>()
        val a = ReliableChannel(PlayerId.P1, "other", bus.endpoint(PlayerId.P1))
        val b = ReliableChannel(PlayerId.P2, "m", bus.endpoint(PlayerId.P2))
        a.hello()
        assertFailsWith<IncompatibleVersionException> { b.poll() }
    }
}
