package com.subtitleedit.chat.history;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                ChatHistorySessionEntity.class,
                ChatHistoryMessageEntity.class,
                ChatHistoryMessageFtsEntity.class
        },
        version = 2,
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
                )
                        .addMigrations(MIGRATION_1_2)
                        .build();
                instance = local;
            }
            return local;
        }
    }

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE chat_messages ADD COLUMN outputTokens INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE chat_messages ADD COLUMN generationMs INTEGER NOT NULL DEFAULT 0");
        }
    };
}
