package hypogeum.domain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RngTest {
    @Test
    fun splitmix64_matches_reference_vector_for_seed_zero() {
        // Referans SplitMix64 çıktıları (seed = 0), bağımsız kaynaktan.
        val expected = listOf(0xE220A8397B1DCDAFuL, 0x6E789E6AA1B965F4uL, 0x06C45D188009454FuL)
        var state = RngState(0)
        for (want in expected) {
            val draw = Rng.nextLong(state)
            assertEquals(want, draw.value.toULong())
            state = draw.next
        }
    }

    @Test
    fun same_seed_same_sequence() {
        var a = RngState(42)
        var b = RngState(42)
        repeat(1_000) {
            val da = Rng.nextInt(a, 97)
            val db = Rng.nextInt(b, 97)
            assertEquals(da.value, db.value)
            a = da.next
            b = db.next
        }
    }

    @Test
    fun nextInt_stays_in_bounds() {
        var s = RngState(7)
        repeat(10_000) {
            val d = Rng.nextInt(s, 6)
            assertTrue(d.value in 0 until 6)
            s = d.next
        }
    }

    @Test
    fun rejects_non_positive_bound() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { Rng.nextInt(RngState(1), 0) }
    }

    @Test
    fun id_counter_is_sequential_and_pure() {
        val (first, c1) = IdCounter().issue("soldier")
        val (second, _) = c1.issue("soldier")
        assertEquals("soldier-1", first)
        assertEquals("soldier-2", second)
    }
}
