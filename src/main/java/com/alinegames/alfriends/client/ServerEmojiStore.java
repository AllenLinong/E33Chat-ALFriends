package com.alinegames.alfriends.client;

import com.alinegames.alfriends.client.network.ALFriendsEmojiCatalogPayload;

import java.util.List;

public final class ServerEmojiStore {
    private static List<ALFriendsEmojiCatalogPayload.Entry> entries = List.of();

    private ServerEmojiStore() {}

    public static void setEntries(List<ALFriendsEmojiCatalogPayload.Entry> value) {
        entries = value == null ? List.of() : List.copyOf(value);
    }

    public static List<ALFriendsEmojiCatalogPayload.Entry> entries() {
        return entries;
    }

    public static void clear() {
        entries = List.of();
    }
}
