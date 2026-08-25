package com.subtitleedit.chat.history;

import androidx.room.Entity;
import androidx.room.Fts4;

/** External-content FTS index maintained by Room for message text. */
@Fts4(contentEntity = ChatHistoryMessageEntity.class)
@Entity(tableName = "chat_messages_fts")
public class ChatHistoryMessageFtsEntity {
    public String content;
}
