package com.subtitleedit.chat.history;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "chat_messages",
        foreignKeys = @ForeignKey(
                entity = ChatHistorySessionEntity.class,
                parentColumns = "id",
                childColumns = "sessionId",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {@Index("sessionId"), @Index(value = {"sessionId", "position"}, unique = true)}
)
public class ChatHistoryMessageEntity {
    @PrimaryKey(autoGenerate = true)
    public long rowId;
    @NonNull
    public String sessionId;
    public int position;
    @NonNull
    public String role;
    @NonNull
    public String content;
    @NonNull
    public String reasoningContent;
    @NonNull
    public String toolCallsJson;
    @NonNull
    public String toolCallId;
    @NonNull
    public String toolName;

    public ChatHistoryMessageEntity(
            @NonNull String sessionId,
            int position,
            @NonNull String role,
            @NonNull String content,
            @NonNull String reasoningContent,
            @NonNull String toolCallsJson,
            @NonNull String toolCallId,
            @NonNull String toolName
    ) {
        this.sessionId = sessionId;
        this.position = position;
        this.role = role;
        this.content = content;
        this.reasoningContent = reasoningContent;
        this.toolCallsJson = toolCallsJson;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
    }
}
