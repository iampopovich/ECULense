package io.github.iampopovich.eculense.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** JVM side of UiText: ViewModel tests compare ids and args, never phrases. */
class UiTextEqualityTest {
    @Test
    fun `res with the same id and args are equal`() {
        assertEquals(UiText.Res(1, listOf("a", 2)), UiText.Res(1, listOf("a", 2)))
        assertNotEquals(UiText.Res(1, listOf("a")), UiText.Res(1, listOf("b")))
    }

    @Test
    fun `plural passes the count as the default argument`() {
        assertEquals(listOf<Any>(3), UiText.Plural(5, 3).args)
    }
}
