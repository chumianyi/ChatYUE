package com.chatyue.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 快捷指令本地存储（独立数据库，与聊天数据解耦）。
 */
class LocalPromptRepository(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION),
    PromptRepository {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PROMPTS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_USAGE_COUNT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 预留迁移位：后续版本在此按 oldVersion 增量升级。
    }

    override fun list(): List<Prompt> {
        return readableDatabase.query(
            TABLE_PROMPTS,
            arrayOf(
                COLUMN_ID,
                COLUMN_TITLE,
                COLUMN_CONTENT,
                COLUMN_CREATED_AT,
                COLUMN_USAGE_COUNT,
            ),
            null,
            null,
            null,
            null,
            "$COLUMN_USAGE_COUNT DESC, $COLUMN_CREATED_AT DESC",
        ).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(COLUMN_TITLE)
                val contentIndex = cursor.getColumnIndexOrThrow(COLUMN_CONTENT)
                val createdAtIndex = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)
                val usageCountIndex = cursor.getColumnIndexOrThrow(COLUMN_USAGE_COUNT)
                while (cursor.moveToNext()) {
                    add(
                        Prompt(
                            id = cursor.getLong(idIndex),
                            title = cursor.getString(titleIndex),
                            content = cursor.getString(contentIndex),
                            createdAt = cursor.getLong(createdAtIndex),
                            usageCount = cursor.getInt(usageCountIndex),
                        ),
                    )
                }
            }
        }
    }

    override fun add(title: String, content: String): Long {
        val values = ContentValues().apply {
            put(COLUMN_TITLE, title)
            put(COLUMN_CONTENT, content)
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow(TABLE_PROMPTS, null, values)
    }

    override fun update(prompt: Prompt) {
        writableDatabase.update(
            TABLE_PROMPTS,
            ContentValues().apply {
                put(COLUMN_TITLE, prompt.title)
                put(COLUMN_CONTENT, prompt.content)
            },
            "$COLUMN_ID = ?",
            arrayOf(prompt.id.toString()),
        )
    }

    override fun delete(promptId: Long) {
        writableDatabase.delete(TABLE_PROMPTS, "$COLUMN_ID = ?", arrayOf(promptId.toString()))
    }

    override fun incrementUsage(promptId: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE_PROMPTS SET $COLUMN_USAGE_COUNT = $COLUMN_USAGE_COUNT + 1 " +
                "WHERE $COLUMN_ID = ?",
            arrayOf(promptId.toString()),
        )
    }

    override fun isEmpty(): Boolean {
        return readableDatabase.query(
            TABLE_PROMPTS,
            arrayOf(COLUMN_ID),
            null,
            null,
            null,
            null,
            null,
            "1",
        ).use { cursor -> !cursor.moveToFirst() }
    }

    companion object {
        private const val DATABASE_NAME = "chatyue_prompts.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_PROMPTS = "prompts"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_USAGE_COUNT = "usage_count"
    }
}
