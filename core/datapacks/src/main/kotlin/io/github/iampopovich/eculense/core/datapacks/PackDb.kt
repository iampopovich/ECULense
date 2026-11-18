package io.github.iampopovich.eculense.core.datapacks

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** Строка результата: колонки по индексу, null — NULL в базе. */
interface PackRow {
    fun string(i: Int): String?
    fun long(i: Int): Long?
    fun double(i: Int): Double?
}

/**
 * Read-only SQL к скачанному пакету (vehicles.sqlite, dtc_codes.db). Интерфейс, а не android.database напрямую:
 * модули-читатели тестируют запросы на JVM через sqlite-jdbc на синтетической базе с той же схемой.
 */
interface PackDb : Closeable {
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (PackRow) -> T): List<T>
}

/** Открывает файл пакета (только чтение, файл не меняется — важно для ODbL «без изменений»). */
fun interface PackDbOpener {
    fun open(file: File): PackDb
}

object AndroidPackDb : PackDbOpener {
    override fun open(file: File): PackDb {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        return object : PackDb {
            override fun <T> query(sql: String, args: List<Any?>, map: (PackRow) -> T): List<T> =
                db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
                    val row = CursorRow(c)
                    buildList { while (c.moveToNext()) add(map(row)) }
                }

            override fun close() = db.close()
        }
    }

    private class CursorRow(private val c: Cursor) : PackRow {
        override fun string(i: Int): String? = if (c.isNull(i)) null else c.getString(i)
        override fun long(i: Int): Long? = if (c.isNull(i)) null else c.getLong(i)
        override fun double(i: Int): Double? = if (c.isNull(i)) null else c.getDouble(i)
    }
}
