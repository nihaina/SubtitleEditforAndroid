package com.subtitleedit.chat.history;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                ChatHistorySessionEntity.class,
                ChatHistoryMessageEntity.class,
                ChatHistoryMessageFtsEntity.class
        },
        version = 1,
        exportSchema = false
)
public abstract class ChatHistoryDatabase extends RoomDatabase {
    private static volatile ChatHistoryDatabase instance;

    public abstract ChatHistoryDao historyDao();

    public static ChatHistoryDatabase getInstance(Context context) {
        ChatHistoryDatabase local = instance;
        if (local != null) return local;
        synchronized (ChatHistoryDatabase.class) {
            local = instance;
            if (local == null) {
                local = Room.databaseBuilder(
                        context.getApplicationContext(),
                        ChatHistoryDatabase.class,
                        "chat_history.db"
                ).build();
                instance = local;
            }
            return local;
        }
    }
}
