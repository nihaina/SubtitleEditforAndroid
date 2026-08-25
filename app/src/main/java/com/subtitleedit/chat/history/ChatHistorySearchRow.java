package com.subtitleedit.chat.history;

/** Projection returned by the FTS query. */
public class ChatHistorySearchRow {
    public String sessionId;
    public String title;
    public String type;
    public long updatedAt;
    public int position;
    public String role;
    public String snippet;
}
