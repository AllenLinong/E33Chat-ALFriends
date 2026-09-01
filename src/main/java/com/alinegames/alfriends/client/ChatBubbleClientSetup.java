package com.alinegames.alfriends.client;

import com.alinegames.alfriends.client.config.ChatBubbleConfig;
import com.alinegames.alfriends.client.config.ConfigManager;
import com.alinegames.alfriends.client.image.ImageLoader;
import com.alinegames.alfriends.client.network.ChatMetaPayload;
import com.alinegames.alfriends.client.network.ConfigSyncPayload;
import com.alinegames.alfriends.client.network.ConfigSyncV2Payload;
import com.alinegames.alfriends.client.network.HistoryPayload;
import com.alinegames.alfriends.client.network.MediaCapPayload;
import com.alinegames.alfriends.client.network.ServerConfigScreenPayload;
import com.alinegames.alfriends.client.network.ALFriendsHelloAckPayload;
import com.alinegames.alfriends.client.network.ALFriendsMessagePayload;
import com.alinegames.alfriends.client.network.ALFriendsOpenChatPayload;
import com.alinegames.alfriends.client.network.ALFriendsContactsPayload;
import com.alinegames.alfriends.client.network.ALFriendsHistoryPayload;
import com.alinegames.alfriends.client.network.ALFriendsEmojiCatalogPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
//#if MC >= 26000
//$$ import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
//#else
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//#endif
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

public class ChatBubbleClientSetup implements ClientModInitializer {
    private static ChatBubbleConfig config = ChatBubbleConfig.defaults();
    private static Path configPath;
    private static boolean leftWasDown;
    private static boolean privateChatShortcutWasDown;
    private static boolean wasInWorld;

    public static ChatBubbleConfig config() { return config; }

    public static void saveConfig(ChatBubbleConfig newConfig) {
        config = newConfig;
        E33Log.info("[alfriendschat] Saving config | soundPublic=" + newConfig.soundPublic() + " | soundSystem=" + newConfig.soundSystem());
        ConfigManager.save(configPath, config);
    }

