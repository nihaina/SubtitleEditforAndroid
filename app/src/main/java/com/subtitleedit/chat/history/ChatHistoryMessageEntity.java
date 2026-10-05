package com.subtitleedit.chat.history;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

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
    @ColumnInfo(defaultValue = "0")
    public int outputTokens;
    @ColumnInfo(defaultValue = "0")
    public long generationMs;

    @Ignore
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
        this(sessionId, position, role, content, reasoningContent, toolCallsJson, toolCallId, toolName, 0, 0L);
    }

    public ChatHistoryMessageEntity(
            @NonNull String sessionId,
            int position,
            @NonNull String role,
            @NonNull String content,
            @NonNull String reasoningContent,
            @NonNull String toolCallsJson,
            @NonNull String toolCallId,
            @NonNull String toolName,
            int outputTokens,
            long generationMs
    ) {
        this.sessionId = sessionId;
        this.position = position;
        this.role = role;
        this.content = content;
        this.reasoningContent = reasoningContent;
        this.toolCallsJson = toolCallsJson;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.outputTokens = outputTokens;
        this.generationMs = generationMs;
    }
}
