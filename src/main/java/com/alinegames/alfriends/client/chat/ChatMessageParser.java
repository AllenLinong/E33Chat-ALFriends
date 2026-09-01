package com.alinegames.alfriends.client.chat;

import com.alinegames.alfriends.client.ChatMessageStore;
import com.alinegames.alfriends.client.ChatMessageStore.SenderMeta;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class ChatMessageParser {
    private ChatMessageParser() {}

    public static SenderMeta tryParseAsPlayerMessage(Text message, String text) {
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.player.networkHandler == null || text == null) return null;
        if (isServerStatusMessage(text)) return null;

        Set<String> names = new LinkedHashSet<>();
        for (PlayerListEntry info : client.player.networkHandler.getPlayerList()) {
            addName(names, profileName(info));
            if (info.getDisplayName() != null) addName(names, info.getDisplayName().getString().trim());
        }
        names.addAll(ChatMessageStore.knownNameVariants());
        var parsed = MessagePresentation.parseDecoratedPlayerLine(text, new ArrayList<>(names));
        if (parsed.isEmpty()) return null;

        var line = parsed.orElseThrow();
        PlayerListEntry info = findPlayer(line.playerName());
        UUID uuid = info == null ? ChatMessageStore.findSeenUuid(line.playerName()) : profileId(info);
        if (uuid == null) uuid = new UUID(0, 0);
        String rawName = info == null ? line.playerName() : profileName(info);
        Text sender = ChatMessageStore.sliceStyled(message, 0, line.contentStart());
        String senderText = sender.getString().trim();
        int separator = senderText.lastIndexOf(line.playerName());
        if (separator >= 0) sender = ChatMessageStore.sliceStyled(message, 0, separator + line.playerName().length());
        Text content = ChatMessageStore.sliceStyled(message, line.contentStart(), text.length());
        return new SenderMeta(uuid, sender, content, false, rawName, false, null);
    }

    private static boolean isServerStatusMessage(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("??????") || lower.contains("??????")
            || lower.contains("????") || lower.contains("????")
            || lower.contains(" joined the game") || lower.contains(" left the game")
            || lower.contains(" joined the server") || lower.contains(" left the server");
    }

    private static void addName(Set<String> names, String name) {
        if (name == null || name.isEmpty()) return;
        names.add(name);
        String stripped = stripFormatting(name);
        if (!stripped.isEmpty()) names.add(stripped);
    }

    private static String stripFormatting(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if ((current == '?' || current == '&') && i + 1 < value.length()) {
                i++;
                continue;
            }
            result.append(current);
        }
        return result.toString();
    }

    private static PlayerListEntry findPlayer(String displayName) {
        var player = MinecraftClient.getInstance().player;
        if (player == null || player.networkHandler == null) return null;
        for (PlayerListEntry info : player.networkHandler.getPlayerList()) {
            String profile = profileName(info);
            if (profile.equals(displayName) || displayName.contains(profile)) return info;
        }
        return null;
    }

    private static String profileName(PlayerListEntry info) {
        //#if MC >= 12109
        return info.getProfile().name();
        //#else
        //$$ return info.getProfile().getName();
        //#endif
    }

    private static UUID profileId(PlayerListEntry info) {
        //#if MC >= 12109
        return info.getProfile().id();
        //#else
        //$$ return info.getProfile().getId();
        //#endif
    }
}
