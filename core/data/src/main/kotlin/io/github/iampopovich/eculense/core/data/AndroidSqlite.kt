package io.github.iampopovich.eculense.core.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.iampopovich.eculense.core.carscanner.SqliteOpener
import io.github.iampopovich.eculense.core.carscanner.SqliteSource
import java.io.File

/** SQLite from a Car Scanner backup via android.database.sqlite. We open a temporary copy, so READWRITE is harmless (WAL header). */
internal object AndroidSqlite : SqliteOpener {
    override fun open(file: File): SqliteSource {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        return object : SqliteSource {
            override fun rows(sql: String): List<List<Any?>> = db.rawQuery(sql, null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            (0 until c.columnCount).map { i ->
                                when (c.getType(i)) {
                                    Cursor.FIELD_TYPE_NULL -> null
                                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                    Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                    else -> c.getBlob(i)
                                }
                            },
                        )
                    }
                }
            }

            override fun close() = db.close()
        }
    }
}
