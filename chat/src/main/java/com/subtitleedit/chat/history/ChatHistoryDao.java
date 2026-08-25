package com.subtitleedit.chat.history;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ChatHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertSession(ChatHistorySessionEntity session);

    @Insert
    void insertMessages(List<ChatHistoryMessageEntity> messages);

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC LIMIT :limit")
    List<ChatHistorySessionEntity> recentSessions(int limit);

    @Query("SELECT * FROM chat_sessions WHERE (:type = '' OR type = :type) ORDER BY updatedAt DESC LIMIT :limit")
    List<ChatHistorySessionEntity> recentSessionsByType(String type, int limit);

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    ChatHistorySessionEntity getSession(String id);

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY position ASC")
    List<ChatHistoryMessageEntity> getMessages(String sessionId);

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM chat_messages WHERE sessionId = :sessionId")
    int nextPosition(String sessionId);

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    void deleteMessages(String sessionId);

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    void deleteSession(String id);

    @Query("DELETE FROM chat_sessions")
    void clearSessions();

    @Query("""
        SELECT m.sessionId AS sessionId, s.title AS title, s.type AS type,
               s.updatedAt AS updatedAt, m.position AS position, m.role AS role,
               snippet(chat_messages_fts, '[', ']', '...', -1, 12) AS snippet
        FROM chat_messages_fts
        JOIN chat_messages AS m ON chat_messages_fts.rowid = m.rowId
        JOIN chat_sessions AS s ON s.id = m.sessionId
        WHERE chat_messages_fts MATCH :query
          AND (:type = '' OR s.type = :type)
        ORDER BY s.updatedAt DESC, m.position ASC
        LIMIT :limit
        """)
    List<ChatHistorySearchRow> searchMessages(String query, String type, int limit);

    @Query("""
        SELECT m.sessionId AS sessionId, s.title AS title, s.type AS type,
               s.updatedAt AS updatedAt, m.position AS position, m.role AS role,
               substr(m.content, 1, 240) AS snippet
        FROM chat_messages AS m
        JOIN chat_sessions AS s ON s.id = m.sessionId
        WHERE m.content LIKE '%' || :query || '%'
          AND (:type = '' OR s.type = :type)
        ORDER BY s.updatedAt DESC, m.position ASC
        LIMIT :limit
        """)
    List<ChatHistorySearchRow> searchMessagesByContent(String query, String type, int limit);
}
