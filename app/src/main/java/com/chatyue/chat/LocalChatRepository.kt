package com.chatyue.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocalChatRepository(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION),
    ChatRepository {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CONVERSATIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CONVERSATION_ID INTEGER NOT NULL,
                $COLUMN_ROLE TEXT NOT NULL,
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                FOREIGN KEY($COLUMN_CONVERSATION_ID)
                    REFERENCES $TABLE_CONVERSATIONS($COLUMN_ID)
                    ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX index_messages_conversation ON " +
                "$TABLE_MESSAGES($COLUMN_CONVERSATION_ID)",
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_MESSAGES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CONVERSATIONS")
        onCreate(db)
    }

    override fun createConversation(title: String): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COLUMN_TITLE, title)
            put(COLUMN_UPDATED_AT, now)
        }
        return writableDatabase.insertOrThrow(TABLE_CONVERSATIONS, null, values)
    }

    override fun updateConversationTitle(conversationId: Long, title: String) {
        val values = ContentValues().apply {
            put(COLUMN_TITLE, title)
            put(COLUMN_UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.update(
            TABLE_CONVERSATIONS,
            values,
            "$COLUMN_ID = ?",
            arrayOf(conversationId.toString()),
        )
    }

    override fun getConversations(query: String): List<Conversation> {
        val normalizedQuery = query.trim()
        val selection: String?
        val selectionArgs: Array<String>?

        if (normalizedQuery.isEmpty()) {
            selection = null
            selectionArgs = null
        } else {
            val likeQuery = "%${escapeLike(normalizedQuery)}%"
            selection =
                "c.$COLUMN_TITLE LIKE ? ESCAPE '\\' OR " +
                "m.$COLUMN_CONTENT LIKE ? ESCAPE '\\'"
            selectionArgs = arrayOf(likeQuery, likeQuery)
        }

        val sql = buildString {
            append(
                """
                SELECT DISTINCT
                    c.$COLUMN_ID,
                    c.$COLUMN_TITLE,
                    c.$COLUMN_UPDATED_AT
                FROM $TABLE_CONVERSATIONS c
                LEFT JOIN $TABLE_MESSAGES m
                    ON c.$COLUMN_ID = m.$COLUMN_CONVERSATION_ID
                """.trimIndent(),
            )
            if (selection != null) {
                append(" WHERE ")
                append(selection)
            }
            append(" ORDER BY c.$COLUMN_UPDATED_AT DESC")
        }

        return readableDatabase.rawQuery(sql, selectionArgs).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(COLUMN_TITLE)
                val updatedAtIndex = cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT)
                while (cursor.moveToNext()) {
                    add(
                        Conversation(
                            id = cursor.getLong(idIndex),
                            title = cursor.getString(titleIndex),
                            updatedAt = cursor.getLong(updatedAtIndex),
                        ),
                    )
                }
            }
        }
    }

    override fun getMessages(conversationId: Long): List<ChatMessage> {
        return readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(
                COLUMN_ID,
                COLUMN_CONVERSATION_ID,
                COLUMN_ROLE,
                COLUMN_CONTENT,
                COLUMN_CREATED_AT,
            ),
            "$COLUMN_CONVERSATION_ID = ?",
            arrayOf(conversationId.toString()),
            null,
            null,
            "$COLUMN_CREATED_AT ASC, $COLUMN_ID ASC",
        ).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val conversationIndex =
                    cursor.getColumnIndexOrThrow(COLUMN_CONVERSATION_ID)
                val roleIndex = cursor.getColumnIndexOrThrow(COLUMN_ROLE)
                val contentIndex = cursor.getColumnIndexOrThrow(COLUMN_CONTENT)
                val createdAtIndex = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)
                while (cursor.moveToNext()) {
                    add(
                        ChatMessage(
                            id = cursor.getLong(idIndex),
                            conversationId = cursor.getLong(conversationIndex),
                            role = MessageRole.valueOf(cursor.getString(roleIndex)),
                            content = cursor.getString(contentIndex),
                            createdAt = cursor.getLong(createdAtIndex),
                        ),
                    )
                }
            }
        }
    }

    override fun addMessage(
        conversationId: Long,
        role: MessageRole,
        content: String,
    ): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COLUMN_CONVERSATION_ID, conversationId)
            put(COLUMN_ROLE, role.name)
            put(COLUMN_CONTENT, content)
            put(COLUMN_CREATED_AT, now)
        }
        val db = writableDatabase
        val messageId = db.insertOrThrow(TABLE_MESSAGES, null, values)
        db.update(
            TABLE_CONVERSATIONS,
            ContentValues().apply { put(COLUMN_UPDATED_AT, now) },
            "$COLUMN_ID = ?",
            arrayOf(conversationId.toString()),
        )
        return messageId
    }

    override fun updateMessageRole(messageId: Long, role: MessageRole) {
        writableDatabase.update(
            TABLE_MESSAGES,
            ContentValues().apply { put(COLUMN_ROLE, role.name) },
            "$COLUMN_ID = ?",
            arrayOf(messageId.toString()),
        )
    }

    override fun finalizeInterruptedThinking() {
        writableDatabase.update(
            TABLE_MESSAGES,
            ContentValues().apply {
                put(COLUMN_ROLE, MessageRole.THINKING_FINISHED.name)
            },
            "$COLUMN_ROLE = ?",
            arrayOf(MessageRole.THINKING_ACTIVE.name),
        )
    }

    override fun replaceMessagesWithSystemSummary(
        conversationId: Long,
        removedMessageIds: List<Long>,
        summary: String,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (removedMessageIds.isNotEmpty()) {
                val placeholders = removedMessageIds.joinToString(",") { "?" }
                db.delete(
                    TABLE_MESSAGES,
                    "$COLUMN_CONVERSATION_ID = ? AND $COLUMN_ID IN ($placeholders)",
                    arrayOf(conversationId.toString()) +
                        removedMessageIds.map { it.toString() },
                )
            }
            // 摘要固定排在会话最前面，因此给一个最小的 created_at。
            val summaryValues = ContentValues().apply {
                put(COLUMN_CONVERSATION_ID, conversationId)
                put(COLUMN_ROLE, MessageRole.SYSTEM.name)
                put(COLUMN_CONTENT, summary)
                put(COLUMN_CREATED_AT, SUMMARY_CREATED_AT)
            }
            db.insertOrThrow(TABLE_MESSAGES, null, summaryValues)
            db.update(
                TABLE_CONVERSATIONS,
                ContentValues().apply { put(COLUMN_UPDATED_AT, System.currentTimeMillis()) },
                "$COLUMN_ID = ?",
                arrayOf(conversationId.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MESSAGES, null, null)
            db.delete(TABLE_CONVERSATIONS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun escapeLike(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }

    companion object {
        private const val DATABASE_NAME = "chatyue.db"
        private const val DATABASE_VERSION = 1
        private const val SUMMARY_CREATED_AT = 1L

        private const val TABLE_CONVERSATIONS = "conversations"
        private const val TABLE_MESSAGES = "messages"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_UPDATED_AT = "updated_at"
        private const val COLUMN_CONVERSATION_ID = "conversation_id"
        private const val COLUMN_ROLE = "role"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_CREATED_AT = "created_at"
    }
}
