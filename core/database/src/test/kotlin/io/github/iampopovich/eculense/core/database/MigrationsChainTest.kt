package io.github.iampopovich.eculense.core.database

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** R2.2: the migration list is a gapless chain 1 → VERSION and every version has an exported schema. */
class MigrationsChainTest {
    @Test
    fun `migrations are contiguous and end at the database version`() {
        val chain = EcuDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals(1, chain.first().first)
        chain.forEach { (from, to) -> assertEquals("$from -> $to", from + 1, to) }
        chain.zipWithNext().forEach { (a, b) -> assertEquals("${a.second} vs ${b.first}", a.second, b.first) }
        assertEquals(EcuDatabase.VERSION, chain.last().second)
    }

    @Test
    fun `every version has an exported schema`() {
        val dir = listOf("schemas", "core/database/schemas")
            .map { File(it, EcuDatabase::class.java.name) }
            .first { it.isDirectory }
        (1..EcuDatabase.VERSION).forEach { v -> assertEquals("schema $v", true, File(dir, "$v.json").isFile) }
    }
}
