package com.alinegames.alfriends.client;

import com.alinegames.alfriends.client.network.ALFriendsHelloPayload;
import com.alinegames.alfriends.client.network.ALFriendsSendPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.util.UUID;

public final class ALFriendsBridge {
    private static volatile boolean active;
    private ALFriendsBridge() {}
    public static boolean active() { return active; }
    public static void setActive(boolean value) { active = value; }
    public static void hello() {
        if (ClientPlayNetworking.canSend(ALFriendsHelloPayload.ID)) ClientPlayNetworking.send(new ALFriendsHelloPayload(3));
    }
    public static boolean send(String partnerName, String message) {
        return send(partnerName, message, "", "");
    }
    public static boolean send(UUID partner, String message, String quoteSender, String quoteContent) {
        if (!active || partner == null || partner.equals(new UUID(0, 0))
            || !ClientPlayNetworking.canSend(ALFriendsSendPayload.ID)) return false;
        ClientPlayNetworking.send(new ALFriendsSendPayload(partner, message,
            quoteSender == null ? "" : quoteSender, quoteContent == null ? "" : quoteContent));
        return true;
    }
    public static boolean send(String partnerName, String message, String quoteSender, String quoteContent) {
        return send(ChatMessageStore.findSeenUuid(partnerName), message, quoteSender, quoteContent);
    }
}