    @Override
    public void onInitializeClient() {
        configPath = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/alfriendschat-client.json");
        // v2.3.x renamed the file from alfriendschat.json to alfriendschat-client.json (aligns with
        // Forge/Neo); migrate an existing old file so users keep their settings
        Path legacyPath = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/alfriendschat.json");
        if (!Files.exists(configPath) && Files.exists(legacyPath)) {
            config = ConfigManager.load(legacyPath);
            ConfigManager.save(configPath, config);
            E33Log.info("[alfriendschat] Migrated config from config/alfriendschat.json to config/alfriendschat-client.json");
        } else {
            config = ConfigManager.load(configPath);
        }

        //#if MC >= 12005
        ClientPlayNetworking.registerGlobalReceiver(ChatMetaPayload.ID, (payload, context) -> {
            context.client().execute(() -> ChatMessageStore.applyChatMeta(
                payload.senderUUID(), payload.senderName(), payload.messageHash(),
                payload.quoteSender(), payload.quoteContent(), payload.mentionTargets()));
        });
        ClientPlayNetworking.registerGlobalReceiver(HistoryPayload.ID, (payload, context) -> {
            context.client().execute(() -> ChatMessageStore.addHistoryMessages(payload.entries()));
        });
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncPayload.ID, (payload, context) -> {
            context.client().execute(() -> ConfigSyncPayload.handle(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(ConfigSyncV2Payload.ID, (payload, context) -> {
            context.client().execute(() -> ConfigSyncV2Payload.handle(payload));
        });
        // Server-config GUI: opened on the client only (server never loads the Screen)
        ClientPlayNetworking.registerGlobalReceiver(ServerConfigScreenPayload.ID, (payload, context) -> {
            context.client().execute(() -> MinecraftClient.getInstance().setScreen(new ServerConfigScreen(
                MinecraftClient.getInstance().currentScreen,
                payload.useTpa(), payload.historyEnabled(), payload.templateDebug(),
                payload.chatTemplates(), payload.whisperTemplates(), payload.mediaEnabled(),
                payload.mediaAutoClean())));
        });

        com.alinegames.alfriends.client.image.MediaClient.registerReceivers();
        ClientPlayNetworking.registerGlobalReceiver(MediaCapPayload.ID, (payload, context) -> {
            context.client().execute(() -> MediaCapPayload.handle(payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(ALFriendsHelloAckPayload.ID, (payload, context) ->
            context.client().execute(() -> ALFriendsBridge.setActive(payload.accepted())));
        ClientPlayNetworking.registerGlobalReceiver(ALFriendsMessagePayload.ID, (payload, context) ->
            context.client().execute(() -> {
                ChatMessageStore.noteFriendMessage(payload.partner(), payload.partnerName(),
                    payload.sentAt(), payload.outgoing());
                ChatMessageStore.rememberPlayer(payload.partner(), payload.partnerName(), payload.partnerName());
                if (!payload.quoteContent().isEmpty()) {
                    ChatMessageStore.setPendingReply(payload.quoteContent(), payload.quoteSender());
                }
                ChatMessageStore.addMessage(net.minecraft.text.Text.literal(payload.content()), payload.sender(),
                    net.minecraft.text.Text.literal(payload.senderName()), false, payload.senderName(), true,
                    payload.partnerName(), payload.outgoing());
            }));
        ClientPlayNetworking.registerGlobalReceiver(ALFriendsOpenChatPayload.ID, (payload, context) ->
            context.client().execute(() -> {
                ChatMessageStore.rememberFriend(payload.target(), payload.targetName());
                ChatMessageStore.rememberPlayer(payload.target(), payload.targetName(), payload.targetName());
                GuiCompat.setScreen(context.client(), new ChatBubbleScreen(String.valueOf(new char[0]), payload.target(), payload.targetName()));
            }));
        ClientPlayNetworking.registerGlobalReceiver(ALFriendsContactsPayload.ID, (payload, context) ->
            context.client().execute(() -> ChatMessageStore.syncFriendContacts(payload.contacts())));
        ClientPlayNetworking.registerGlobalReceiver(ALFriendsHistoryPayload.ID, (payload, context) ->
            context.client().execute(() -> ChatMessageStore.syncALFriendsHistory(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ALFriendsEmojiCatalogPayload.ID, (payload, context) ->
            context.client().execute(() -> ServerEmojiStore.setEntries(payload.entries())));
        //#endif

        // On disconnect: immediately set the volatile flag from the network thread.
        // This is thread-safe (volatile write) and ensures blurPanel() and all
        // render/tick paths see it on the very next frame 鈥?BEFORE mc.world becomes
        // null. We also disable ImageLoader entirely so no new downloads or texture
        // uploads start during the disconnect transition. ImageLoader is re-enabled
        // when the next world is entered (see tick handler below).
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ALFriendsBridge.setActive(false);
            ChatMessageStore.clearFriendContacts();
            ServerEmojiStore.clear();
            ChatMessageStore.clearServerPlayerPresentation();
            BlurRenderer.disconnecting = true;
            ImageLoader.setEnabled(false);
            privateChatShortcutWasDown = false;
        });

        //#if MC >= 26000
        //$$ HudElementRegistry.attachElementAfter(
        //$$     VanillaHudElements.CHAT,
        //$$     GuiCompat.id(ChatBubbleMod.MOD_ID, "chat_icon"),
        //$$     (graphics, deltaTracker) -> {
        //$$         if (!config.enabled()) return;
        //$$         if (BlurRenderer.isDisconnecting()) return;
        //$$         ChatBubbleHudOverlay.render(graphics);
        //$$     }
        //$$ );
        //#else
        //#if MC >= 12000
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (!config.enabled()) return;
            if (BlurRenderer.isDisconnecting()) return;
            ChatBubbleHudOverlay.render(drawContext);
        });
        //#else
        //$$ HudRenderCallback.EVENT.register((matrices, tickDelta) -> {
        //$$     if (!config.enabled()) return;
        //$$     ChatBubbleHudOverlay.render(new DrawContext(matrices));
        //$$ });
        //#endif
        //#endif

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // World state transitions must be checked even during disconnect,
            // otherwise the "entered new world" branch never runs and the
            // disconnecting flag + ImageLoader disabled state would stick forever.
            boolean inWorld = client.world != null && client.player != null;
            if (wasInWorld && !inWorld) {
                BlurRenderer.disconnecting = true;
                ImageLoader.setEnabled(false);
                if (client.currentScreen instanceof ChatBubbleScreen) {
                    client.setScreen(null);
                }
            }
            if (!wasInWorld && inWorld) {
                BlurRenderer.disconnecting = false;
                ImageLoader.setEnabled(true);
                ALFriendsBridge.hello();
            }
            wasInWorld = inWorld;

            // Short-circuit ALL remaining alfriendschat tick logic the moment disconnect
            // begins. The BlurRenderer.disconnecting flag is set from the network
            // thread the instant DISCONNECT fires 鈥?it is visible on the render
            // thread on the very next tick. We skip ImageLoader, history saves,
            // everything 鈥?any work that could interact with the tearing-down
            // world or GL state is deferred until the next world.
            if (BlurRenderer.isDisconnecting()) return;

            ImageLoader.tick();

            // 绾圭悊鍏ㄩ儴璧?drawTexture(Identifier) 鎳掑姞杞斤紙getTexture 鑷姩 new ResourceTexture锛夛紝F3+T 閲嶈浇鍚庤嚜鍔ㄩ噸璇昏祫婧愬寘鏂?PNG
            if (!config.enabled()) return;

            long window = client.getWindow().getHandle();
            boolean controlDown = org.lwjgl.glfw.GLFW.glfwGetKey(window,
                org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window,
                org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean tDown = org.lwjgl.glfw.GLFW.glfwGetKey(window,
                org.lwjgl.glfw.GLFW.GLFW_KEY_T) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean privateChatShortcutDown = controlDown && tDown;
            if (privateChatShortcutDown && !privateChatShortcutWasDown
                    && !(client.currentScreen instanceof ChatBubbleScreen)
                    && (client.currentScreen == null
                    || client.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)) {
                client.setScreen(new ChatBubbleScreen(""));
            }
            privateChatShortcutWasDown = privateChatShortcutDown;

            String key;
            if (client.world == null || client.player == null) {
                key = null;
            } else if (client.getServer() != null) {
                key = "SP:" + client.getServer().getSaveProperties().getLevelName();
            } else if (client.getCurrentServerEntry() != null) {
                key = "MP:" + client.getCurrentServerEntry().name;
            } else {
                key = "world";
            }
            ChatMessageStore.setCurrentWorld(key);
            ChatMessageStore.maybeAutoSave();

            if (client.currentScreen == null) {
                boolean leftDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                    client.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_1) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
                if (leftDown && !leftWasDown) {
                    double mx = client.mouse.getX() * (double)client.getWindow().getScaledWidth() / (double)client.getWindow().getWidth();
                    double my = client.mouse.getY() * (double)client.getWindow().getScaledHeight() / (double)client.getWindow().getHeight();
                    ChatMessageStore.FriendContact friend = ChatBubbleHudOverlay.getUnreadFriendAt(mx, my);
                    if (friend != null) {
                        ChatMessageStore.activateFriendConversation(friend);
                        client.setScreen(new ChatBubbleScreen("", friend.uuid(), friend.name()));
                    } else if (ChatBubbleHudOverlay.isMouseOverIcon(mx, my)) {
                        client.setScreen(new ChatBubbleScreen(""));
                    }
                }
                leftWasDown = leftDown;
            } else {
                leftWasDown = false;
            }
        });

        //#if MC < 26000
        //#if MC >= 12000
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) ->
            ScreenEvents.afterRender(screen).register((scr, g, mouseX, mouseY, delta) -> {
                if (config.enabled() && !BlurRenderer.isDisconnecting())
                    ChatBubbleHudOverlay.renderBannerForScreen(g);
            })
        );
        //#else
        //$$ ScreenEvents.BEFORE_INIT.register((client, screen, width, height) ->
        //$$     ScreenEvents.afterRender(screen).register((scr, matrices, mouseX, mouseY, delta) -> {
        //$$         if (config.enabled()) ChatBubbleHudOverlay.renderBannerForScreen(new DrawContext(matrices));
        //$$     })
        //$$ );
        //#endif
        //#endif

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
                @Override
                public Identifier getFabricId() {
                    //#if MC >= 12000
                    return Identifier.of(ChatBubbleMod.MOD_ID, "shader_reload");
                    //#else
                    //$$ return new Identifier(ChatBubbleMod.MOD_ID, "shader_reload");
                    //#endif
                }
                @Override
                //#if MC >= 26000
                //$$ public void onResourceManagerReload(ResourceManager manager) {
                //#else
                public void reload(ResourceManager manager) {
                //#endif
                    RoundRectRenderer.resetShader();
                }
            }
        );
    }
}
