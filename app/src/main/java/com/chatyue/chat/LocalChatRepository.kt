package com.chatyue.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

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
                $COLUMN_UPDATED_AT INTEGER NOT NULL,
                $COLUMN_MEMORY_MODE TEXT NOT NULL DEFAULT 'GLOBAL',
                $COLUMN_PINNED INTEGER NOT NULL DEFAULT 0,
                $COLUMN_TAGS TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_TAGS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL,
                $COLUMN_COLOR TEXT NOT NULL
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
                $COLUMN_REPLIED_TO_ID INTEGER,
                $COLUMN_REPLIED_ROLE TEXT,
                $COLUMN_REPLIED_PREVIEW TEXT,
                $COLUMN_STARRED INTEGER NOT NULL DEFAULT 0,
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
        // 1 -> 2：会话表新增「记忆模式」列。
        // 使用 ALTER TABLE 原地升级，保证既有对话数据不丢失。
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE $TABLE_CONVERSATIONS ADD COLUMN " +
                    "$COLUMN_MEMORY_MODE TEXT NOT NULL DEFAULT 'GLOBAL'",
            )
        }
        if (oldVersion < 3) {
            // 2 -> 3：会话表新增「置顶」列，老会话默认不置顶。
            db.execSQL(
                "ALTER TABLE $TABLE_CONVERSATIONS ADD COLUMN " +
                    "$COLUMN_PINNED INTEGER NOT NULL DEFAULT 0",
            )
        }
        if (oldVersion < 4) {
            // 3 -> 4：消息表新增「引用回复」三列，老消息默认无引用。
            db.execSQL(
                "ALTER TABLE $TABLE_MESSAGES ADD COLUMN " +
                    "$COLUMN_REPLIED_TO_ID INTEGER",
            )
            db.execSQL(
                "ALTER TABLE $TABLE_MESSAGES ADD COLUMN " +
                    "$COLUMN_REPLIED_ROLE TEXT",
            )
            db.execSQL(
                "ALTER TABLE $TABLE_MESSAGES ADD COLUMN " +
                    "$COLUMN_REPLIED_PREVIEW TEXT",
            )
        }
        if (oldVersion < 5) {
            // 4 -> 5：消息表新增「收藏」列，老消息默认未收藏。
            db.execSQL(
                "ALTER TABLE $TABLE_MESSAGES ADD COLUMN " +
                    "$COLUMN_STARRED INTEGER NOT NULL DEFAULT 0",
            )
        }
        if (oldVersion < 6) {
            // 5 -> 6：会话表新增「标签」列（存储标签 id 的 JSON 数组）。
            db.execSQL(
                "ALTER TABLE $TABLE_CONVERSATIONS ADD COLUMN " +
                    "$COLUMN_TAGS TEXT NOT NULL DEFAULT '[]'",
            )
        }
    }

    override fun createConversation(
        title: String,
        memoryMode: ConversationMemoryMode,
    ): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COLUMN_TITLE, title)
            put(COLUMN_UPDATED_AT, now)
            put(COLUMN_MEMORY_MODE, memoryMode.name)
        }
        return writableDatabase.insertOrThrow(TABLE_CONVERSATIONS, null, values)
    }

    override fun updateConversationMemoryMode(
        conversationId: Long,
        memoryMode: ConversationMemoryMode,
    ) {
        writableDatabase.update(
            TABLE_CONVERSATIONS,
            ContentValues().apply { put(COLUMN_MEMORY_MODE, memoryMode.name) },
            "$COLUMN_ID = ?",
            arrayOf(conversationId.toString()),
        )
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
                    c.$COLUMN_UPDATED_AT,
                    c.$COLUMN_MEMORY_MODE,
                    c.$COLUMN_PINNED,
                    c.$COLUMN_TAGS
                FROM $TABLE_CONVERSATIONS c
                LEFT JOIN $TABLE_MESSAGES m
                    ON c.$COLUMN_ID = m.$COLUMN_CONVERSATION_ID
                """.trimIndent(),
            )
            if (selection != null) {
                append(" WHERE ")
                append(selection)
            }
            append(" ORDER BY c.$COLUMN_PINNED DESC, c.$COLUMN_UPDATED_AT DESC")
        }

        return readableDatabase.rawQuery(sql, selectionArgs).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(COLUMN_TITLE)
                val updatedAtIndex = cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT)
                val memoryModeIndex = cursor.getColumnIndexOrThrow(COLUMN_MEMORY_MODE)
                val pinnedIndex = cursor.getColumnIndexOrThrow(COLUMN_PINNED)
                val tagsIndex = cursor.getColumnIndexOrThrow(COLUMN_TAGS)
                while (cursor.moveToNext()) {
                    add(
                        Conversation(
                            id = cursor.getLong(idIndex),
                            title = cursor.getString(titleIndex),
                            updatedAt = cursor.getLong(updatedAtIndex),
                            memoryMode = ConversationMemoryMode.fromName(
                                cursor.getString(memoryModeIndex),
                            ),
                            pinned = cursor.getInt(pinnedIndex) == 1,
                            tags = decodeTagIds(cursor.getString(tagsIndex)),
                        ),
                    )
                }
            }
        }
    }

    override fun getConversation(conversationId: Long): Conversation? {
        return readableDatabase.query(
            TABLE_CONVERSATIONS,
            arrayOf(
                COLUMN_ID,
                COLUMN_TITLE,
                COLUMN_UPDATED_AT,
                COLUMN_MEMORY_MODE,
                COLUMN_PINNED,
                COLUMN_TAGS,
            ),
            "$COLUMN_ID = ?",
            arrayOf(conversationId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Conversation(
                id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)),
                title = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE)),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
                memoryMode = ConversationMemoryMode.fromName(
                    cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MEMORY_MODE)),
                ),
                pinned = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PINNED)) == 1,
                tags = decodeTagIds(
                    cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TAGS)),
                ),
            )
        }
    }

    override fun getGlobalMemoryMessages(excludeConversationId: Long): List<ChatMessage> {
        // 只汇总「全局对话」会话的消息；单独对话的数据保持隔离。
        val sql = """
            SELECT m.$COLUMN_ID, m.$COLUMN_CONVERSATION_ID, m.$COLUMN_ROLE,
                   m.$COLUMN_CONTENT, m.$COLUMN_CREATED_AT,
                   m.$COLUMN_REPLIED_TO_ID, m.$COLUMN_REPLIED_ROLE, m.$COLUMN_REPLIED_PREVIEW
            FROM $TABLE_MESSAGES m
            INNER JOIN $TABLE_CONVERSATIONS c
                ON m.$COLUMN_CONVERSATION_ID = c.$COLUMN_ID
            WHERE c.$COLUMN_MEMORY_MODE = ?
              AND m.$COLUMN_CONVERSATION_ID <> ?
            ORDER BY m.$COLUMN_CREATED_AT ASC, m.$COLUMN_ID ASC
        """.trimIndent()
        return readableDatabase.rawQuery(
            sql,
            arrayOf(ConversationMemoryMode.GLOBAL.name, excludeConversationId.toString()),
        ).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val conversationIndex = cursor.getColumnIndexOrThrow(COLUMN_CONVERSATION_ID)
                val roleIndex = cursor.getColumnIndexOrThrow(COLUMN_ROLE)
                val contentIndex = cursor.getColumnIndexOrThrow(COLUMN_CONTENT)
                val createdAtIndex = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)
                val repliedToIdIndex = cursor.getColumnIndexOrThrow(COLUMN_REPLIED_TO_ID)
                val repliedRoleIndex = cursor.getColumnIndexOrThrow(COLUMN_REPLIED_ROLE)
                val repliedPreviewIndex = cursor.getColumnIndexOrThrow(COLUMN_REPLIED_PREVIEW)
                while (cursor.moveToNext()) {
                    add(
                        ChatMessage(
                            id = cursor.getLong(idIndex),
                            conversationId = cursor.getLong(conversationIndex),
                            role = MessageRole.valueOf(cursor.getString(roleIndex)),
                            content = cursor.getString(contentIndex),
                            createdAt = cursor.getLong(createdAtIndex),
                            repliedToId = cursor.getLong(repliedToIdIndex)
                                .takeIf { !cursor.isNull(repliedToIdIndex) },
                            repliedRole = cursor.getString(repliedRoleIndex)
                                ?.let { name -> MessageRole.entries.firstOrNull { it.name == name } },
                            repliedPreview = cursor.getString(repliedPreviewIndex),
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
                COLUMN_REPLIED_TO_ID,
                COLUMN_REPLIED_ROLE,
                COLUMN_REPLIED_PREVIEW,
                COLUMN_STARRED,
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
                val repliedToIdIndex = cursor.getColumnIndexOrThrow(COLUMN_REPLIED_TO_ID)
                val repliedRoleIndex = cursor.getColumnIndexOrThrow(COLUMN_REPLIED_ROLE)
                val repliedPreviewIndex = cursor.getColumnIndexOrThrow(COLUMN_REPLIED_PREVIEW)
                val starredIndex = cursor.getColumnIndexOrThrow(COLUMN_STARRED)
                while (cursor.moveToNext()) {
                    add(
                        ChatMessage(
                            id = cursor.getLong(idIndex),
                            conversationId = cursor.getLong(conversationIndex),
                            role = MessageRole.valueOf(cursor.getString(roleIndex)),
                            content = cursor.getString(contentIndex),
                            createdAt = cursor.getLong(createdAtIndex),
                            repliedToId = cursor.getLong(repliedToIdIndex)
                                .takeIf { !cursor.isNull(repliedToIdIndex) },
                            repliedRole = cursor.getString(repliedRoleIndex)
                                ?.let { name -> MessageRole.entries.firstOrNull { it.name == name } },
                            repliedPreview = cursor.getString(repliedPreviewIndex),
                            starred = cursor.getInt(starredIndex) == 1,
                        ),
                    )
                }
            }
        }
    }

    override fun getAllMessages(): List<ChatMessage> {
        return readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(
                COLUMN_ID,
                COLUMN_CONVERSATION_ID,
                COLUMN_ROLE,
                COLUMN_CONTENT,
                COLUMN_CREATED_AT,
                COLUMN_REPLIED_TO_ID,
                COLUMN_REPLIED_ROLE,
                COLUMN_REPLIED_PREVIEW,
            ),
            null,
            null,
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
                val repliedToIdIndex = cursor.getColumnIndexOrThrow(COLUMN_REPLIED_TO_ID)
                val repliedRoleIndex = cursor.getColumnIndexOrThrow(COLUMN_REPLIED_ROLE)
                val repliedPreviewIndex =
                    cursor.getColumnIndexOrThrow(COLUMN_REPLIED_PREVIEW)
                while (cursor.moveToNext()) {
                    add(
                        ChatMessage(
                            id = cursor.getLong(idIndex),
                            conversationId = cursor.getLong(conversationIndex),
                            role = MessageRole.valueOf(cursor.getString(roleIndex)),
                            content = cursor.getString(contentIndex),
                            createdAt = cursor.getLong(createdAtIndex),
                            repliedToId = cursor.getLong(repliedToIdIndex)
                                .takeIf { !cursor.isNull(repliedToIdIndex) },
                            repliedRole = cursor.getString(repliedRoleIndex)
                                ?.let { name ->
                                    MessageRole.entries.firstOrNull { it.name == name }
                                },
                            repliedPreview = cursor.getString(repliedPreviewIndex),
                        ),
                    )
                }
            }
        }
    }

    override fun searchMessages(query: String, limit: Int): List<MessageHit> {        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()
        val likeQuery = "%${escapeLike(normalized)}%"
        val sql = """
            SELECT m.$COLUMN_ID, m.$COLUMN_CONVERSATION_ID, m.$COLUMN_ROLE,
                   m.$COLUMN_CONTENT, m.$COLUMN_CREATED_AT, c.$COLUMN_TITLE
            FROM $TABLE_MESSAGES m
            INNER JOIN $TABLE_CONVERSATIONS c
                ON m.$COLUMN_CONVERSATION_ID = c.$COLUMN_ID
            WHERE m.$COLUMN_CONTENT LIKE ? ESCAPE '\'
              AND m.$COLUMN_ROLE IN (?, ?)
            ORDER BY m.$COLUMN_CREATED_AT DESC
            LIMIT ?
        """.trimIndent()
        return readableDatabase.rawQuery(
            sql,
            arrayOf(
                likeQuery,
                MessageRole.USER.name,
                MessageRole.ASSISTANT.name,
                limit.toString(),
            ),
        ).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val conversationIndex = cursor.getColumnIndexOrThrow(COLUMN_CONVERSATION_ID)
                val roleIndex = cursor.getColumnIndexOrThrow(COLUMN_ROLE)
                val contentIndex = cursor.getColumnIndexOrThrow(COLUMN_CONTENT)
                val createdAtIndex = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)
                val titleIndex = cursor.getColumnIndexOrThrow(COLUMN_TITLE)
                while (cursor.moveToNext()) {
                    add(
                        MessageHit(
                            messageId = cursor.getLong(idIndex),
                            conversationId = cursor.getLong(conversationIndex),
                            conversationTitle = cursor.getString(titleIndex),
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
        repliedToId: Long?,
        repliedRole: MessageRole?,
        repliedPreview: String?,
    ): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COLUMN_CONVERSATION_ID, conversationId)
            put(COLUMN_ROLE, role.name)
            put(COLUMN_CONTENT, content)
            put(COLUMN_CREATED_AT, now)
            // 引用字段可空：不引用时不写列，保持 NULL。
            put(COLUMN_REPLIED_TO_ID, repliedToId)
            put(COLUMN_REPLIED_ROLE, repliedRole?.name)
            put(COLUMN_REPLIED_PREVIEW, repliedPreview)
            // 新消息默认未收藏。
            put(COLUMN_STARRED, 0)
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

    override fun setMessageStarred(messageId: Long, starred: Boolean) {
        writableDatabase.update(
            TABLE_MESSAGES,
            ContentValues().apply { put(COLUMN_STARRED, if (starred) 1 else 0) },
            "$COLUMN_ID = ?",
            arrayOf(messageId.toString()),
        )
    }

    override fun getStarredMessages(): List<StarredMessage> {
        val sql = """
            SELECT m.$COLUMN_ID, m.$COLUMN_CONVERSATION_ID, m.$COLUMN_ROLE,
                   m.$COLUMN_CONTENT, m.$COLUMN_CREATED_AT, m.$COLUMN_STARRED,
                   c.$COLUMN_TITLE
            FROM $TABLE_MESSAGES m
            INNER JOIN $TABLE_CONVERSATIONS c
                ON m.$COLUMN_CONVERSATION_ID = c.$COLUMN_ID
            WHERE m.$COLUMN_STARRED = 1
            ORDER BY m.$COLUMN_CREATED_AT DESC, m.$COLUMN_ID DESC
        """.trimIndent()
        return readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val conversationIndex = cursor.getColumnIndexOrThrow(COLUMN_CONVERSATION_ID)
                val roleIndex = cursor.getColumnIndexOrThrow(COLUMN_ROLE)
                val contentIndex = cursor.getColumnIndexOrThrow(COLUMN_CONTENT)
                val createdAtIndex = cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)
                val starredIndex = cursor.getColumnIndexOrThrow(COLUMN_STARRED)
                val titleIndex = cursor.getColumnIndexOrThrow(COLUMN_TITLE)
                while (cursor.moveToNext()) {
                    add(
                        StarredMessage(
                            message = ChatMessage(
                                id = cursor.getLong(idIndex),
                                conversationId = cursor.getLong(conversationIndex),
                                role = MessageRole.valueOf(cursor.getString(roleIndex)),
                                content = cursor.getString(contentIndex),
                                createdAt = cursor.getLong(createdAtIndex),
                                starred = cursor.getInt(starredIndex) == 1,
                            ),
                            conversationTitle = cursor.getString(titleIndex),
                        ),
                    )
                }
            }
        }
    }

    override fun updateMessageRole(messageId: Long, role: MessageRole) {
        writableDatabase.update(
            TABLE_MESSAGES,
            ContentValues().apply { put(COLUMN_ROLE, role.name) },
            "$COLUMN_ID = ?",
            arrayOf(messageId.toString()),
        )
    }

    override fun updateMessageContent(messageId: Long, content: String) {
        // 编辑不改变消息时间与会话排序，只替换正文列。
        writableDatabase.update(
            TABLE_MESSAGES,
            ContentValues().apply { put(COLUMN_CONTENT, content) },
            "$COLUMN_ID = ?",
            arrayOf(messageId.toString()),
        )
    }

    override fun deleteMessage(messageId: Long) {
        // 先取所属会话，删除后把该会话的排序时间刷新为当前时间。
        val conversationId = readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(COLUMN_CONVERSATION_ID),
            "$COLUMN_ID = ?",
            arrayOf(messageId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CONVERSATION_ID))
            } else {
                null
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MESSAGES, "$COLUMN_ID = ?", arrayOf(messageId.toString()))
            if (conversationId != null) {
                db.update(
                    TABLE_CONVERSATIONS,
                    ContentValues().apply { put(COLUMN_UPDATED_AT, System.currentTimeMillis()) },
                    "$COLUMN_ID = ?",
                    arrayOf(conversationId.toString()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun deleteMessages(messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 先把受影响的会话收集出来，删除后统一刷新排序时间。
            val affectedConversations = mutableSetOf<Long>()
            messageIds.chunked(SQL_VARIABLE_LIMIT).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                readableDatabase.query(
                    TABLE_MESSAGES,
                    arrayOf(COLUMN_CONVERSATION_ID),
                    "$COLUMN_ID IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    val conversationIndex =
                        cursor.getColumnIndexOrThrow(COLUMN_CONVERSATION_ID)
                    while (cursor.moveToNext()) {
                        affectedConversations.add(cursor.getLong(conversationIndex))
                    }
                }
                db.delete(
                    TABLE_MESSAGES,
                    "$COLUMN_ID IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                )
            }
            val now = System.currentTimeMillis()
            affectedConversations.forEach { conversationId ->
                db.update(
                    TABLE_CONVERSATIONS,
                    ContentValues().apply { put(COLUMN_UPDATED_AT, now) },
                    "$COLUMN_ID = ?",
                    arrayOf(conversationId.toString()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun deleteMessagesAfter(messageId: Long) {
        // 同会话内主键 id 单调递增，用 id 比较即可精确截断到该消息为止。
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM $TABLE_MESSAGES WHERE " +
                    "$COLUMN_CONVERSATION_ID = " +
                    "(SELECT $COLUMN_CONVERSATION_ID FROM $TABLE_MESSAGES WHERE $COLUMN_ID = ?) " +
                    "AND $COLUMN_ID > ?",
                arrayOf(messageId.toString(), messageId.toString()),
            )
            db.update(
                TABLE_CONVERSATIONS,
                ContentValues().apply { put(COLUMN_UPDATED_AT, System.currentTimeMillis()) },
                "$COLUMN_ID = " +
                    "(SELECT $COLUMN_CONVERSATION_ID FROM $TABLE_MESSAGES WHERE $COLUMN_ID = ?)",
                arrayOf(messageId.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clearConversationMessages(conversationId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                TABLE_MESSAGES,
                "$COLUMN_CONVERSATION_ID = ?",
                arrayOf(conversationId.toString()),
            )
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

    override fun setConversationPinned(conversationId: Long, pinned: Boolean) {
        writableDatabase.update(
            TABLE_CONVERSATIONS,
            ContentValues().apply { put(COLUMN_PINNED, if (pinned) 1 else 0) },
            "$COLUMN_ID = ?",
            arrayOf(conversationId.toString()),
        )
    }

    override fun deleteConversation(conversationId: Long): String? {
        val title = readableDatabase.query(
            TABLE_CONVERSATIONS,
            arrayOf(COLUMN_TITLE),
            "$COLUMN_ID = ?",
            arrayOf(conversationId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TITLE))
            } else {
                null
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 消息表已配置 ON DELETE CASCADE，删会话即级联清空其全部消息。
            db.delete(
                TABLE_CONVERSATIONS,
                "$COLUMN_ID = ?",
                arrayOf(conversationId.toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return title
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

    // ---------------- 会话标签 ----------------

    override fun getTags(): List<Tag> {
        return readableDatabase.query(
            TABLE_TAGS,
            arrayOf(COLUMN_ID, COLUMN_NAME, COLUMN_COLOR),
            null,
            null,
            null,
            null,
            "$COLUMN_NAME COLLATE NOCASE ASC",
        ).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow(COLUMN_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(COLUMN_NAME)
                val colorIndex = cursor.getColumnIndexOrThrow(COLUMN_COLOR)
                while (cursor.moveToNext()) {
                    add(
                        Tag(
                            id = cursor.getLong(idIndex),
                            name = cursor.getString(nameIndex),
                            colorHex = cursor.getString(colorIndex),
                        ),
                    )
                }
            }
        }
    }

    override fun addTag(name: String, colorHex: String): Long =
        writableDatabase.insertOrThrow(
            TABLE_TAGS,
            null,
            ContentValues().apply {
                put(COLUMN_NAME, name.trim())
                put(COLUMN_COLOR, colorHex)
            },
        )

    override fun updateTag(tag: Tag) {
        writableDatabase.update(
            TABLE_TAGS,
            ContentValues().apply {
                put(COLUMN_NAME, tag.name.trim())
                put(COLUMN_COLOR, tag.colorHex)
            },
            "$COLUMN_ID = ?",
            arrayOf(tag.id.toString()),
        )
    }

    override fun deleteTag(tagId: Long) {
        writableDatabase.delete(
            TABLE_TAGS,
            "$COLUMN_ID = ?",
            arrayOf(tagId.toString()),
        )
        // 把该标签从所有会话上摘掉（移除 JSON 数组里的对应 id）。
        val conversations = getConversations("")
        writableDatabase.beginTransaction()
        try {
            conversations.forEach { conversation ->
                val remaining = conversation.tags.filter { it != tagId }
                if (remaining.size != conversation.tags.size) {
                    writableDatabase.update(
                        TABLE_CONVERSATIONS,
                        ContentValues().apply { put(COLUMN_TAGS, encodeTagIds(remaining)) },
                        "$COLUMN_ID = ?",
                        arrayOf(conversation.id.toString()),
                    )
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    override fun setConversationTags(conversationId: Long, tagIds: List<Long>) {
        writableDatabase.update(
            TABLE_CONVERSATIONS,
            ContentValues().apply { put(COLUMN_TAGS, encodeTagIds(tagIds)) },
            "$COLUMN_ID = ?",
            arrayOf(conversationId.toString()),
        )
    }

    /** 把标签 id 列表编码成可存进 TEXT 列的 JSON 数组字符串。 */
    private fun encodeTagIds(ids: List<Long>): String =
        JSONArray().apply { ids.forEach { put(it) } }.toString()

    /** 从 JSON 数组字符串里解出标签 id 列表，格式异常时返回空列表。 */
    private fun decodeTagIds(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    add(array.getLong(i))
                }
            }
        } catch (_: Exception) {
            emptyList()
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
        private const val DATABASE_VERSION = 6
        private const val SUMMARY_CREATED_AT = 1L

        /** SQLite 单条语句的变量数上限，批量删除按此分片，避免超出 999 限制。 */
        private const val SQL_VARIABLE_LIMIT = 400

        private const val TABLE_CONVERSATIONS = "conversations"
        private const val TABLE_MESSAGES = "messages"
        private const val TABLE_TAGS = "tags"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_UPDATED_AT = "updated_at"
        private const val COLUMN_MEMORY_MODE = "memory_mode"
        private const val COLUMN_PINNED = "pinned"
        private const val COLUMN_TAGS = "tags"
        private const val COLUMN_NAME = "name"
        private const val COLUMN_COLOR = "color"
        private const val COLUMN_CONVERSATION_ID = "conversation_id"
        private const val COLUMN_ROLE = "role"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_REPLIED_TO_ID = "replied_to_id"
        private const val COLUMN_REPLIED_ROLE = "replied_role"
        private const val COLUMN_REPLIED_PREVIEW = "replied_preview"
        private const val COLUMN_STARRED = "starred"
    }
}
