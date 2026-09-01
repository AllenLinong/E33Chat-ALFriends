package com.alinegames.alfriends.client.chat.notification;

import com.alinegames.alfriends.client.ChatBubbleClientSetup;
import com.alinegames.alfriends.client.ChatBubbleScreen;
import com.alinegames.alfriends.client.ChatMessageStore;
import com.alinegames.alfriends.client.chat.MentionDetector;
import com.alinegames.alfriends.client.chat.notification.MentionNotificationBanner.NotificationType;
import net.minecraft.client.MinecraftClient;
//#if MC >= 26000
//$$ import net.minecraft.client.sound.SimpleSoundInstance;
//#else
import net.minecraft.client.sound.PositionedSoundInstance;
//#endif
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.*;

public class MentionNotificationController {
    public static final MentionNotificationController INSTANCE = new MentionNotificationController();

    private final Map<String, Long> recentFingerprints = new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry<String, Long> e) {
            return size() > 256;
        }
    };

    private MentionNotificationController() {}

    public void onMessageCaptured(Text content, ChatMessageStore.SenderMeta meta,
                                   int messageIndex, String replySender) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        String localName = mc.player.getName().getString();
        boolean requireAt = ChatBubbleClientSetup.config().mentionRequireAt();
        String text = content.getString();

        if (!MentionDetector.isMentioned(text, localName, requireAt, replySender)) return;

        boolean isOwn = (meta.senderUUID() != null && meta.senderUUID().equals(mc.player.getUuid()))
            || (meta.rawPlayerName() != null && meta.rawPlayerName().equals(localName));
        boolean chatOpen = mc.currentScreen instanceof ChatBubbleScreen;
        NotificationType type = (replySender != null && replySender.equals(localName))
            ? NotificationType.QUOTE : NotificationType.MENTION;
        boolean selfNotify = isOwn && (type == NotificationType.QUOTE
            ? ChatBubbleClientSetup.config().ownQuoteNotify()
            : ChatBubbleClientSetup.config().ownMentionNotify());

        ChatMessageStore.debugLog(() -> "[alfriendschat] Mention | sender="
            + (meta.rawPlayerName() != null ? meta.rawPlayerName() : "?")
            + " | chatOpen=" + chatOpen
            + " | own=" + isOwn
            + " | type=" + type
            + " | sound=" + ChatBubbleClientSetup.config().mentionSoundEnabled()
            + " | banner=" + ChatBubbleClientSetup.config().mentionBannerEnabled()
            + " | selfNotify=" + selfNotify
            + " | preview=" + text.substring(0, Math.min(40, text.length())));

        boolean notify = (!isOwn || selfNotify)
            && (ChatBubbleClientSetup.config().mentionSoundEnabled()
                || ChatBubbleClientSetup.config().mentionBannerEnabled())
            && claimNotification(meta.senderUUID(), type, content);
        if (notify && ChatBubbleClientSetup.config().mentionSoundEnabled()) {
            //#if MC >= 26000
            //$$ mc.getSoundManager().play(SimpleSoundInstance.forUI(
            //$$     SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.25f,
            //$$     0.25f * ChatBubbleClientSetup.config().soundVolume() / 100f));
            //#else
            //#if MC >= 26000
            mc.getSoundManager().play(PositionedSoundInstance.ui(
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.25f,
                0.25f * ChatBubbleClientSetup.config().soundVolume() / 100f));
            //#else
            // Legacy sound factory unavailable on this mapping.
            //#endif
            //#endif
        }

        if (notify && ChatBubbleClientSetup.config().mentionBannerEnabled()) {
            MentionNotificationBanner.INSTANCE.enqueue(meta.senderUUID(), meta.senderName(), content, messageIndex, type);
        }
    }

    public void onWhisperReceived(UUID senderUUID, Text senderName, Text content,
                                   int messageIndex) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        boolean chatOpen = mc.currentScreen instanceof ChatBubbleScreen;
        String senderStr = senderName.getString().replaceAll("鎼?", "");
        boolean isOwn = (senderUUID != null && senderUUID.equals(mc.player.getUuid()))
            || mc.player.getName().getString().equals(senderStr);

        ChatMessageStore.debugLog(() -> "[alfriendschat] Whisper banner | sender=" + senderStr
            + " | chatOpen=" + chatOpen
            + " | own=" + isOwn
            + " | soundWhisper=" + ChatBubbleClientSetup.config().soundWhisper()
            + " | banner=" + ChatBubbleClientSetup.config().mentionWhisperBanner());

        boolean selfNotify = isOwn && ChatBubbleClientSetup.config().ownWhisperNotify();
        boolean notify = (!isOwn || selfNotify)
            && (ChatBubbleClientSetup.config().soundWhisper()
                || ChatBubbleClientSetup.config().mentionWhisperBanner())
            && claimNotification(senderUUID, NotificationType.WHISPER, content);
        if (notify && ChatBubbleClientSetup.config().soundWhisper()) {
            //#if MC >= 26000
            //$$ mc.getSoundManager().play(SimpleSoundInstance.forUI(
            //$$     SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.25f,
            //$$     0.25f * ChatBubbleClientSetup.config().soundVolume() / 100f));
            //#else
            //#if MC >= 26000
            mc.getSoundManager().play(PositionedSoundInstance.ui(
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.25f,
                0.25f * ChatBubbleClientSetup.config().soundVolume() / 100f));
            //#else
            // Legacy sound factory unavailable on this mapping.
            //#endif
            //#endif
        }

        if (notify && ChatBubbleClientSetup.config().mentionWhisperBanner()) {
            MentionNotificationBanner.INSTANCE.enqueue(senderUUID, senderName, content, messageIndex, NotificationType.WHISPER);
        }
    }

    // System messages (server broadcasts/deaths/joins) pop the same banner as
    // @/whisper/quote; no sender name 閳?the [缁崵绮篯 label is the name row.
    public void onSystemMessage(Text content, int messageIndex) {
        if (MinecraftClient.getInstance().player == null) return;
        if (!ChatBubbleClientSetup.config().systemBannerEnabled()) return;
        if (claimNotification(new UUID(0, 0), NotificationType.SYSTEM, content)) {
            MentionNotificationBanner.INSTANCE.enqueue(new UUID(0, 0), Text.empty(), content, messageIndex,
                NotificationType.SYSTEM);
        }
    }

    private boolean claimNotification(UUID uuid, NotificationType type, Text content) {
        String fp = type + "\0" + uuid + "\0" + content.getString();
        long now = System.currentTimeMillis();
        Long last = recentFingerprints.get(fp);
        if (last != null && now - last < 5 * 60_000L) {
            ChatMessageStore.debugLog(() -> "[alfriendschat] Notification deduped for 5 minutes | fp="
                + fp.substring(0, Math.min(60, fp.length())));
            return false;
        }
        recentFingerprints.put(fp, now);
        return true;
    }
}
