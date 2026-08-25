package com.subtitleedit.chat.history;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "chat_sessions")
public class ChatHistorySessionEntity {
    @PrimaryKey
    @NonNull
    public String id;
    @NonNull
    public String title;
    @NonNull
    public String type;
    public long updatedAt;

    public ChatHistorySessionEntity(@NonNull String id, @NonNull String title, @NonNull String type, long updatedAt) {
        this.id = id;
        this.title = title;
        this.type = type;
        this.updatedAt = updatedAt;
    }
}
