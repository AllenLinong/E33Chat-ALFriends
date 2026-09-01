package com.alinegames.alfriends.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;

import com.alinegames.alfriends.client.config.ChatBubbleConfig;
import com.alinegames.alfriends.client.image.BracketCodec;
import com.alinegames.alfriends.client.image.ImageEntry;
import com.alinegames.alfriends.client.image.ImageLoader;
import com.alinegames.alfriends.client.image.ImageUploader;
import com.alinegames.alfriends.client.image.MediaClient;
import com.alinegames.alfriends.client.image.LocalImageSource;
import com.alinegames.alfriends.client.network.QuoteSyncPayload;
import com.alinegames.alfriends.client.texture.ColoredTextureRenderer;
import com.alinegames.alfriends.client.texture.UiElement;
import com.alinegames.alfriends.client.texture.UiTextureManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
//#if MC >= 12109
import net.minecraft.client.gui.Click;
//#endif
//#if MC >= 12000
import net.minecraft.client.gui.DrawContext;
//#else
//$$ import net.minecraft.client.util.math.MatrixStack;
//#endif
//#if MC >= 11900
import net.minecraft.client.gui.screen.ChatInputSuggestor;
//#endif
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.GameRenderer;
//#if MC >= 12102
import net.minecraft.client.render.RenderLayer;
//#endif
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Language;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.io.InputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class ChatBubbleScreen extends ChatScreen {

    // Layout
    private int panelX, panelW;
    private static final int TITLE_H = 24;
    private int titleY, msgTop, msgBottom, barTop;
    private static final int PAD = 8;
    private static final int BUBBLE_PAD_X = 6;
    private static final int BUBBLE_PAD_Y = 4;
    private static int avatarSize() {
        Integer a = ChatBubbleClientSetup.config().avatarSize();
        return a == null ? 20 : Math.max(12, Math.min(32, a));
    }
    private static int messageGap() {
        Integer g = ChatBubbleClientSetup.config().messageGap();
        return g == null ? 6 : Math.max(0, Math.min(12, g));
    }
    /** Bubble text target height px (clamped 5-14, default 9 = vanilla font height). */
    private static int bubbleSizePx() {
        return BubbleLayout.clampBubbleSize(ChatBubbleClientSetup.config().bubbleSize());
    }
    /** Bubble scale factor: px / vanilla font height (9). Default 9 -> 1.0 (unchanged behaviour). */
    private static float bubbleScale() {
        return BubbleLayout.scale(bubbleSizePx(), 9);
    }
    /** Scaled text wrap width in design units: a bigger bubble fits fewer chars per line. */
    private static int scaledWrapWidth(int bubbleMaxW, float scale) {
        return BubbleLayout.scaledWrapWidth(bubbleMaxW, scale);
    }
    private static final int NAME_H = 10;
    private static final int TIME_SEP_H = 14;
    static final int BAR_H = 26;
    private static final int SIDEBAR_W = 90;
    private static final int SIDEBAR_ITEM_H = 22;
    private static final int SIDEBAR_ICON_S = 20;
    private static final int CHAT_IMAGE_PREVIEW_MAX = 320;

    private ChatBubbleTheme.Colors c() {
        return theme().colors();
    }

    private ChatBubbleTheme theme() {
        return ChatBubbleTheme.fromConfig(ChatBubbleClientSetup.config().theme());
    }

    private static final int INPUT_H = 14;
    private static final int ICON_S = 14;

    static Identifier iconTex(String name) {
        String theme = ChatBubbleClientSetup.config().theme().toLowerCase();
        //#if MC >= 11900
        return Identifier.of("alfriendschat", "textures/gui/" + theme + "/" + name + ".png");
        //#else
        //$$ return new Identifier("alfriendschat", "textures/gui/" + theme + "/" + name + ".png");
        //#endif
    }


    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static String timeKey(long t) {
        return ChatMessageStore.timeKey(t, ChatBubbleClientSetup.config().timeSeparatorMinutes());
    }

    //#if MC >= 11900
    private ChatInputSuggestor commandSuggestions;
    //#else
    //$$ // commandSuggestions not available before 1.19
    //#endif
    private static int inputX, inputY;

    public static int getInputX() { return inputX; }
    public static int getInputY() { return inputY; }
    private final String initialText;
    private String historyBuffer = "";
    private int historyPos = -1;
    private int scrollOffset;
    private int maxScroll;
    private boolean scrollToBottom = true;
    private boolean firstRender = true;
    private static String savedInput = "";
    private static final int SKIN_CACHE_CAP = 256;
    private static final java.util.Map<UUID, Identifier> skinCache = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<UUID, Identifier> eldest) {
            return size() > SKIN_CACHE_CAP;
        }
    };

    final ChatEmojiPanel emojiPanel = new ChatEmojiPanel();
    final ChatSettingsMenu settingsMenu = new ChatSettingsMenu();
    final ChatSearchPanel searchPanel = new ChatSearchPanel();
    private TextFieldWidget searchInput;
    private final List<Integer> searchMatches = new ArrayList<>();
    private int searchMatchIdx;
    private int searchHighlightIndex = -1;
    final ChatQuickChatPanel quickChatPanel = new ChatQuickChatPanel();
    private TextFieldWidget quickChatInput;
    private static final int QUICK_CHAT_W = 140;
    private static boolean sidebarOpen = true;

    // Popup open animation timestamps (opening only; closing stays instant)
    private long settingsAnimStart, emojiAnimStart, quickAnimStart, searchAnimStart;
    private String whisperPartner;
    private int sidebarScrollOffset;
    private int sidebarMaxScroll;
    private TextFieldWidget sidebarSearchBox;
    private boolean friendsExpanded;
    private String pendingRecentRemoval;

    private long sidebarAnimStart;
    private boolean sidebarTargetOpen;
    private boolean sidebarAnimating;
    private UUID whisperPartnerUuid;

    private static final int SCROLLBAR_WIDTH = 6;
    private static final int MIN_THUMB_H = 8;
    private boolean scrollbarDragging;
    private int scrollbarDragStartY;
    private int scrollbarDragStartOffset;
    private int messageTotalH;
    private boolean scrollbarHovered;
    private float scrollbarAlpha;
    private static final int SCROLLBAR_HOVER_ZONE = 20;
    private boolean scrollAnimActive;
    private long scrollAnimStart;
    private float scrollAnimFrom;
    private float scrollAnimTo;
    private int scrollAnimDuration;
    private long lastScrollTime;

    private boolean showMentions;
    private boolean mentionNavigated;
    private final List<String> mentionCandidates = new ArrayList<>();
    private int mentionIdx;
    private String mentionFilter = "";

    private int contextMsgIndex = -1;
    private int contextX, contextY;
    private static final int CTX_W = 80;
    private static final int CTX_ITEM_H = 18;
    private static final int PROFILE_CARD_W = 132;
    private static final int PROFILE_CARD_H = 112;
    private static final int PROFILE_BUTTON_W = 36;
    private static final int PROFILE_BUTTON_H = 19;
    private int contextAvatarIndex = -1;
    private int contextAvatarX, contextAvatarY;
    private String imagePreviewUrl;
    private float imagePreviewScale = 1.0F;
    private double imagePreviewOffsetX;
    private double imagePreviewOffsetY;
    private boolean imagePreviewDragging;

    // Per-frame wrap cache: every message is measured once (layout pass) and
    // rendered once (bubble pass), and both call getMsgHeight -> wrapContent.
    // Without the cache each message gets re-wrapped 3x per frame.
    private final Map<ChatMessageStore.ChatMessage, Integer> msgHeightCache =
        new IdentityHashMap<>();

    // Image cards: parsed once per message (bracket strip + refs), invalidated
    // when ImageLoader flips any entry's state (VERSION bumps).
    private final Map<ChatMessageStore.ChatMessage, BracketCodec.ParseResult> imageParseCache =
        new IdentityHashMap<>();
    private int lastImageVersion = -1;
    private boolean uploading = false;
    private int uploadToastTicks = 0;
    // Safety-net "uploading" indicator: set to 60 (3s @ 20fps) when an upload
    // starts, cleared on completion/failure. Lets the toast render an orange
    // "uploading" hint and guards against the uploading flag hanging true if a
    // worker throws before reaching its client.execute() reset.
    private int uploadBusyTicks = 0;

    private final List<int[]> bubbleRects = new ArrayList<>();
    private final List<ClickableSpan> clickableSpans = new ArrayList<>();

    private int replyTargetIndex = -1;
    private int copyToastTicks;
    private int emoteHintTicks;
    private int actionToastTicks;
    private String actionToastKey;
    private String actionToastTarget;

    private int selectedMessageIndex = -1;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private boolean selectingText;
    private boolean chatFieldSelecting;

    private long animStart;
    private boolean closing;
    private static final int ANIM_MS = 150;
    private static final int NOTIF_H = 14;
    private int newMessageCount;
    private boolean hasNewMentionOrQuote;
    private int latestMentionIndex = -1;
    private int lastSeenMessageCount;
    private int notifCountLeft, notifCountRight;
    private int notifMentionLeft = -1, notifMentionRight = -1;
    private int notifBarTextY;

    public ChatBubbleScreen(String initialText) {
        //#if MC >= 12109
        // 1.21.9+: ChatScreen constructor gained a `draft` boolean parameter.
        super("", false);
        //#else
        //$$ super("");
        //#endif
        this.initialText = initialText;
    }

    public ChatBubbleScreen(String initialText, String whisperPartner) {
        this(initialText);
        this.whisperPartner = whisperPartner;
        this.whisperPartnerUuid = ChatMessageStore.findSeenUuid(whisperPartner);
    }

    public ChatBubbleScreen(String initialText, UUID whisperPartnerUuid, String whisperPartner) {
        this(initialText);
        this.whisperPartnerUuid = whisperPartnerUuid;
        this.whisperPartner = whisperPartner;
    }

    @Override
    protected void init() {
        historyPos = client.inGameHud.getChatHud().getMessageHistory().size();
        ChatMessageStore.setScreenOpen(true);
        ChatMessageStore.setOpenWhisperPartner(whisperPartner);
        sidebarOpen = true;
        sidebarTargetOpen = true;
        friendsExpanded = false;
        pendingRecentRemoval = null;
        historyPos = client.inGameHud.getChatHud().getMessageHistory().size();
        animStart = Util.getMeasuringTimeMs();
        closing = false;
        firstRender = true;

        int physicalW = ChatBubbleClientSetup.config().panelWidth();
        int guiScale = (int) Math.round(client.getWindow().getScaleFactor());
        panelW = Math.max(100, Math.min(physicalW / Math.max(1, guiScale), width));
        if (sidebarOpen) {
            panelX = SIDEBAR_W;
            sidebarAnimating = false; // sidebar is already in place; the panel's
            // own open animation (by style) handles its entrance 鈥?don't let the
            // sidebar state machine re-drive panelX (slides the whole panel)
        } else {
            panelX = 0;
            sidebarAnimating = false;
            sidebarTargetOpen = false;
        }
        if (panelX + panelW > width) panelW = width - panelX;
        titleY = 0;
        msgTop = titleY + TITLE_H + 1;
        barTop = height - BAR_H;
        msgBottom = barTop - 1;

        int ibY = barTop + (BAR_H - INPUT_H) / 2;
        inputY = ibY;
        inputX = panelX + 4 + ICON_S + 3;
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int inputW = sendX - ICON_S - 8 - inputX;

        chatField = new TextFieldWidget(textRenderer, inputX, ibY + 3, inputW, INPUT_H, Text.literal(""));
        chatField.setMaxLength(256);
        chatField.setDrawsBackground(false);
        int editColor = theme().usesLightControls() ? c().textSecondary() : c().textPrimary();
        chatField.setEditableColor(editColor);
        chatField.setUneditableColor(c().textMuted());
        chatField.setText(initialText.isEmpty() && ChatBubbleClientSetup.config().preserveInput() && !savedInput.isEmpty() ? savedInput : initialText);
        chatField.setChangedListener(this::onInputEdited);
        chatField.setFocusUnlocked(false);
        addDrawableChild(chatField);

        int cmdBgAlpha = theme().usesLightControls() ? 0x99 : 0xDD;
        //#if MC >= 11900
        commandSuggestions = new ChatInputSuggestor(client, this, chatField, textRenderer,
            false, false, 1, 10, true, ChatBubbleTheme.alphaBlend(c().panelBg(), cmdBgAlpha));
        //#endif
        //#if MC >= 11900
        commandSuggestions.setWindowActive(true);
        //#endif
        //#if MC >= 11900
        commandSuggestions.refresh();
        //#endif


        sidebarSearchBox = new TextFieldWidget(textRenderer, 2, 5, SIDEBAR_W - 5, SIDEBAR_SEARCH_H, Text.literal(""));
        sidebarSearchBox.setMaxLength(20);
        sidebarSearchBox.setDrawsBackground(false);
        sidebarSearchBox.setEditableColor(editColor);
        sidebarSearchBox.setUneditableColor(editColor);
        sidebarSearchBox.setVisible(sidebarOpen);
        sidebarSearchBox.setChangedListener(s -> sidebarScrollOffset = 0);
        sidebarSearchBox.setFocusUnlocked(true);
        if (sidebarOpen) sidebarSearchBox.setX(2);
        addDrawableChild(sidebarSearchBox);

        quickChatInput = new TextFieldWidget(textRenderer, 0, 0, QUICK_CHAT_W - 8, 12, Text.translatable("alfriendschat.menu.quick_chat"));
        quickChatInput.setMaxLength(256);
        quickChatInput.setDrawsBackground(false);
        quickChatInput.setEditableColor(editColor);
        quickChatInput.setUneditableColor(c().textMuted());
        quickChatInput.setVisible(false);
        quickChatInput.setFocusUnlocked(true);
        addDrawableChild(quickChatInput);

        searchInput = new TextFieldWidget(textRenderer, 0, 0, 160, 12, Text.translatable("alfriendschat.menu.search"));
        searchInput.setMaxLength(128);
        searchInput.setDrawsBackground(false);
        searchInput.setEditableColor(editColor);
        searchInput.setUneditableColor(c().textMuted());
        searchInput.setVisible(false);
        searchInput.setChangedListener(this::onSearchEdited);
        searchInput.setFocusUnlocked(true);
        addDrawableChild(searchInput);

        setFocused(chatField);
        // The chat field's initial text is set before setChangedListener binds,
        // so the open-time value (e.g. "/" from the chat key) never flows through
        // onInputEdited 鈥?sync it once so the IMBlocker IME state is correct.
        onInputEdited(chatField.getText());
    }

    private void rebuildLayout() {
        int physicalW = ChatBubbleClientSetup.config().panelWidth();
        int guiScale = (int) Math.round(client.getWindow().getScaleFactor());
        panelW = Math.max(100, Math.min(physicalW / Math.max(1, guiScale), width));
        if (panelX + panelW > width) panelW = width - panelX;
        titleY = 0;
        msgTop = titleY + TITLE_H + 1;
        barTop = height - BAR_H;
        msgBottom = barTop - 1;

        int ibY = barTop + (BAR_H - INPUT_H) / 2;
        inputY = ibY;
        inputX = panelX + 4 + ICON_S + 3;
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int inputW = sendX - ICON_S - 8 - inputX;

        if (chatField != null) {
            chatField.setX(inputX);
            chatField.setWidth(inputW);
            //#if MC >= 12000
            chatField.setY(ibY + 3);
            //#else
            //$$ GuiCompat.setWidgetY(chatField, ibY + 3);
            //#endif
        }
    }

    private String getDisplayTitle() {
        if (whisperPartner != null) return whisperPartner;
        return Text.translatable("alfriendschat.sidebar.public").getString();
    }

    private float getSidebarAnimProgress() {
        if (!ChatBubbleClientSetup.config().animationEnabled()) return sidebarOpen ? 1f : 0f;
        AnimationStyle style = AnimationStyle.parse(ChatBubbleClientSetup.config().panelAnimStyle());
        // Hamburger toggle always slides, regardless of the panel animation style
        if (sidebarAnimating) {
            long elapsed = Util.getMeasuringTimeMs() - sidebarAnimStart;
            float t = MathHelper.clamp((float) elapsed / ANIM_MS, 0f, 1f);
            float progress = Animation.styleCurve(AnimationStyle.SLIDE, t);
            return sidebarTargetOpen ? progress : 1.0f - progress;
        }
        // FADE/NONE have no horizontal displacement: the sidebar fades in place.
        if (style == AnimationStyle.FADE || style == AnimationStyle.NONE) return sidebarOpen ? 1f : 0f;
        if (!sidebarOpen) return 0f;
        return getAnimProgress(); // follow the panel's open animation
    }

    private int getSidebarScreenX() {
        return (int) ((getSidebarAnimProgress() - 1.0f) * SIDEBAR_W);
    }

    private void tickSidebarAnimation() {
        if (!sidebarAnimating) return;
        long elapsed = Util.getMeasuringTimeMs() - sidebarAnimStart;
        float t = MathHelper.clamp((float) elapsed / ANIM_MS, 0f, 1f);
        if (t >= 1f) {
            sidebarAnimating = false;
            sidebarOpen = sidebarTargetOpen;
            panelX = sidebarOpen ? SIDEBAR_W : 0;
            sidebarSearchBox.setX(2);
            sidebarSearchBox.setVisible(sidebarOpen);
            if (!sidebarOpen && sidebarSearchBox.isFocused()) setFocused(chatField);
            rebuildLayout();
            return;
        }
        float progress = getSidebarAnimProgress();
        panelX = (int) (SIDEBAR_W * progress);
        sidebarSearchBox.setX(2 + getSidebarScreenX());
        sidebarSearchBox.setVisible(progress > 0.01f);
        rebuildLayout();
    }

    private static final int SIDEBAR_SEARCH_H = 14;

    // 1.21.9+ removed DrawContext.drawBorder; emulate it with four fill() calls
    // using the exact same pixel coverage as the old vanilla implementation.
    private static void drawRectBorder(DrawContext g, int x, int y, int w, int h, int color) {
        //#if MC >= 12109
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
        //#else
        //#if MC >= 12000
        //$$ g.drawBorder(x, y, w, h, color);
        //#else
        //$$ g.fill(x, y, x + w, y + 1, color);
        //$$ g.fill(x, y + h - 1, x + w, y + h, color);
        //$$ g.fill(x, y + 1, x + 1, y + h - 1, color);
        //$$ g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
        //#endif
        //#endif
    }

    private void renderSidebar(DrawContext g, int mouseX, int mouseY, float alpha) {
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_BG), 0, 0, SIDEBAR_W, height, alpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), SIDEBAR_W - 1, 0, 1, height, alpha);

        int y = 2;
        int itemH = SIDEBAR_ITEM_H;

        int sbx = 2;
        int sby = 2;
        int sbw = SIDEBAR_W - 5;
        int sbh = SIDEBAR_SEARCH_H;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.INPUT_BG), sbx - 1, sby, sbw + 1, sbh, alpha);
        boolean hoverSearch = mouseX >= sbx - 1 && mouseX <= sbx + sbw && mouseY >= sby && mouseY <= sby + sbh;
        if (hoverSearch || sidebarSearchBox.isFocused())
            drawRectBorder(g, sbx - 1, sby, sbw + 1, sbh, c().textMuted());
        if (sidebarSearchBox.getText().isEmpty() && !sidebarSearchBox.isFocused()) {
            g.drawText(textRenderer, Text.translatable("alfriendschat.sidebar.search").getString(), sbx, sby + 3, c().textMuted(), false);
        }
        y = sby + sbh + 3;

        int nameX = 2 + SIDEBAR_ICON_S + 3;
        boolean isPublic = whisperPartner == null;
        boolean hoverTab = mouseX >= 0 && mouseX <= SIDEBAR_W && mouseY >= y && mouseY <= y + itemH;
        if (isPublic)
            ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_SELECTED), 0, y, SIDEBAR_W, itemH, alpha);
        else if (hoverTab)
            ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_HOVER), 0, y, SIDEBAR_W, itemH, alpha);
        drawTextureIconAlpha(g, iconTex("public_icon"), 2, y + 1, SIDEBAR_ICON_S, alpha);
        String publicLabel = Text.translatable("alfriendschat.sidebar.public").getString();
        g.drawText(textRenderer, publicLabel, nameX, y + 1, c().textPrimary(), false);
        ChatMessageStore.ChatMessage latestPub = ChatMessageStore.getLatestPublicMessage();
        if (latestPub != null) {
            int previewMaxW = SIDEBAR_W - nameX - 4;
            String preview = ChatMessageStore.singleLine(latestPub.content().getString());
            String previewDisplay = textRenderer.trimToWidth(preview, previewMaxW - textRenderer.getWidth("..."));
            if (!previewDisplay.equals(preview)) previewDisplay += "...";
            g.drawText(textRenderer, previewDisplay, nameX, y + 1 + textRenderer.fontHeight, c().textMuted(), false);
        }
        y += itemH + 2;


        if (client.player != null) {
            String filter = sidebarSearchBox.getText().toLowerCase().trim();
            boolean browsingFriends = friendsExpanded || !filter.isEmpty();
            boolean hoverFriends = mouseX >= 0 && mouseX <= SIDEBAR_W && mouseY >= y && mouseY <= y + itemH;
            if (hoverFriends)
                ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_HOVER), 0, y, SIDEBAR_W, itemH, alpha);
            String friendsLabel = Text.translatable("alfriendschat.sidebar.friends_toggle").getString();
            drawFullTextureIconAlpha(g, iconTex("friends"), 2, y + 3, SIDEBAR_ICON_S, alpha);
            g.drawText(textRenderer, friendsLabel, nameX, y + 7, c().textSecondary(), false);
            drawFullTextureIconAlpha(g, iconTex(browsingFriends ? "collapse" : "expand"),
                SIDEBAR_W - SIDEBAR_ICON_S - 3, y + 3, SIDEBAR_ICON_S, alpha);
            y += itemH + 2;

            var friends = browsingFriends
                ? ChatMessageStore.getFriendContacts()
                : ChatMessageStore.getRecentFriendContacts();

            int startY = y;
            int visibleBottom = msgBottom > 0 ? msgBottom : height - BAR_H;
            int totalH = 0;
            for (var friend : friends) {
                String name = friend.name();
                if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;
                if (ChatBubbleClientSetup.config().isSidebarHidden(name)) continue;
                totalH += itemH + 2;
            }

            if (totalH == 0) {
                int iconS = 32;
                drawTextureIconAlpha(g, iconTex("no_online"), (SIDEBAR_W - iconS) / 2, startY + 8, iconS, alpha);
                String emptyKey = browsingFriends
                    ? "alfriendschat.sidebar.no_friends"
                    : "alfriendschat.sidebar.no_recent";
                String noPlayers = Text.translatable(emptyKey).getString();
                int textW = textRenderer.getWidth(noPlayers);
                g.drawText(textRenderer, noPlayers,
                    (SIDEBAR_W - textW) / 2, startY + 8 + iconS + 4, c().textMuted(), false);
            } else {
                int maxSideScroll = Math.max(0, totalH - (visibleBottom - startY));
                sidebarMaxScroll = maxSideScroll;
                if (sidebarScrollOffset > maxSideScroll) sidebarScrollOffset = maxSideScroll;

                g.enableScissor(0, startY, SIDEBAR_W, visibleBottom);
                int scrollY = startY - sidebarScrollOffset;
                for (var friend : friends) {
                    String name = friend.name();
                    if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;
                    if (ChatBubbleClientSetup.config().isSidebarHidden(name)) continue;

                    if (scrollY + itemH > startY && scrollY < visibleBottom) {
                        boolean sel = name.equals(whisperPartner);
                        boolean hoverRow = mouseX >= 0 && mouseX <= SIDEBAR_W && mouseY >= scrollY && mouseY <= scrollY + itemH;
                        if (sel)
                            ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_SELECTED), 0, scrollY, SIDEBAR_W, itemH, alpha);
                        else if (hoverRow)
                            ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.SIDEBAR_HOVER), 0, scrollY, SIDEBAR_W, itemH, alpha);

                        Identifier skin = getSkin(friend.uuid(), friend.name());
                        drawPlayerHead(g, skin, 4, scrollY + 3, 16, 18, alpha);

                        int tipW = ChatMessageStore.hasUnreadWhisper(name) ? 16 : 0;
                        boolean confirmRemove = !browsingFriends && name.equalsIgnoreCase(pendingRecentRemoval);
                        if (confirmRemove) tipW = Math.max(tipW, textRenderer.getWidth(
                            Text.translatable("alfriendschat.sidebar.remove").getString()) + 5);
                        int maxNameW = SIDEBAR_W - nameX - 4 - tipW - 2;
                        String displayName = textRenderer.trimToWidth(name, maxNameW - textRenderer.getWidth("..."));
                        if (!displayName.equals(name)) displayName += "...";
                        g.drawText(textRenderer, displayName, nameX, scrollY + 1, c().textPrimary(), false);

                        ChatMessageStore.ChatMessage latest = ChatMessageStore.getLatestWhisperWith(name);
                        if (latest != null) {
                            String preview = ChatMessageStore.singleLine(latest.content().getString());
                            String previewDisplay = textRenderer.trimToWidth(preview, maxNameW - textRenderer.getWidth("..."));
                            if (!previewDisplay.equals(preview)) previewDisplay += "...";
                            g.drawText(textRenderer, previewDisplay, nameX, scrollY + 1 + textRenderer.fontHeight, c().textMuted(), false);
                        }

                        if (ChatMessageStore.hasUnreadWhisper(name)) {
                            int tipX = SIDEBAR_W - 16 - 2;
                            int tipY = scrollY + 3 + (int) (Math.abs(Math.sin(System.currentTimeMillis() / 300.0)) * 3);
                            drawTextureIconAlpha(g, iconTex("private_tip"), tipX, tipY, 16, alpha);
                        }
                        if (confirmRemove) {
                            String remove = Text.translatable("alfriendschat.sidebar.remove").getString();
                            int removeX = SIDEBAR_W - textRenderer.getWidth(remove) - 4;
                            g.drawText(textRenderer, remove, removeX, scrollY + 7, 0xFFFF6B6B, false);
                        }
                    }
                    scrollY += itemH + 2;
                }
                g.disableScissor();
            }
        }
    }

    private void insertMention(String name) {
        String text = chatField.getText();
        int atIdx = text.lastIndexOf('@');
        chatField.setText(text.substring(0, atIdx) + "@" + name + " ");
        //#if MC >= 12004
        chatField.setCursorToEnd(false);
        //#else
        //$$ chatField.setCursorToEnd();
        //#endif
        showMentions = false;
        mentionNavigated = false;
    }

    private void onInputEdited(String text) {
        showMentions = false;
        mentionNavigated = false;
        int atIdx = text.lastIndexOf('@');
        // Commands use vanilla selectors (@s/@p/...) instead of player names:
        // do not offer player-name completion inside a command.
        if (atIdx >= 0 && !text.startsWith("/") && client.player != null && client.player.networkHandler != null) {
            String after = text.substring(atIdx + 1);
            if (!after.contains(" ")) {
                mentionFilter = after.toLowerCase();
                mentionCandidates.clear();
                for (var info : client.player.networkHandler.getPlayerList()) {
                    //#if MC >= 12109
                    String name = info.getProfile().name();
                    //#else
                    //$$ String name = info.getProfile().getName();
                    //#endif
                    if (name.toLowerCase().contains(mentionFilter))
                        mentionCandidates.add(name);
                }
                mentionCandidates.sort(String::compareToIgnoreCase);
                mentionIdx = 0;
                showMentions = !mentionCandidates.isEmpty();
            }
        }
        //#if MC >= 11900
        if (commandSuggestions != null) {
            commandSuggestions.refresh();
        }
        //#endif
        // IMBlocker listens to vanilla ChatScreen.onChatFieldUpdate, which we
        // bypass; mirror its command-detection hook so the IME still switches
        // to English while typing a command. No-op when IMBlocker is absent.
        IMBlockerCompat.setCommandMode(chatField, text.startsWith("/"));
    }

    private void onSearchEdited(String text) {
        searchMatches.clear();
        searchMatchIdx = -1;
        searchHighlightIndex = -1;
        if (text.isEmpty()) return;
        String lower = text.toLowerCase();
        var msgs = ChatMessageStore.getMessages();
        for (int i = 0; i < msgs.size(); i++) {
            var msg = msgs.get(i);
            if (msg == null) continue;
            if (msg.content().getString().toLowerCase().contains(lower)
                || (msg.senderName() != null && msg.senderName().getString().toLowerCase().contains(lower)))
                searchMatches.add(i);
        }
        if (!searchMatches.isEmpty()) {
            searchMatchIdx = 0;
            searchHighlightIndex = searchMatches.get(0);
            jumpToMessage(searchHighlightIndex);
        }
    }

    @Override
    public void tick() {
        if (copyToastTicks > 0) copyToastTicks--;
        if (emoteHintTicks > 0) emoteHintTicks--;
        if (actionToastTicks > 0) actionToastTicks--;
        if (uploadBusyTicks > 0) uploadBusyTicks--;
        if (uploadToastTicks > 0) uploadToastTicks--;
        if (closing && Util.getMeasuringTimeMs() - animStart >= ANIM_MS)
            //#if MC >= 11700
            client.setScreen(null);
            //#else
            //$$ client.openScreen(null);
            //#endif
    }

    private void renderCustomBackground(DrawContext g, int x, int areaWidth, int areaHeight, float alpha) {
        var cfg = ChatBubbleClientSetup.config();
        if (!Boolean.TRUE.equals(cfg.customBackgroundEnabled()) || areaWidth <= 0 || areaHeight <= 0) return;
        CustomBackgroundTexture.Entry entry = CustomBackgroundTexture.get(cfg.customBackgroundPath());
        if (entry == null) return;

        String mode = cfg.customBackgroundMode() == null ? "cover" : cfg.customBackgroundMode();
        int sourceWidth = entry.width();
        int sourceHeight = entry.height();
        int drawX = x;
        int drawY = 0;
        int drawWidth = areaWidth;
        int drawHeight = areaHeight;
        float sourceX = 0;
        float sourceY = 0;
        int regionWidth = sourceWidth;
        int regionHeight = sourceHeight;

        if ("contain".equals(mode) || "center".equals(mode)) {
            float scale = "center".equals(mode) ? 1f
                : Math.min((float) areaWidth / sourceWidth, (float) areaHeight / sourceHeight);
            if ("center".equals(mode)) scale = Math.min(1f, scale);
            drawWidth = Math.max(1, Math.round(sourceWidth * scale));
            drawHeight = Math.max(1, Math.round(sourceHeight * scale));
            drawX = x + (areaWidth - drawWidth) / 2;
            drawY = (areaHeight - drawHeight) / 2;
        } else if ("cover".equals(mode)) {
            float targetRatio = (float) areaWidth / areaHeight;
            float sourceRatio = (float) sourceWidth / sourceHeight;
            if (sourceRatio > targetRatio) {
                regionWidth = Math.max(1, Math.round(sourceHeight * targetRatio));
                sourceX = (sourceWidth - regionWidth) / 2f;
            } else {
                regionHeight = Math.max(1, Math.round(sourceWidth / targetRatio));
                sourceY = (sourceHeight - regionHeight) / 2f;
            }
        }

        g.enableScissor(x, 0, x + areaWidth, areaHeight);
        int imageAlpha = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        DrawHelper.drawTexture(g, entry.id(), drawX, drawY, drawWidth, drawHeight,
            sourceX, sourceY, regionWidth, regionHeight, sourceWidth, sourceHeight,
            imageAlpha << 24 | 0x00FFFFFF);
        int dim = cfg.customBackgroundDim() == null ? 35 : Math.max(0, Math.min(100, cfg.customBackgroundDim()));
        int dimAlpha = Math.round(255f * dim / 100f * alpha);
        if (dimAlpha > 0) g.fill(x, 0, x + areaWidth, areaHeight, dimAlpha << 24);
        g.disableScissor();
    }

    //#if MC >= 12004
    //#if MC >= 26000
    @Override
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
    //#else
    @Override
    public void renderBackground(DrawContext g, int mouseX, int mouseY, float delta) {
    //#endif
    //#else
    //#if MC >= 12000
    //$$ @Override
    //$$ public void renderBackground(DrawContext g) {
    //#else
    //$$ @Override
    //$$ public void renderBackground(MatrixStack g) {
    //#endif
    //#endif
        // no-op: disable vanilla blur
    }

    //#if MC >= 26000
    @Override
    public void extractTransparentBackground(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        // no-op: on 26.x, extractRenderStateWithTooltipAndSubtitles() calls this
        // directly (bypassing extractBackground), drawing a dark fillGradient over
        // the world. Override to prevent the vanilla black mask.
    }
    //#endif

    private float getAnimProgress() {
        if (!ChatBubbleClientSetup.config().animationEnabled()) return 1.0f;
        AnimationStyle style = AnimationStyle.parse(ChatBubbleClientSetup.config().panelAnimStyle());
        if (style == AnimationStyle.NONE) return 1.0f;
        long elapsed = Util.getMeasuringTimeMs() - animStart;
        float t = MathHelper.clamp((float) elapsed / ANIM_MS, 0f, 1f);
        if (closing) return 1.0f - (t * t);
        return Animation.styleCurve(style, t);
    }

    // Popup open animation (opening only 鈥?closing stays instant). The panel
    // renders itself with the given alpha (per-element fade); ZOOM additionally
    // scales it in around the screen center with overshoot.
    private void renderPopupWithAnim(DrawContext g, long startMs, java.util.function.Function<Float, Runnable> renderer) {
        float alpha = 1f;
        float t = 1f;
        AnimationStyle style = AnimationStyle.parse(ChatBubbleClientSetup.config().popupAnimStyle());
        if (ChatBubbleClientSetup.config().animationEnabled() && style != AnimationStyle.NONE) {
            t = MathHelper.clamp((float) (Util.getMeasuringTimeMs() - startMs) / 150f, 0f, 1f);
            alpha = Animation.styleCurve(style, t);
        }
        Runnable render = renderer.apply(alpha);
        if (t >= 1f || style == AnimationStyle.NONE) { render.run(); return; }
        if (style == AnimationStyle.ZOOM) {
            //#if MC >= 12106
            g.getMatrices().pushMatrix();
            //#else
            //$$ g.getMatrices().push();
            //#endif
            float s = 0.85f + 0.15f * Animation.easeOutBack(alpha);
            //#if MC >= 12106
            g.getMatrices().translate(width / 2f, height / 2f);
            //#else
            //$$ g.getMatrices().translate(width / 2f, height / 2f, 0);
            //#endif
            //#if MC >= 12106
            g.getMatrices().scale(s, s);
            //#else
            //$$ g.getMatrices().scale(s, s, 1f);
            //#endif
            //#if MC >= 12106
            g.getMatrices().translate(-width / 2f, -height / 2f);
            //#else
            //$$ g.getMatrices().translate(-width / 2f, -height / 2f, 0);
            //#endif
            render.run();
            //#if MC >= 12106
            g.getMatrices().popMatrix();
            //#else
            //$$ g.getMatrices().pop();
            //#endif
        } else if (style == AnimationStyle.SLIDE) {
            // SLIDE: rise up from below while fading in
            //#if MC >= 12106
            g.getMatrices().pushMatrix();
            //#else
            //$$ g.getMatrices().push();
            //#endif
            //#if MC >= 12106
            g.getMatrices().translate(0, (1f - alpha) * 10f);
            //#else
            //$$ g.getMatrices().translate(0, (1f - alpha) * 10f, 0);
            //#endif
            render.run();
            //#if MC >= 12106
            g.getMatrices().popMatrix();
            //#else
            //$$ g.getMatrices().pop();
            //#endif
        } else {
            render.run();
        }
    }

    @Override
    //#if MC >= 12109
    public boolean keyPressed(net.minecraft.client.input.KeyInput key) {
        int keyCode = key.key();
        int scanCode = key.scancode();
        int modifiers = key.modifiers();
    //#else
    //$$ public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //#endif
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_C && (modifiers & 0x2) != 0 && selectedMessageIndex >= 0) {
            ChatMessageStore.ChatMessage selected = ChatMessageStore.getMessageAt(selectedMessageIndex);
            if (selected != null && selectionStart >= 0 && selectionEnd >= 0) {
                String value = selected.content().getString();
                int from = Math.max(0, Math.min(value.length(), Math.min(selectionStart, selectionEnd)));
                int to = Math.max(from, Math.min(value.length(), Math.max(selectionStart, selectionEnd)));
                client.keyboard.setClipboard(value.substring(from, to)); copyToastTicks = 30; return true;
            }
        }

        if (imagePreviewUrl != null && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            closeImagePreview();
            return true;
        }
        // Ctrl+V with an image in the clipboard uploads it and inserts the code.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_V && (modifiers & 0x2) != 0) {
            startUploadFromClipboard();
        }
        if (settingsMenu.visible && keyCode == 256) { settingsMenu.visible = false; return true; }
        if (emojiPanel.visible && keyCode == 256) { emojiPanel.visible = false; return true; }
        if (quickChatPanel.visible && keyCode == 256) {
            quickChatPanel.visible = false; quickChatInput.setVisible(false); setFocused(chatField); return true;
        }
        if (searchPanel.visible && keyCode == 256) { closeSearchPanel(); return true; }

        if (searchPanel.visible && !searchMatches.isEmpty()) {
            if (keyCode == 265) {
                searchMatchIdx = searchMatchIdx > 0 ? searchMatchIdx - 1 : searchMatches.size() - 1;
                searchHighlightIndex = searchMatches.get(searchMatchIdx);
                jumpToMessage(searchHighlightIndex); return true;
            }
            if (keyCode == 264) {
                searchMatchIdx = searchMatchIdx < searchMatches.size() - 1 ? searchMatchIdx + 1 : 0;
                searchHighlightIndex = searchMatches.get(searchMatchIdx);
                jumpToMessage(searchHighlightIndex); return true;
            }
            if (keyCode == 257 || keyCode == 335) { closeSearchPanel(); return true; }
        }

        if (keyCode == 258 && this.getFocused() != chatField) {
            GuiCompat.setWidgetFocused(sidebarSearchBox, false);
            GuiCompat.setWidgetFocused(searchInput, false);
            GuiCompat.setWidgetFocused(quickChatInput, false);
            setFocused(chatField);
        }

        if (sidebarSearchBox.isFocused()) {
            if (keyCode == 256 || keyCode == 257 || keyCode == 335) {
                GuiCompat.setWidgetFocused(sidebarSearchBox, false);
                setFocused(chatField); return true;
            }
        }

        if (showMentions) {
            if (keyCode == 258) { insertMention(mentionCandidates.get(mentionIdx)); return true; }
            if (keyCode == 256) { showMentions = false; mentionNavigated = false; return true; }
            if (keyCode == 265) { mentionIdx = mentionIdx > 0 ? mentionIdx - 1 : mentionCandidates.size() - 1; mentionNavigated = true; return true; }
            if (keyCode == 264) { mentionIdx = mentionIdx < mentionCandidates.size() - 1 ? mentionIdx + 1 : 0; mentionNavigated = true; return true; }
            if (keyCode == 257 || keyCode == 335) {
                // Only apply the highlighted candidate when the player actually
                // navigated it (arrow keys); otherwise Enter just sends the text.
                if (mentionNavigated) { insertMention(mentionCandidates.get(mentionIdx)); return true; }
            }
        }

        //#if MC >= 11900
        //#if MC >= 12109
        if (commandSuggestions != null && commandSuggestions.keyPressed(key))
        //#else
        //$$ if (commandSuggestions != null && commandSuggestions.keyPressed(keyCode, scanCode, modifiers))
        //#endif
            return true;
        //#endif
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB && this.getFocused() == chatField) {
            return true;
        }
        if (keyCode == 256) { onClose(); return true; }
        if (quickChatInput.isFocused() && (keyCode == 257 || keyCode == 335)) {
            String text = quickChatInput.getText().trim();
            if (!text.isEmpty()) {
                var phrases = new ArrayList<>(ChatBubbleClientSetup.config().quickChatPhrases());
                phrases.add(text);
                ChatBubbleClientSetup.saveConfig(ChatBubbleClientSetup.config().withQuickChatPhrases(phrases));
                quickChatInput.setText("");
            }
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            sendMessage(); return true;
        }
        if (keyCode == 265 && modifiers == 0 && this.getFocused() == chatField) { setChatFromHistory(-1); return true; }
        if (keyCode == 264 && modifiers == 0 && this.getFocused() == chatField) { setChatFromHistory(1); return true; }

        // 涓嶈皟 super.keyPressed锛? ChatScreen锛屽唴閮ㄨ闂?package-private chatInputSuggestor = null 鈫?NPE锛夈€?
        // self 瀹炵幇 Screen.keyPressed 绛変环鍒嗗彂锛氬厛缁?focused widget锛坈hatField TextFieldWidget 澶勭悊
        // backspace/鍒犻櫎/宸﹀彸/Home/End/Ctrl+A/C/V/X锛夛紝鍐?Tab/绠ご鐒︾偣瀵艰埅銆?
        //#if MC >= 12109
        // 1.21.9+: Element.keyPressed takes a KeyInput instead of (int,int,int).
        if (this.getFocused() != null && this.getFocused().keyPressed(key))
        //#else
        //$$ if (this.getFocused() != null && this.getFocused().keyPressed(keyCode, scanCode, modifiers))
        //#endif
            return true;
        //#if MC >= 12000
        net.minecraft.client.gui.navigation.GuiNavigation nav = switch (keyCode) {
            //#if MC >= 12109
            // 1.21.9+: Screen.hasShiftDown() was removed; use the shift modifier bit
            // carried by the KeyInput (set by GLFW on Shift+Tab).
            case 258 -> new net.minecraft.client.gui.navigation.GuiNavigation.Tab((modifiers & net.minecraft.client.util.InputUtil.GLFW_MOD_SHIFT) == 0);
            //#else
            //$$ case 258 -> new net.minecraft.client.gui.navigation.GuiNavigation.Tab(!Screen.hasShiftDown());
            //#endif
            case 262 -> new net.minecraft.client.gui.navigation.GuiNavigation.Arrow(net.minecraft.client.gui.navigation.NavigationDirection.RIGHT);
            case 263 -> new net.minecraft.client.gui.navigation.GuiNavigation.Arrow(net.minecraft.client.gui.navigation.NavigationDirection.LEFT);
            case 264 -> new net.minecraft.client.gui.navigation.GuiNavigation.Arrow(net.minecraft.client.gui.navigation.NavigationDirection.DOWN);
            case 265 -> new net.minecraft.client.gui.navigation.GuiNavigation.Arrow(net.minecraft.client.gui.navigation.NavigationDirection.UP);
            default -> null;
        };
        if (nav != null) {
            net.minecraft.client.gui.navigation.GuiNavigationPath path = super.getNavigationPath(nav);
            if (path == null && nav instanceof net.minecraft.client.gui.navigation.GuiNavigation.Tab) {
                // Screen.blur() is private (inaccessible from subclass) in 1.20.1; replicate its
                // vanilla body (this.setFocused((Element)null)) via the public ParentElement API.
                this.setFocused((net.minecraft.client.gui.Element) null);
                path = super.getNavigationPath(nav);
            }
            if (path != null) this.switchFocus(path);
        }
        //#endif
        return false;
    }

    @Override
    //#if MC >= 12004
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
    //#else
    //$$ public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
    //#endif
        if (imagePreviewUrl != null) {
            imagePreviewScale = MathHelper.clamp(imagePreviewScale + (float) scrollY * 0.15F, 0.25F, 4.0F);
            return true;
        }

        if (quickChatPanel.visible) { quickChatPanel.handleScroll(scrollY); return true; }
        if (searchPanel.visible && !searchMatches.isEmpty()) {
            searchMatchIdx = MathHelper.clamp(searchMatchIdx - (int) scrollY, 0, searchMatches.size() - 1);
            searchHighlightIndex = searchMatches.get(searchMatchIdx);
            jumpToMessage(searchHighlightIndex); return true;
        }
        if (showMentions && !mentionCandidates.isEmpty()) {
            mentionIdx = MathHelper.clamp(mentionIdx - (int) scrollY, 0, mentionCandidates.size() - 1);
            mentionNavigated = true;
            return true;
        }
        int sidebarX = getSidebarScreenX();
        if ((sidebarOpen || sidebarAnimating) && mouseX >= sidebarX && mouseX <= sidebarX + SIDEBAR_W) {
            sidebarScrollOffset = MathHelper.clamp(sidebarScrollOffset - (int) (scrollY * 20), 0, sidebarMaxScroll);
            return true;
        }
        //#if MC >= 11900
        if (commandSuggestions != null && commandSuggestions.mouseScrolled(scrollY)) return true;
        //#endif
        scrollToBottom = false;
        lastScrollTime = Util.getMeasuringTimeMs();
        float newTarget = MathHelper.clamp(scrollOffset - (int) (scrollY * 40), 0, maxScroll);
        scrollAnimFrom = scrollOffset;
        scrollAnimTo = newTarget;
        scrollAnimStart = Util.getMeasuringTimeMs();
        if (!scrollAnimActive) { scrollAnimDuration = 120; scrollAnimActive = true; }
        return true;
    }

    @Override
    //#if MC >= 12109
    public boolean mouseClicked(Click click, boolean inside) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
    //#else
    //$$ public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //#endif
        if (imagePreviewUrl != null) {
            if (button == 1) {
                closeImagePreview();
            } else if (button == 0) {
                imagePreviewDragging = true;
            }
            return true;
        }

        // Panel contents are translated by panelOffset during the open/close slide;
        // undo the shift here so hit-testing matches what is drawn. The sidebar and
        // EditBox render outside that translate (they set their own x), so they keep
        // the original coordinate.
        double origX = mouseX;
        if (isPanelSliding()) mouseX -= currentPanelOffset();

        // @mention popup click
        if (showMentions && button == 0) {
            //#if MC >= 12000
            int popupX = chatField.getX();
            //#else
            //$$ int popupX = GuiCompat.getWidgetX(chatField);
            //#endif
            int popupH = Math.min(mentionCandidates.size(), 8) * textRenderer.fontHeight + 4;
            //#if MC >= 12000
            int popupY = chatField.getY() - popupH - 2;
            //#else
            //$$ int popupY = GuiCompat.getWidgetY(chatField) - popupH - 2;
            //#endif
            //#if MC >= 12000
            if (popupY < msgTop) popupY = chatField.getY() + chatField.getHeight() + 2;
            //#else
            //$$ if (popupY < msgTop) popupY = GuiCompat.getWidgetY(chatField) + chatField.getHeight() + 2;
            //#endif
            int maxW = 60;
            for (String name : mentionCandidates) maxW = Math.max(maxW, textRenderer.getWidth(name));
            int popupW = maxW + 12;
            if (mouseX >= popupX && mouseX <= popupX + popupW && mouseY >= popupY && mouseY <= popupY + popupH) {
                int relY = (int) mouseY - popupY - 2;
                int idx = relY / textRenderer.fontHeight;
                int startIdx = Math.max(0, mentionIdx - Math.min(mentionCandidates.size(), 8) + 1);
                idx += startIdx;
                if (idx >= 0 && idx < mentionCandidates.size()) {
                    insertMention(mentionCandidates.get(idx)); return true;
                }
            }
        }

        // Sidebar clicks
        int sidebarX = getSidebarScreenX();
        if ((sidebarOpen || sidebarAnimating) && (button == 0 || button == 1)
                && origX >= sidebarX && origX <= sidebarX + SIDEBAR_W) {
            int searchY = 2;
            int searchH = SIDEBAR_SEARCH_H;
            if (button == 0 && mouseY >= searchY && mouseY <= searchY + searchH) {
                setFocused(sidebarSearchBox);
                GuiCompat.setWidgetFocused(chatField, false);
                return true;
            }
            if (sidebarSearchBox.isFocused()) setFocused(chatField);

            int y2 = searchY + searchH + 3;
            if (button == 0 && mouseY >= y2 && mouseY <= y2 + SIDEBAR_ITEM_H) {
                whisperPartner = null;
                ChatMessageStore.setOpenWhisperPartner(null);
                sidebarSearchBox.setText(""); setFocused(chatField); scrollToBottom = true; return true;
            }
            y2 += SIDEBAR_ITEM_H + 2;
            if (client.player != null) {
                String filter = sidebarSearchBox.getText().toLowerCase().trim();
                boolean browsingFriends = friendsExpanded || !filter.isEmpty();
                if (mouseY >= y2 && mouseY <= y2 + SIDEBAR_ITEM_H) {
                    if (button == 0) {
                        friendsExpanded = !friendsExpanded;
                        pendingRecentRemoval = null;
                        sidebarScrollOffset = 0;
                    }
                    return true;
                }
                y2 += SIDEBAR_ITEM_H + 2;
                var friends = browsingFriends
                    ? ChatMessageStore.getFriendContacts()
                    : ChatMessageStore.getRecentFriendContacts();
                int scrollY = y2 - sidebarScrollOffset;
                for (var friend : friends) {
                    String name = friend.name();
                    if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) continue;
                    if (ChatBubbleClientSetup.config().isSidebarHidden(name)) continue;
                    if (mouseY >= scrollY && mouseY <= scrollY + SIDEBAR_ITEM_H) {
                        if (button == 1 && !browsingFriends) {
                            pendingRecentRemoval = name;
                            return true;
                        }
                        if (button == 0 && !browsingFriends && name.equalsIgnoreCase(pendingRecentRemoval)) {
                            int removeW = textRenderer.getWidth(
                                Text.translatable("alfriendschat.sidebar.remove").getString()) + 8;
                            if (origX >= sidebarX + SIDEBAR_W - removeW) {
                                ChatMessageStore.removeRecentFriend(name);
                                pendingRecentRemoval = null;
                                if (name.equalsIgnoreCase(whisperPartner)) {
                                    whisperPartner = null;
                                    ChatMessageStore.setOpenWhisperPartner(null);
                                }
                                return true;
                            }
                        }
                        if (button == 0) {
                            ChatMessageStore.activateFriendConversation(friend);
                            whisperPartner = name;
                            ChatMessageStore.setOpenWhisperPartner(name);
                            friendsExpanded = false;
                            pendingRecentRemoval = null;
                            sidebarSearchBox.setText(""); setFocused(chatField); scrollToBottom = true; return true;
                        }
                    }
                    scrollY += SIDEBAR_ITEM_H + 2;
                }
            }
            if (button == 0) pendingRecentRemoval = null;
            return true;
        }


        if (button == 0 && contextAvatarIndex >= 0) { handleAvatarContextClick((int) mouseX, (int) mouseY); return true; }
        if (contextAvatarIndex >= 0) { contextAvatarIndex = -1; return true; }
        if (button == 0 && contextMsgIndex >= 0) { handleContextClick((int) mouseX, (int) mouseY); return true; }
        if (contextMsgIndex >= 0) { contextMsgIndex = -1; return true; }

        // Notification bar clicks
        if (button == 0 && newMessageCount > 0) {
            if (mouseX >= notifCountLeft && mouseX <= notifCountRight
                && mouseY >= notifBarTextY && mouseY <= notifBarTextY + textRenderer.fontHeight) {
                scrollToBottom = true; newMessageCount = 0; hasNewMentionOrQuote = false;
                latestMentionIndex = -1; lastSeenMessageCount = ChatMessageStore.getMessages().size(); return true;
            }
            if (hasNewMentionOrQuote && notifMentionLeft >= 0
                && mouseX >= notifMentionLeft && mouseX <= notifMentionRight
                && mouseY >= notifBarTextY && mouseY <= notifBarTextY + textRenderer.fontHeight) {
                jumpToMessage(latestMentionIndex); return true;
            }
        }

        if (button == 0 && replyTargetIndex >= 0 && isMouseOverReplyCancel(mouseX, mouseY)) {
            replyTargetIndex = -1; return true;
        }

        // Scrollbar interaction
        if (button == 0 && maxScroll > 0) {
            int trackX = panelX + panelW - SCROLLBAR_WIDTH;
            int effBottom = newMessageCount > 0 ? barTop - NOTIF_H - 1 : msgBottom;
            if (mouseX >= trackX && mouseX < trackX + SCROLLBAR_WIDTH
                && mouseY >= msgTop && mouseY < effBottom) {
                int trackH = effBottom - msgTop;
                int thumbH = Math.max(MIN_THUMB_H, (int) ((long) trackH * trackH / messageTotalH));
                thumbH = Math.min(thumbH, trackH);
                int travelRange = trackH - thumbH;
                int thumbY = msgTop + (int) ((long) scrollOffset * travelRange / maxScroll);
                if (mouseY < thumbY) { scrollOffset = Math.max(0, scrollOffset - trackH); }
                else if (mouseY > thumbY + thumbH) { scrollOffset = Math.min(maxScroll, scrollOffset + trackH); }
                else { scrollbarDragging = true; scrollbarDragStartY = (int) mouseY; scrollbarDragStartOffset = scrollOffset; }
                scrollToBottom = false; return true;
            }
        }

        //#if MC >= 11900
        //#if MC >= 12109
        // 1.21.9+: ChatInputSuggestor.mouseClicked takes a Click.
        if (commandSuggestions != null && commandSuggestions.mouseClicked(new Click(mouseX, mouseY, click.buttonInfo())))
        //#else
        //$$ if (commandSuggestions != null && commandSuggestions.mouseClicked((int) mouseX, (int) mouseY, button))
        //#endif
            return true;
        //#endif

        if (button == 0) {
            if (isMouseOverHamburger(mouseX, mouseY)) {
                if (!ChatBubbleClientSetup.config().animationEnabled()) {
                    sidebarOpen = !sidebarOpen; sidebarAnimating = false;
                    panelX = sidebarOpen ? SIDEBAR_W : 0;
                    sidebarSearchBox.setX(2); sidebarSearchBox.setVisible(sidebarOpen);
                    if (!sidebarOpen && sidebarSearchBox.isFocused()) setFocused(chatField);
                    rebuildLayout();
                } else if (sidebarAnimating) {
                    sidebarTargetOpen = !sidebarTargetOpen;
                    long elapsed = Util.getMeasuringTimeMs() - sidebarAnimStart;
                    float currentT = MathHelper.clamp((float) elapsed / ANIM_MS, 0f, 1f);
                    sidebarAnimStart = Util.getMeasuringTimeMs() - (long) ((1.0f - currentT) * ANIM_MS);
                } else {
                    sidebarTargetOpen = !sidebarOpen; sidebarAnimating = true;
                    sidebarAnimStart = Util.getMeasuringTimeMs();
                }
                return true;
            }
            if (mouseX >= panelX + panelW - 18 && mouseX <= panelX + panelW - 6
                && mouseY >= titleY + 6 && mouseY <= titleY + 18) { onClose(); return true; }
            if (settingsMenu.visible) {
                int action = settingsMenu.handleClick((int) mouseX, (int) mouseY, panelX, panelW, barTop, ICON_S);
                if (action >= 0) executeMenuAction(action);
                return true;
            }
            if (emojiPanel.visible) {
                String emojiText = emojiPanel.handleClick((int) mouseX, (int) mouseY, textRenderer, c(), panelX, panelW, barTop, ICON_S, PAD);
                if (emojiText != null && !emojiText.isEmpty()) {
                    if (emojiText.startsWith("@EMOTE:")) {
                        java.io.File f = new java.io.File(emojiText.substring(7));
                        if (f.isFile()) uploadEmote(f);
                    } else if (emojiText.startsWith("@EMOTE_DEL:")) {
                        java.io.File f = new java.io.File(emojiText.substring(11));
                        if (f.isFile()) EmoteStore.remove(f);
                    } else if (emojiText.equals("@EMOTE_ADD")) {
                        // 2.4.0 sync: no file picker 鈥?drag an image onto the window
                        // while the emote tab is open to import it
                        emoteHintTicks = 60;
                    } else {
                        chatField.write(emojiText);
                    }
                }
                return true;
            }
            if (quickChatPanel.visible) {
                // 杈撳叆妗嗚仛鐒︿笉渚濊禆 widget 鐐瑰嚮鍛戒腑閾捐矾锛?.21.1/yarn TextFieldWidget 鐐瑰嚮涓嶈嚜鍔ㄨ仛鐒︼級锛?
                // 鐩存帴鍑犱綍鍒ゅ畾鍛戒腑灏辫仛鐒︼紝瑕嗙洊鎵€鏈夋儏鍐?
                if (ChatQuickChatPanel.isInsideInput((int) mouseX, (int) mouseY, panelX, panelW, barTop,
                        ChatBubbleClientSetup.config().quickChatPhrases().size())) {
                    // 涓?sidebar 鎼滅储妗嗚仛鐒﹀悓娆撅紙Fabric 瀹炴祴闇€鏄惧紡澶辩劍涓昏緭鍏ユ锛屽惁鍒欑劍鐐归摼琚?chatField 鍗犵敤锛?
                    quickChatInput.setVisible(true);
                    setFocused(quickChatInput);
                    GuiCompat.setWidgetFocused(chatField, false);
                    return true;
                }
                int result = quickChatPanel.handleClick((int) mouseX, (int) mouseY, textRenderer, c(), panelX, panelW, barTop, quickChatInput);
                if (result >= 0) {
                    chatField.setText(ChatBubbleClientSetup.config().quickChatPhrases().get(result));
                    setFocused(chatField);
                } else if (result == -2) {
                    setFocused(quickChatInput);
                }
                return true;
            }
            if (searchPanel.visible) {
                if (searchPanel.isClickOnPanel((int) mouseX, (int) mouseY, panelX, panelW, barTop)) {
                    setFocused(searchInput); return true;
                }
                closeSearchPanel(); return true;
            }
            if (mouseY >= barTop) {
                if (handleIconClick((int) mouseX, (int) mouseY)) return true;
            }
        }

        // Avatar left-click action menu
        if (button == 0) {
            for (int[] r : bubbleRects) {
                ChatMessageStore.ChatMessage msg = ChatMessageStore.getMessageAt(r[4]);
                if (msg == null || msg.isSystem() || msg.isOwn()) continue;
                if (msg.rawPlayerName() == null || msg.rawPlayerName().isEmpty()) continue;
                int avatarX = r[0] - avatarSize() - 4;
                int avatarY = msg.replyContent() != null ? r[1] - textRenderer.fontHeight - 2 : r[1] - NAME_H;
                if (mouseX >= avatarX && mouseX <= avatarX + avatarSize()
                    && mouseY >= avatarY && mouseY <= avatarY + avatarSize()) {
                    contextAvatarIndex = r[4];
                    contextAvatarX = avatarX + avatarSize() + 4;
                    contextAvatarY = avatarY;
                    return true;
                }
            }
        }

        // Native-style text selection: left-drag across a message, then Ctrl+C.
        if (button == 0) {
            int[] hit = messageRectAt(mouseX, mouseY);
            if (hit != null) {
                ChatMessageStore.ChatMessage selected = ChatMessageStore.getMessageAt(hit[4]);
                if (selected != null && !selected.isSystem()) {
                    int offset = textOffsetAt(selected, hit, mouseX);
                    selectedMessageIndex = hit[4]; selectionStart = offset; selectionEnd = offset;
                    selectingText = true;
                    return true;
                }
            }
        }

        // Bubble right-click
        if (button == 1) {
            for (int[] r : bubbleRects) {
                if (mouseX >= r[0] && mouseX <= r[0] + r[2]
                    && mouseY >= r[1] && mouseY <= r[1] + r[3]) {
                    contextMsgIndex = r[4]; contextX = (int) mouseX; contextY = (int) mouseY;
                    return true;
                }
            }
        }

        // Image cards open an in-chat viewer instead of launching a browser.
        if (button == 0) {
            for (ClickableSpan span : clickableSpans) {
                if (span.imageUrl != null && mouseX >= span.x && mouseX <= span.x + span.w
                        && mouseY >= span.y && mouseY <= span.y + span.h
                        && !isMouseOverChatField(mouseX, mouseY)) {
                    openImagePreview(span.imageUrl);
                    return true;
                }
            }
        }

    // Clickable text
        if (button == 0) {
            Style style = getHoveredStyle(mouseX, mouseY);
            if (style != null && style.getClickEvent() != null) {
                //#if MC >= 12105
                // Renamed to clickEvent to avoid clashing with the `Click click` method
                // parameter introduced in 1.21.9+ (mouseClicked(Click, boolean)).
                ClickEvent clickEvent = style.getClickEvent();
                if (clickEvent instanceof ClickEvent.SuggestCommand sc) {
                    chatField.setText(sc.command()); return true;
                }
                if (clickEvent instanceof ClickEvent.OpenFile of) {
                    Util.getOperatingSystem().open(of.path()); return true;
                }
                if (clickEvent instanceof ClickEvent.OpenUrl url) {
                    String clickUrl = url.uri().toString();
                    if (clickUrl != null && (clickUrl.startsWith("http://") || clickUrl.startsWith("https://"))) {
                        //#if MC >= 26000
                        Screen.handleClickEvent(clickEvent, client, this);
                        //#else
                        //$$ Screen.handleClickEvent(style.getClickEvent(), client, this);
                        //#endif
                    }
                    return true;
                }
                if (clickEvent instanceof ClickEvent.RunCommand cmd) {
                    String command = cmd.command();
                    if (command.startsWith("/")) command = command.substring(1);
                    GuiCompat.sendCommand(client.player.networkHandler, command); return true;
                }
                if (clickEvent instanceof ClickEvent.CopyToClipboard ctc) {
                    client.keyboard.setClipboard(ctc.value()); return true;
                }
                //#else
                //$$ ClickEvent click = style.getClickEvent();
                //$$ if (click.getAction() == ClickEvent.Action.SUGGEST_COMMAND) {
                    //$$ chatField.setText(click.getValue()); return true;
                //$$ }
                //$$ if (click.getAction() == ClickEvent.Action.OPEN_FILE) {
                    //$$ java.io.File file = new java.io.File(click.getValue());
                    //$$ Util.getOperatingSystem().open(file); return true;
                //$$ }
                //$$ if (click.getAction() == ClickEvent.Action.OPEN_URL) {
                    //$$ // Local file:// links (e.g. legacy chatimage messages) are not
                    //$$ // browser URLs; opening them throws URISyntaxException. Only
                    //$$ // hand http(s) to the vanilla handler.
                    //$$ String clickUrl = click.getValue();
                    //$$ if (clickUrl != null && (clickUrl.startsWith("http://") || clickUrl.startsWith("https://"))) {
                        //$$ Screen.handleClickEvent(style.getClickEvent(), client, this);
                    //$$ }
                    //$$ return true;
                //$$ }
                //$$ Screen.handleClickEvent(style.getClickEvent(), client, this); return true;
                //#endif
            }
        }
        //#if MC >= 12109
        // 1.21.9+: ClickableWidget.mouseClicked takes (Click, boolean). The chat field
        // is drawn outside the panel slide, so use the unshifted origX coordinate.
        if (button == 0 && origX >= chatField.getX() && origX < chatField.getX() + chatField.getWidth()
                && mouseY >= chatField.getY() && mouseY < chatField.getY() + chatField.getHeight()) {
            setFocused(chatField);
            chatFieldSelecting = true;
        }
        return this.chatField.mouseClicked(new Click(origX, mouseY, click.buttonInfo()), inside);
        //#else
        //$$ int chatFieldX = GuiCompat.getWidgetX(chatField);
        //$$ int chatFieldY = GuiCompat.getWidgetY(chatField);
        //$$ if (button == 0 && origX >= chatFieldX && origX < chatFieldX + chatField.getWidth()
        //$$         && mouseY >= chatFieldY && mouseY < chatFieldY + chatField.getHeight()) {
        //$$     setFocused(chatField);
        //$$     chatFieldSelecting = true;
        //$$ }
        //$$ return this.chatField.mouseClicked(origX, mouseY, button);
        //#endif
    }

    private boolean isMouseOverChatField(double mouseX, double mouseY) {
        if (chatField == null || !chatField.isVisible()) return false;
        //#if MC >= 12000
        return mouseX >= chatField.getX() && mouseX <= chatField.getX() + chatField.getWidth()
            && mouseY >= chatField.getY() && mouseY <= chatField.getY() + chatField.getHeight();
        //#else
        //$$ return mouseX >= GuiCompat.getWidgetX(chatField) && mouseX <= GuiCompat.getWidgetX(chatField) + chatField.getWidth()
        //$$     && mouseY >= GuiCompat.getWidgetY(chatField) && mouseY <= GuiCompat.getWidgetY(chatField) + chatField.getHeight();
        //#endif
    }

    @Override
    //#if MC >= 12109
    public boolean mouseDragged(Click click, double dx, double dy) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        double dragX = dx;
        double dragY = dy;
    //#else
    //$$ public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    //#endif
        if (imagePreviewUrl != null) {
            if (imagePreviewDragging && button == 0) {
                imagePreviewOffsetX += dragX;
                imagePreviewOffsetY += dragY;
            }
            return true;
        }
        if (selectingText && button == 0) {
            int[] hit = messageRectAt(mouseX, mouseY);
            if (hit != null && hit[4] == selectedMessageIndex) {
                ChatMessageStore.ChatMessage selected = ChatMessageStore.getMessageAt(selectedMessageIndex);
                if (selected != null) selectionEnd = textOffsetAt(selected, hit, mouseX);
            }
            return true;
        }
        if (chatFieldSelecting && button == 0) {
            //#if MC >= 12109
            return chatField.mouseDragged(click, dragX, dragY);
            //#else
            //$$ return chatField.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            //#endif
        }
        if (scrollbarDragging && maxScroll > 0) {
            lastScrollTime = Util.getMeasuringTimeMs();
            int effBottom = newMessageCount > 0 ? barTop - NOTIF_H - 1 : msgBottom;
            int trackH = effBottom - msgTop;
            int thumbH = Math.max(MIN_THUMB_H, (int) ((long) trackH * trackH / messageTotalH));
            thumbH = Math.min(thumbH, trackH);
            int travelRange = trackH - thumbH;
            if (travelRange > 0) {
                int deltaY = (int) mouseY - scrollbarDragStartY;
                float newTarget = MathHelper.clamp(scrollbarDragStartOffset + (int) ((long) deltaY * maxScroll / travelRange), 0, maxScroll);
                scrollAnimFrom = scrollOffset; scrollAnimTo = newTarget;
                scrollAnimStart = Util.getMeasuringTimeMs();
                if (!scrollAnimActive) { scrollAnimDuration = 80; scrollAnimActive = true; }
            }
            return true;
        }
        //#if MC >= 12109
        // 1.21.9+: mouseDragged takes (Click, double, double).
        return super.mouseDragged(click, dragX, dragY);
        //#else
        //$$ return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        //#endif
    }

    @Override
    //#if MC >= 12109
    public boolean mouseReleased(Click click) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
    //#else
    //$$ public boolean mouseReleased(double mouseX, double mouseY, int button) {
    //#endif
        if (imagePreviewUrl != null) {
            if (button == 0) imagePreviewDragging = false;
            return true;
        }
        if (selectingText && button == 0) { selectingText = false; return true; }
        if (chatFieldSelecting && button == 0) {
            chatFieldSelecting = false;
            //#if MC >= 12109
            return chatField.mouseReleased(click);
            //#else
            //$$ return chatField.mouseReleased(mouseX, mouseY, button);
            //#endif
        }
        if (scrollbarDragging) { scrollbarDragging = false; return true; }
        //#if MC >= 12109
        // 1.21.9+: mouseReleased takes a Click.
        return super.mouseReleased(click);
        //#else
        //$$ return super.mouseReleased(mouseX, mouseY, button);
        //#endif
    }

    private boolean handleIconClick(int mx, int my) {
        int iconY = barTop + (BAR_H - ICON_S) / 2;
        int gearX = panelX + 4;
        if (mx >= gearX && mx <= gearX + ICON_S && my >= iconY && my <= iconY + ICON_S) {
            if (emojiPanel.visible) emojiPanel.visible = false;
            if (searchPanel.visible) closeSearchPanel();
            boolean opening = !settingsMenu.visible;
            settingsMenu.visible = opening;
            if (opening) settingsAnimStart = Util.getMeasuringTimeMs();
            return true;
        }
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int emojiX = sendX - ICON_S - 6;
        if (mx >= emojiX && mx <= emojiX + ICON_S && my >= iconY && my <= iconY + ICON_S) {
            if (settingsMenu.visible) settingsMenu.visible = false;
            if (searchPanel.visible) closeSearchPanel();
            boolean opening = !emojiPanel.visible;
            emojiPanel.visible = opening;
            if (opening) emojiAnimStart = Util.getMeasuringTimeMs();
            showMentions = false;
            if (emojiPanel.visible) emojiPanel.scroll = 0;
            return true;
        }
        if (mx >= sendX && mx <= sendX + ICON_S && my >= iconY && my <= iconY + ICON_S) {
            sendMessage(); return true;
        }
        return false;
    }


    // ---- Local image upload (2.3.11) ----

    /** OS file drag onto the window (vanilla drop hook): upload the first image dropped. */
    //#if MC >= 12104
    @Override
    public void onFilesDropped(List<java.nio.file.Path> paths) {
    //#else
    //$$ @Override
    //$$ public void filesDragged(List<java.nio.file.Path> paths) {
    //#endif
        if (uploading) return;
        // 2.4.0 sync: with the emote-pack tab open, a dropped image is imported
        // as an emote instead of being uploaded as a chat image
        if (emojiPanel.visible && emojiPanel.tab == 2) {
            boolean any = false;
            for (java.nio.file.Path p : paths) {
                if (EmoteStore.add(p.toFile())) any = true;
            }
            if (any) {
                client.execute(() -> setFocused(chatField));
                return;
            }
        }
        for (java.nio.file.Path p : paths) {
            String l = p.getFileName().toString().toLowerCase();
            if (l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg")
                    || l.endsWith(".gif") || l.endsWith(".bmp") || l.endsWith(".webp")) {
                upload(p.toFile());
                // The OS drop can steal window focus; give it back to the chat input
                // so typing keeps working right after a drag.
                client.execute(() -> setFocused(chatField));
                return;
            }
        }
    }

    private void startUploadFromClipboard() {
        if (uploading) return;
        LocalImageSource.PreparedImage prep;
        try {
            prep = LocalImageSource.fromClipboard();
        } catch (Throwable t) {
            prep = null;
        }
        if (prep == null) return; // no image in clipboard 鈥?let vanilla paste text
        final LocalImageSource.PreparedImage fprep = prep;
        uploading = true;
        uploadBusyTicks = 60;
        ImageLoader.executor().execute(() -> {
            try {
                finishUpload(fprep, "clipboard");
            } catch (Throwable t) {
                E33Log.warn("[alfriendschat] clipboard upload worker crashed", t);
                client.execute(() -> { uploading = false; uploadBusyTicks = 0; uploadToastTicks = 60; });
            }
        });
    }

    private void upload(java.io.File f) {
        upload(f, false);
    }

    /** Emote-pack send: uploads and inserts [[E33Emote,...]] so it renders at the
     * small emote size instead of a full-size chat image. */
    private void uploadEmote(java.io.File f) {
        upload(f, true);
    }

    private void upload(java.io.File f, boolean emote) {
        uploading = true;
        uploadBusyTicks = 60;
        ImageLoader.executor().execute(() -> {
            try {
                LocalImageSource.PreparedImage prep = LocalImageSource.fromFile(f);
                if (prep == null) {
                    client.execute(() -> { uploading = false; uploadBusyTicks = 0; uploadToastTicks = 60; });
                    return;
                }
                finishUpload(prep, f.getName(), emote);
            } catch (Throwable t) {
                E33Log.warn("[alfriendschat] file upload worker crashed", t);
                client.execute(() -> { uploading = false; uploadBusyTicks = 0; uploadToastTicks = 60; });
            }
        });
    }

    private void finishUpload(LocalImageSource.PreparedImage prep, String srcName) {
        finishUpload(prep, srcName, false);
    }

    private static String contentType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg" : "image/png";
    }

    private void finishUpload(LocalImageSource.PreparedImage prep, String srcName, boolean emote) {
        com.alinegames.alfriends.client.config.ChatBubbleConfig cfg = ChatBubbleClientSetup.config();
        String uploadedUrl = ImageUploader.upload(prep.bytes(), prep.fileName(),
            cfg.uploadUrl(), cfg.uploadField(), cfg.uploadExtra(), cfg.uploadResponse());
        final String url = uploadedUrl;
        E33Log.info("[alfriendschat] upload {} -> {}", srcName, url == null ? "FAILED" : url);
        client.execute(() -> {
            uploading = false;
            uploadBusyTicks = 0;
            if (url == null) {
                uploadToastTicks = 60;
                return;
            }
            String code = emote ? "[[E33Emote,url=" + url + "]]" : "[[CICode,url=" + url + "]]";
            String cur = chatField.getText();
            if (cur.contains("[[CICode,url=file://")) {
                // Replace the local file:// CICode (chatimage drag/paste) with
                // the real upload URL instead of appending a second link.
                cur = cur.replaceFirst("\\[\\[CICode,url=file://[^]]*]]", code);
            } else {
                cur = cur.isEmpty() ? code : cur + " " + code;
            }
            ChatMessageStore.debugLog("[alfriendschat] upload replace | before='" + chatField.getText() + "' | after='" + cur + "'");
            chatField.setText(cur);
            //#if MC >= 12004
            chatField.setCursorToEnd(false);
            //#else
            //$$ chatField.setCursorToEnd();
            //#endif
        });
    }

    private void handleContextClick(int mx, int my) {
        int menuH = CTX_ITEM_H * 2 + 2;
        int menuX = Math.min(contextX, panelX + panelW - CTX_W - 2);
        int menuY = contextY - menuH;
        if (menuY < msgTop) menuY = contextY + 4;
        if (mx >= menuX && mx <= menuX + CTX_W) {
            if (my >= menuY && my <= menuY + CTX_ITEM_H) {
                ChatMessageStore.ChatMessage msg = ChatMessageStore.getMessageAt(contextMsgIndex);
                if (msg != null) { client.keyboard.setClipboard(msg.content().getString()); copyToastTicks = 30; }
            } else if (my >= menuY + CTX_ITEM_H + 1 && my <= menuY + CTX_ITEM_H * 2 + 1) {
                replyTargetIndex = contextMsgIndex;
            }
        }
        contextMsgIndex = -1;
    }

    private void handleAvatarContextClick(int mx, int my) {
        int menuH = CTX_ITEM_H * 3 + 4;
        int menuX = Math.min(contextAvatarX, panelX + panelW - CTX_W - 2);
        int menuY = MathHelper.clamp(contextAvatarY, msgTop + 2,
            Math.max(msgTop + 2, msgBottom - menuH - 2));
        ChatMessageStore.ChatMessage msg = ChatMessageStore.getMessageAt(contextAvatarIndex);
        String name = msg != null ? msg.rawPlayerName() : null;
        if (name == null || name.isEmpty()) {
            contextAvatarIndex = -1;
            return;
        }
        if (mx >= menuX && mx <= menuX + CTX_W) {
            if (my >= menuY && my <= menuY + CTX_ITEM_H) {
                handleAvatarPrimaryAction(name);
            } else if (my >= menuY + CTX_ITEM_H + 2
                    && my <= menuY + CTX_ITEM_H * 2 + 2) {
                boolean request = ChatMessageStore.useTpa();
                GuiCompat.sendChat(client.player.networkHandler,
                    "/" + (request ? "tpa " : "tp ") + name);
                showActionToast(request ? "alfriendschat.toast.tpa_sent" : "alfriendschat.toast.tp_sent", name);
            } else if (my >= menuY + CTX_ITEM_H * 2 + 4 && my <= menuY + menuH) {
                blockPlayer();
            }
        }
        contextAvatarIndex = -1;
    }

    private void handleAvatarPrimaryAction(String name) {
        if (whisperPartner != null) {
            GuiCompat.sendChat(client.player.networkHandler, "/tpahere " + name);
            showActionToast("alfriendschat.toast.tpahere_sent", name);
        } else if (ChatMessageStore.isFriendContact(name)) {
            openWhisper(name);
        } else {
            GuiCompat.sendChat(client.player.networkHandler, "/alfs add " + name);
            showActionToast("alfriendschat.toast.friend_request_sent", name);
        }
    }

    private void showActionToast(String key, String target) {
        actionToastKey = key;
        actionToastTarget = target;
        actionToastTicks = 60;
    }

    private void openWhisper(String name) {
        whisperPartner = name;
        ChatMessageStore.setOpenWhisperPartner(name);
        if (sidebarSearchBox != null) sidebarSearchBox.setText("");
        setFocused(chatField);
        scrollToBottom = true;
    }

    // 灞忚斀/鍙栨秷灞忚斀鍙抽敭鑿滃崟鐩爣鐜╁锛氬悕鍗曞嵆鏃剁敓鏁?+ 浠庢秷鎭垪琛ㄦ竻鎺夊巻鍙?+ 绔嬪嵆鍐欑洏
    private void blockPlayer() {
        ChatMessageStore.ChatMessage msg = ChatMessageStore.getMessageAt(contextAvatarIndex);
        if (msg == null) return;
        String name = msg.rawPlayerName();
        if (name == null || name.isEmpty()) {
            name = msg.senderName() != null ? msg.senderName().getString() : null;
        }
        if (name == null || name.isEmpty()) return;
        String targetName = name;
        boolean blocked = ChatMessageStore.isPlayerBlocked(targetName, msg.senderName(),
            ChatBubbleClientSetup.config().blockedPlayers());
        List<String> next = new ArrayList<>(ChatBubbleClientSetup.config().blockedPlayers());
        if (blocked) {
            next.removeIf(v -> v != null && v.equalsIgnoreCase(targetName));
            GuiCompat.sendChat(client.player.networkHandler, "/alfs unblock " + targetName);
        } else {
            next.add(targetName);
            GuiCompat.sendChat(client.player.networkHandler, "/alfs block " + targetName);
        }
        ChatBubbleClientSetup.saveConfig(ChatBubbleClientSetup.config().withBlockedPlayers(next));
    }

    //#if MC >= 12000
    //#if MC >= 26000
    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
    //#else
    @Override
    public void render(DrawContext g, int mouseX, int mouseY, float delta) {
    //#endif
    //#else
    //$$ @Override
    //$$ public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
    //$$ DrawContext g = new DrawContext(matrices);
    //#endif
        // Guard: if the world/player is null (server disconnect in progress),
        // skip ALL rendering 鈥?GL state may be transitional and any draw call
        // (including child widget rendering) can trigger unsafe GL operations.
        // Do NOT call super.render() 鈥?ChatScreen.render() accesses package-private
        // chatInputSuggestor and may trigger GL operations unsafe during disconnect.
        var mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            return;
        }

        tickSidebarAnimation();

        float anim = getAnimProgress();
        AnimationStyle pstyle = AnimationStyle.parse(ChatBubbleClientSetup.config().panelAnimStyle());
        int panelOffset = (pstyle == AnimationStyle.SLIDE) ? currentPanelOffset() : 0;
        boolean zoom = (pstyle == AnimationStyle.ZOOM) && anim < 1f;
        float panelScale = 1f;
        if (zoom) panelScale = 0.8f + 0.2f * Animation.easeOutBack(anim);

        //#if MC >= 12106
        g.getMatrices().pushMatrix();
        //#else
        //$$ g.getMatrices().push();
        //#endif
        //#if MC >= 12106
        g.getMatrices().translate(panelOffset, 0);
        //#else
        //$$ g.getMatrices().translate(panelOffset, 0, 0);
        //#endif
        if (zoom) {
            float cx = panelX + panelW / 2f;
            //#if MC >= 12106
            g.getMatrices().translate(cx, height / 2f);
            //#else
            //$$ g.getMatrices().translate(cx, height / 2f, 0);
            //#endif
            //#if MC >= 12106
            g.getMatrices().scale(panelScale, panelScale);
            //#else
            //$$ g.getMatrices().scale(panelScale, panelScale, 1f);
            //#endif
            //#if MC >= 12106
            g.getMatrices().translate(-cx, -height / 2f);
            //#else
            //$$ g.getMatrices().translate(-cx, -height / 2f, 0);
            //#endif
        }

        float panelOpacity = ChatBubbleClientSetup.config().panelOpacity() / 100f * anim;
        // When sidebar is synced to main animation, extend panel bg to
        // sidebar's right edge so there's no gap between them. Only SLIDE
        // moves horizontally (the panel slides in); FADE/ZOOM keep the bg
        // in place and fade/scale it in place instead.
        int fillLeft = (!sidebarAnimating && sidebarOpen && pstyle == AnimationStyle.SLIDE)
            ? (int)(anim * SIDEBAR_W) : panelX;
        if (ChatBubbleClientSetup.config().blurEnabled() && panelOpacity < 0.999f && !zoom) {
            //#if MC >= 26000
            //#if MC < 26000
            // 1.21.11 render-state: native full-screen blur at the layer boundary 鈥?
            // the old mid-frame GL blit can't capture the deferred GUI content,
            // so it produced no visible blur there.
            g.applyBlur();
            //#else
            //$$ BlurRenderer.blurPanel(g, panelOffset + fillLeft, 0, panelX + panelW - fillLeft, height);
            //#endif
            //#else
            //#if MC >= 12000
            //#if MC < 12106
            //$$ g.draw();
            //#endif
            //#endif
            //$$ BlurRenderer.blurPanel(g, panelOffset + fillLeft, 0, panelX + panelW - fillLeft, height);
            //#endif
        }
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.PANEL_BG),
            fillLeft, 0, panelX + panelW - fillLeft, height, panelOpacity);
        renderCustomBackground(g, fillLeft, panelX + panelW - fillLeft, height, anim);

        renderTitleBar(g, mouseX, mouseY, anim);
        renderMessages(g, mouseX, mouseY);
        Style hovered = getHoveredStyle(mouseX, mouseY);
        if (hovered != null && hovered.getHoverEvent() != null) {
            //#if MC >= 12000
            //#if MC < 26000
            g.drawHoverEvent(textRenderer, hovered, mouseX, mouseY);
            //#else
            //$$ var he = hovered.getHoverEvent();
            //$$ if (he instanceof net.minecraft.text.HoverEvent.ShowText st) {
            //$$     g.setTooltipForNextFrame(st.value(), mouseX, mouseY);
            //$$ } else if (he instanceof net.minecraft.text.HoverEvent.ShowItem si) {
            //$$     g.setTooltipForNextFrame(textRenderer, si.item().create(), mouseX, mouseY);
            //$$ } else if (he instanceof net.minecraft.text.HoverEvent.ShowEntity se) {
            //$$     g.setComponentTooltipForNextFrame(textRenderer, se.entity().getTooltipLines(), mouseX, mouseY);
            //$$ }
            //#endif
            //#endif
        }

        //#if MC >= 12106
        g.getMatrices().translate(0, 0);
        //#else
        //$$ g.getMatrices().translate(0, 0, 50);
        //#endif
        renderNotificationBar(g, mouseX, mouseY);
        renderReplyBar(g, mouseX, mouseY);
        renderContextMenu(g, mouseX, mouseY);
        renderAvatarContextMenu(g, mouseX, mouseY);
        renderToast(g);
        renderBottomBar(g, mouseX, mouseY, anim);
        renderMentionPopup(g, mouseX, mouseY);
        // 寮瑰眰闈㈡澘锛堣缃?琛ㄦ儏/蹇嵎/鎼滅储锛夌敾鍦ㄥ簳鏍忎箣涓婏紝z 楂樹竴灞傗€斺€斾晶杈规爮鍚?z 鍚庣敾
        // 浼氱洊浣忓畠浠紝鎻愬崌寮瑰眰 z 鍒颁晶杈规爮涔嬩笂閬垮厤閬尅
        //#if MC >= 12106
        g.getMatrices().pushMatrix();
        //#else
        //$$ g.getMatrices().push();
        //#endif
        //#if MC >= 12106
        g.getMatrices().translate(0, 0);
        //#else
        //$$ g.getMatrices().translate(0, 0, 100);
        //#endif
        renderPopupWithAnim(g, settingsAnimStart, a -> () -> settingsMenu.render(g, mouseX, mouseY, textRenderer, c(), panelX, panelW, barTop, ChatBubbleScreen::iconTex, a));
        renderPopupWithAnim(g, emojiAnimStart, a -> () -> emojiPanel.render(g, mouseX, mouseY, textRenderer, c(), panelX, panelW, barTop, ICON_S, PAD, a));
        renderPopupWithAnim(g, quickAnimStart, a -> () -> quickChatPanel.render(g, mouseX, mouseY, textRenderer, c(), panelX, panelW, barTop, quickChatInput, a));
        renderPopupWithAnim(g, searchAnimStart, a -> () -> searchPanel.render(g, mouseX, mouseY, textRenderer, c(), panelX, panelW, barTop, searchInput, searchMatches, searchMatchIdx, a));
        // 杈撳叆妗?widget 鍦?z=50 鐨?children 寰幆娓叉煋锛屼細琚繖閲?z=100 鐨勪笉閫忔槑闈㈡澘鑳屾櫙鐩栦綇
        // 锛?bb740e 寮瑰眰 z 鎻愬崌寮曞叆锛夆€斺€旈潰鏉挎墦寮€鏃跺湪鍚?z 閲嶇敾涓€娆★紝鏂囧瓧/鍏夋爣鎵嶅彲瑙併€?
        // widget 鏃犺儗鏅紙drawsBackground=false锛夛紝鍙敾鏂囧瓧/鍏夋爣锛屼笉閬尅闈㈡澘鍐呭
        if (quickChatPanel.visible && quickChatInput != null) {
            //#if MC >= 12000
            quickChatInput.render(g, mouseX, mouseY, delta);
            //#else
            //$$ quickChatInput.render(g.getMatrices(), mouseX, mouseY, delta);
            //#endif
        }
        if (searchPanel.visible && searchInput != null) {
            //#if MC >= 12000
            searchInput.render(g, mouseX, mouseY, delta);
            //#else
            //$$ searchInput.render(g.getMatrices(), mouseX, mouseY, delta);
            //#endif
        }
        //#if MC >= 12106
        g.getMatrices().popMatrix();
        //#else
        //$$ g.getMatrices().pop();
        //#endif

        //#if MC >= 12106
        g.getMatrices().popMatrix();
        //#else
        //$$ g.getMatrices().pop();
        //#endif

        if (sidebarOpen || sidebarAnimating) {
            //#if MC >= 12106
            g.getMatrices().pushMatrix();
            //#else
            //$$ g.getMatrices().push();
            //#endif
            // ZOOM: the sidebar scales with the panel around the panel center
            if (zoom) {
                float cx = panelX + panelW / 2f;
                //#if MC >= 12106
                g.getMatrices().translate(cx, height / 2f);
                //#else
                //$$ g.getMatrices().translate(cx, height / 2f, 0);
                //#endif
                //#if MC >= 12106
                g.getMatrices().scale(panelScale, panelScale);
                //#else
                //$$ g.getMatrices().scale(panelScale, panelScale, 1f);
                //#endif
                //#if MC >= 12106
                g.getMatrices().translate(-cx, -height / 2f);
                //#else
                //$$ g.getMatrices().translate(-cx, -height / 2f, 0);
                //#endif
            }
            // Fade/zoom-in-place applies only to the panel's own open/close
            // animation; the hamburger toggle always slides.
            boolean fadeSidebar = !sidebarAnimating && (pstyle == AnimationStyle.FADE || zoom);
            int sidebarOffset = (closing && !fadeSidebar)
                ? (int) ((getAnimProgress() - 1.0f) * SIDEBAR_W)
                : (fadeSidebar ? 0 : getSidebarScreenX());
            //#if MC >= 12106
            g.getMatrices().translate(sidebarOffset, 0);
            //#else
            //$$ g.getMatrices().translate(sidebarOffset, 0, 50);
            //#endif
            // Per-element alpha (vanilla drawTexture ignores setShaderColor; the
            // sidebar fades its own textures through the alpha path)
            renderSidebar(g, mouseX - sidebarOffset, mouseY, fadeSidebar ? getAnimProgress() : 1f);
            //#if MC >= 12106
            g.getMatrices().popMatrix();
            //#else
            //$$ g.getMatrices().pop();
            //#endif
            if (closing) sidebarSearchBox.setX(2 + sidebarOffset);
        }

        //#if MC >= 12106
        g.getMatrices().pushMatrix();
        //#else
        //$$ g.getMatrices().push();
        //#endif
        //#if MC >= 12106
        g.getMatrices().translate(0, 0);
        //#else
        //$$ g.getMatrices().translate(0, 0, 50);
        //#endif
        chatField.setX(inputX + panelOffset);
        // 涓嶈皟 super.render锛圕hatScreen.render 璁块棶 package-private chatInputSuggestor锛?
        // 璺ㄥ寘鏃犳硶鍒濆鍖栵級锛涘鍒?Screen.render 鐨?widgets 閬嶅巻娓叉煋
        for (net.minecraft.client.gui.Element w : this.children()) {
            if (w instanceof net.minecraft.client.gui.Drawable d) {
                //#if MC >= 12000
                d.render(g, mouseX, mouseY, delta);
                //#else
                //$$ d.render(g.getMatrices(), mouseX, mouseY, delta);
                //#endif
            }
        }
        // 寤鸿妗嗗畾浣嶅熀浜?chatField.getScreenX()锛堝睆骞曞潗鏍囷級锛屼笌 input 鍚屽潗鏍囩┖闂存覆鏌?
        g.enableScissor(panelX, 0, panelX + panelW, height);
        // In 1.19.x (MC >= 11900 && MC < 12000) ChatInputSuggestor.render takes a MatrixStack, not DrawContext.
        //#if MC >= 12000
        if (commandSuggestions != null) commandSuggestions.render(g, mouseX, mouseY);
        //#else
        //#if MC >= 11900
        //$$ if (commandSuggestions != null) commandSuggestions.render(g.getMatrices(), mouseX, mouseY);
        //#endif
        //#endif
        g.disableScissor();
        //#if MC >= 12106
        g.getMatrices().popMatrix();
        //#else
        //$$ g.getMatrices().pop();
        //#endif

        renderImagePreview(g);

        // Notification banner is rendered by ChatBubbleHudOverlay at z=300
    }

    private void renderTitleBar(DrawContext g, int mouseX, int mouseY, float panelAlpha) {
        int ty = titleY;
        int a255 = (int) (255 * panelAlpha);
        // Content (icons/text) alpha follows only the open/close animation 鈥?
        // panelOpacity must not tint it (2.3.7 regression: permanent 80%
        // opacity made icons/text lighter on light themes).
        int c255 = (int) (255 * getAnimProgress());
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.TITLE_BAR), panelX, ty, panelW, TITLE_H, panelAlpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), panelX, ty + TITLE_H, panelW, 1, panelAlpha);

        int menuX = panelX + 3;
        int menuY = ty + (TITLE_H - ICON_S) / 2;
        boolean hoverMenu = mouseX >= menuX && mouseX <= menuX + ICON_S && mouseY >= menuY && mouseY <= menuY + ICON_S;
        if (hoverMenu) ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.HOVER_BG), menuX - 1, menuY - 1, ICON_S + 2, ICON_S + 2, panelAlpha);
        drawTextureIconAlpha(g, iconTex("menu"), menuX, menuY, ICON_S, getAnimProgress());

        String title = getDisplayTitle();
        int titleW = textRenderer.getWidth(title);
        int titleX = UiLayout.centerX(panelX, panelW, titleW);
        int titleTextY = ty + (TITLE_H - textRenderer.fontHeight) / 2;
        g.drawText(textRenderer, title, titleX, titleTextY, ChatBubbleTheme.alphaBlend(c().textPrimary(), c255), false);

        String time = LocalTime.now().format(TIME_FMT);
        int timeW = textRenderer.getWidth(time);
        g.drawText(textRenderer, time,
            panelX + panelW - PAD - 20 - timeW, ty + (TITLE_H - textRenderer.fontHeight) / 2, ChatBubbleTheme.alphaBlend(c().timeColor(), c255), false);

        int closeX = panelX + panelW - 18;
        int closeY = ty + 6;
        boolean hoverClose = mouseX >= closeX && mouseX <= closeX + 12 && mouseY >= closeY && mouseY <= closeY + 12;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(hoverClose ? UiElement.CLOSE_HOVER : UiElement.CLOSE_BG),
            closeX, closeY, 12, 12, panelAlpha);
        g.drawText(textRenderer, "×", closeX + 6 - textRenderer.getWidth("×") / 2, closeY + 2, ChatBubbleTheme.alphaBlend(c().closeText(), c255), false);
    }

    private boolean isMouseOverHamburger(double mx, double my) {
        int menuX = panelX + 3;
        int menuY = titleY + (TITLE_H - ICON_S) / 2;
        return mx >= menuX && mx <= menuX + ICON_S && my >= menuY && my <= menuY + ICON_S;
    }

    private void renderMessages(DrawContext g, int mouseX, int mouseY) {
        msgHeightCache.clear();
        int imgVersion = ImageLoader.version();
        if (imgVersion != lastImageVersion) {
            lastImageVersion = imgVersion;
            imageParseCache.clear();
        }
        bubbleRects.clear();
        clickableSpans.clear();
        List<ChatMessageStore.ChatMessage> messages;
        if (whisperPartner == null) {
            messages = ChatMessageStore.getPublicMessages();
        } else {
            messages = ChatMessageStore.getWhisperMessages(whisperPartner);
        }
        if (messages.isEmpty()) return;

        int indicatorH = 0;
        if (whisperPartner != null) {
            indicatorH = 14;
            int indY = msgTop;
            ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.WHISPER_BAR), panelX, indY, panelW, indicatorH, getAnimProgress());
            String modeText = Text.translatable("alfriendschat.whisper.mode").getString() + ": " + whisperPartner;
            int modeTW = textRenderer.getWidth(modeText);
            g.drawText(textRenderer, modeText, panelX + (panelW - modeTW) / 2, indY + 2, c().textPrimary(), false);
        }

        int effectiveMsgTop = msgTop + indicatorH;
        int effectiveMsgBottom = newMessageCount > 0 ? barTop - NOTIF_H - 1 : msgBottom;
        int areaH = effectiveMsgBottom - effectiveMsgTop;

        int timeSeps = 0;
        String lastKey = null;
        int totalH = 0;
        for (var msg : messages) {
            totalH += getMsgHeight(msg) + messageGap();
            if (!msg.isSystem()) {
                String key = timeKey(msg.time());
                if (lastKey == null || !key.equals(lastKey)) { timeSeps++; lastKey = key; }
            }
        }
        totalH += timeSeps * (TIME_SEP_H + messageGap());
        int prevMaxScroll = maxScroll;
        maxScroll = Math.max(0, totalH - areaH);
        this.messageTotalH = totalH;

        boolean wasAtBottom = scrollOffset >= prevMaxScroll - 2;

        String playerName = client.player != null ? client.player.getName().getString() : "";
        int currentMsgCount = messages.size();
        if (wasAtBottom) {
            newMessageCount = 0; hasNewMentionOrQuote = false;
            latestMentionIndex = -1; lastSeenMessageCount = currentMsgCount;
        } else if (currentMsgCount > lastSeenMessageCount) {
            for (int i = lastSeenMessageCount; i < currentMsgCount; i++) {
                var msg = messages.get(i);
                if (msg == null) continue;
                newMessageCount++;
                if (msg.content().getString().contains("@" + playerName)) {
                    hasNewMentionOrQuote = true; latestMentionIndex = i;
                }
                if (msg.replySender() != null && msg.replySender().equals(playerName)) {
                    hasNewMentionOrQuote = true;
                    if (i > latestMentionIndex) latestMentionIndex = i;
                }
            }
            lastSeenMessageCount = currentMsgCount;
        }

        if (firstRender) {
            scrollOffset = maxScroll; scrollToBottom = false; firstRender = false; scrollAnimActive = false;
        } else if (scrollAnimActive) {
            float t = Animation.progress(scrollAnimStart, scrollAnimDuration, false);
            scrollOffset = Math.round(scrollAnimFrom + (scrollAnimTo - scrollAnimFrom) * t);
            if (t >= 1.0f) { scrollOffset = Math.round(scrollAnimTo); scrollAnimActive = false; }
        } else if (scrollToBottom || wasAtBottom) {
            float newTarget = maxScroll;
            if (Math.abs(scrollOffset - newTarget) <= 3) {
                scrollOffset = Math.round(newTarget); scrollToBottom = false;
            } else {
                lastScrollTime = Util.getMeasuringTimeMs();
                scrollAnimFrom = scrollOffset; scrollAnimTo = newTarget;
                scrollAnimStart = Util.getMeasuringTimeMs(); scrollAnimDuration = 150; scrollAnimActive = true;
            }
        }
        scrollOffset = MathHelper.clamp(scrollOffset, 0, maxScroll);

        g.enableScissor(panelX, effectiveMsgTop, panelX + panelW, effectiveMsgBottom);

        List<ChatMessageStore.ChatMessage> fullList = ChatMessageStore.getMessages();
        int fullIdx = 0;
        while (fullIdx < fullList.size() && fullList.get(fullIdx) != messages.get(0)) fullIdx++;

        int contentY = 0;
        lastKey = null;
        ChatMessageStore.ChatMessage prevRenderMsg = null;
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            while (fullIdx < fullList.size() && fullList.get(fullIdx) != msg) fullIdx++;

            boolean hideRep = ChatBubbleClientSetup.config().hideRepeatedAvatars() != null
                && ChatBubbleClientSetup.config().hideRepeatedAvatars();
            boolean showAvatar = !hideRep || !com.alinegames.alfriends.client.chat.MessageGrouping.isSameGroup(prevRenderMsg, msg);
            if (!msg.isSystem()) {
                String key = timeKey(msg.time());
                if (lastKey == null || !key.equals(lastKey)) {
                    lastKey = key;
                    int ssy = effectiveMsgTop + contentY - scrollOffset;
                    if (ssy + TIME_SEP_H > effectiveMsgTop && ssy < effectiveMsgBottom)
                        renderTimeSeparator(g, msg.time(), ssy);
                    contentY += TIME_SEP_H + messageGap();
                }
            }

            int h = getMsgHeight(msg);
            int screenY = effectiveMsgTop + contentY - scrollOffset;
            contentY += h + messageGap();

            if (screenY + h <= effectiveMsgTop || screenY >= effectiveMsgBottom) { fullIdx++; continue; }

            // New-message enter animation, staggered 40ms per message from the
            // tail (250ms window, keyed on msg.time()).
            // SLIDE: slide in horizontally 鈥?own bubbles from right to left,
            // others from left to right 鈥?plus fade. FADE: pure fade, no
            // displacement. ZOOM: scale in around the bubble center with overshoot.
            float mAlpha = 1f;
            int mDx = 0;
            int mDy = 0;
            float mScale = 1f;
            if (ChatBubbleClientSetup.config().animationEnabled()) {
                AnimationStyle mstyle = AnimationStyle.parse(ChatBubbleClientSetup.config().messageAnimStyle());
                if (mstyle != AnimationStyle.NONE) {
                    int tailIdx = messages.size() - 1 - i;
                    // msg.time() is epoch millis (System.currentTimeMillis), so the
                    // "now" side must use the same clock 鈥?the MC render clock is
                    // nanoTime-based and subtracting it yields a huge negative raw.
                    float raw = (float) (System.currentTimeMillis() - msg.time() - tailIdx * 40L) / 250f;
                    if (raw < 1f) {
                        float curve = Animation.styleCurve(mstyle, raw);
                        mAlpha = curve;
                        switch (mstyle) {
                            case SLIDE -> mDx = Math.round((1f - curve) * 40f) * (msg.isOwn() ? 1 : -1);
                            case FADE -> { /* pure fade, no displacement */ }
                            case ZOOM -> mScale = 0.8f + 0.2f * Animation.easeOutBack(curve);
                            default -> { }
                        }
                    }
                }
            }
            //#if MC >= 12106
            g.getMatrices().pushMatrix();
            //#else
            //$$ g.getMatrices().push();
            //#endif
            //#if MC >= 12106
            g.getMatrices().translate(mDx, mDy);
            //#else
            //$$ g.getMatrices().translate(mDx, mDy, 0);
            //#endif
            if (mScale != 1f) {
                // Bubble top-left for the ZOOM pivot (mirrors renderBubble's layout incl. bubble_size)
                float zs = bubbleScale();
                int zW = 0;
                for (var zl : wrapContent(msg.content(), scaledWrapWidth(panelW - avatarSize() - PAD * 2 - BUBBLE_PAD_X * 2 - 16, zs)))
                    zW = Math.max(zW, textRenderer.getWidth(zl));
                int zBubbleW = (int) ((zW + BUBBLE_PAD_X * 2) * zs);
                int zBubbleX = msg.isOwn()
                    ? panelX + panelW - PAD - avatarSize() - 4 - zBubbleW
                    : panelX + PAD + avatarSize() + 4;
                int zBubbleY = screenY + NAME_H;
                //#if MC >= 12106
                g.getMatrices().translate(zBubbleX + zBubbleW / 2f, zBubbleY);
                //#else
                //$$ g.getMatrices().translate(zBubbleX + zBubbleW / 2f, zBubbleY, 0);
                //#endif
                //#if MC >= 12106
                g.getMatrices().scale(mScale, mScale);
                //#else
                //$$ g.getMatrices().scale(mScale, mScale, 1f);
                //#endif
                //#if MC >= 12106
                g.getMatrices().translate(-(zBubbleX + zBubbleW / 2f), -zBubbleY);
                //#else
                //$$ g.getMatrices().translate(-(zBubbleX + zBubbleW / 2f), -zBubbleY, 0);
                //#endif
            }
            renderBubble(g, msg, fullIdx, screenY, mouseX, mouseY, mAlpha, showAvatar);
            //#if MC >= 12106
            g.getMatrices().popMatrix();
            //#else
            //$$ g.getMatrices().pop();
            //#endif
            fullIdx++;
            prevRenderMsg = msg;
        }
        renderScrollbar(g, mouseX, mouseY, effectiveMsgBottom);
        g.disableScissor();
    }

    private void renderScrollbar(DrawContext g, int mouseX, int mouseY, int effectiveMsgBottom) {
        if (maxScroll <= 0) return;
        boolean inZone = mouseX >= panelX + panelW - SCROLLBAR_HOVER_ZONE
            && mouseX <= panelX + panelW && mouseY >= msgTop && mouseY < effectiveMsgBottom;
        boolean recentlyScrolled = Util.getMeasuringTimeMs() - lastScrollTime < 1000;
        float target = (inZone || scrollbarDragging || recentlyScrolled) ? 1f : 0f;
        scrollbarAlpha = Animation.lerpTo(scrollbarAlpha, target, 0.15f, 0.005f);
        if (scrollbarAlpha <= 0.005f && !scrollbarDragging) return;

        int trackX = panelX + panelW - SCROLLBAR_WIDTH;
        int trackTop = msgTop;
        int trackBottom = effectiveMsgBottom;
        int trackH = trackBottom - trackTop;

        g.fill(trackX, trackTop, trackX + SCROLLBAR_WIDTH, trackBottom,
            ChatBubbleTheme.alphaBlend(c().scrollbar(), (int) (0x1A * scrollbarAlpha)));

        int thumbH = Math.max(MIN_THUMB_H, (int) ((long) trackH * trackH / messageTotalH));
        thumbH = Math.min(thumbH, trackH);
        int travelRange = trackH - thumbH;
        int thumbY = trackTop + (int) ((long) scrollOffset * travelRange / maxScroll);

        boolean hovering = !scrollbarDragging
            && mouseX >= trackX && mouseX < trackX + SCROLLBAR_WIDTH
            && mouseY >= thumbY && mouseY < thumbY + thumbH;
        scrollbarHovered = hovering || scrollbarDragging;

        float thumbBase = scrollbarDragging ? 0xAA : scrollbarHovered ? 0x88 : 0x66;
        g.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH,
            ChatBubbleTheme.alphaBlend(c().scrollbar(), (int) (thumbBase * scrollbarAlpha)));
    }

    private void renderTimeSeparator(DrawContext g, long timeMillis, int y) {
        String text = ChatMessageStore.formatTime(timeMillis);
        int tw = textRenderer.getWidth(text);
        int tx = UiLayout.centerX(panelX, panelW, tw);
        g.fill(tx - 6, y + 2, tx + tw + 6, y + TIME_SEP_H - 2, ChatBubbleTheme.alphaBlend(c().toastBg(), 0x44));
        g.drawText(textRenderer, text, tx, y + 3, c().timeColor(), false);
    }

    private List<OrderedText> wrapContent(Text c, int width) {
        List<Text> paras = new ArrayList<>();
        MutableText[] cur = { Text.empty() };
        c.visit((style, text) -> {
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    if (i > start) cur[0].append(Text.literal(text.substring(start, i)).fillStyle(style));
                    paras.add(cur[0]);
                    cur[0] = Text.empty();
                    start = i + 1;
                }
            }
            if (start < text.length()) cur[0].append(Text.literal(text.substring(start)).fillStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        paras.add(cur[0]);
        while (!paras.isEmpty() && paras.get(0).getString().isEmpty()) paras.remove(0);
        while (!paras.isEmpty() && paras.get(paras.size() - 1).getString().isEmpty()) paras.remove(paras.size() - 1);
        List<OrderedText> out = new ArrayList<>();
        for (Text p : paras) out.addAll(textRenderer.wrapLines(p, width));
        if (out.isEmpty()) out.addAll(textRenderer.wrapLines(c, width));
        return out;
    }

    private boolean isPanelSliding() {
        return ChatBubbleClientSetup.config().animationEnabled() && getAnimProgress() < 1.0f;
    }

    private int currentPanelOffset() {
        if (AnimationStyle.parse(ChatBubbleClientSetup.config().panelAnimStyle()) != AnimationStyle.SLIDE)
            return 0; // FADE/ZOOM/NONE have no horizontal displacement
        float anim = getAnimProgress();
        int moveDist;
        if (sidebarOpen) {
            moveDist = closing ? panelW : SIDEBAR_W;
        } else {
            moveDist = panelW;
        }
        return (int) ((anim - 1.0f) * moveDist);
    }

    private int getMsgHeight(ChatMessageStore.ChatMessage msg) {
        Integer cached = msgHeightCache.get(msg);
        if (cached != null) return cached;
        int h;
        if (msg.isSystem()) {
            List<OrderedText> lines = wrapContent(msg.content(), panelW - PAD * 2 - 20);
            h = lines.size() * textRenderer.fontHeight + 4;
        } else {
            int bubbleMaxW = panelW - avatarSize() - PAD * 2 - BUBBLE_PAD_X * 2 - 16;
            BracketCodec.ParseResult parsed = parseImages(msg);
            if (!parsed.images().isEmpty()
                    && parsed.images().stream().allMatch(BracketCodec.ImageRef::emote)
                    && parsed.textWithoutImages().getString().isBlank()) {
                // Keep the source size when it fits; only scale down when the
                // image exceeds the usable message width.
                h = NAME_H + textRenderer.fontHeight + 2
                    + imageEdgeHeight(parsed.images().get(0).url()) + 2;
            } else if (!parsed.images().isEmpty()) {
                // 2.4.0 sync: image messages are bubble-less 鈥?no bubble padding
                List<OrderedText> imgLines = wrapContent(parsed.textWithoutImages(), bubbleMaxW);
                int textH = imgLines.size() * textRenderer.fontHeight;
                int imgH = 0;
                for (var ref : parsed.images()) imgH += imageEdgeHeight(ref.url()) + 2;
                h = NAME_H + textH + imgH;
                if (msg.replyContent() != null) h += textRenderer.fontHeight + 7;
            } else {
                float s = bubbleScale();
                List<OrderedText> lines = wrapContent(parsed.textWithoutImages(), scaledWrapWidth(bubbleMaxW, s));
                h = NAME_H + (int) ((lines.size() * textRenderer.fontHeight + BUBBLE_PAD_Y * 2) * s);
                // Quote block hangs below the bubble (3px gap + scaled height)
                if (msg.replyContent() != null) h += 3 + (int) ((textRenderer.fontHeight + 4) * s);
            }
        }
        msgHeightCache.put(msg, h);
        return h;
    }

    private BracketCodec.ParseResult parseImages(ChatMessageStore.ChatMessage msg) {
        if (!ChatBubbleClientSetup.config().receiveImages()) {
            // Receiving disabled: bracket codes render as a plain-text
            // placeholder, never downloaded (the flood limiter stays untouched).
            return new BracketCodec.ParseResult(
                BracketCodec.toPlaceholderText(msg.content()), java.util.List.of());
        }
        BracketCodec.ParseResult cached = imageParseCache.get(msg);
        if (cached != null) return cached;
        cached = BracketCodec.parseOrExtract(msg.content());
        imageParseCache.put(msg, cached);
        return cached;
    }

    /** Display height in px for one image ref in a bubble-less message: long edge
     * clamped to the panel's usable width, aspect preserved, never upscaled. */
    private int imageEdgeHeight(String url) {
        int maxW = Math.max(1, Math.min(CHAT_IMAGE_PREVIEW_MAX, panelW - avatarSize() - PAD * 2 - 16));
        ImageEntry entry = ImageLoader.getOrLoad(url);
        if (entry != null && entry.state() == ImageEntry.State.LOADED
                && entry.width() > 0 && entry.height() > 0) {
            float ratio = Math.min((float) maxW / entry.width(), (float) maxW / entry.height());
            ratio = Math.min(1f, ratio);
            return Math.max(1, (int) (entry.height() * ratio));
        }
        return maxW;
    }

    private int[] messageRectAt(double mouseX, double mouseY) {
        for (int[] rect : bubbleRects) {
            if (mouseX >= rect[0] && mouseX <= rect[0] + rect[2] && mouseY >= rect[1] && mouseY <= rect[1] + rect[3]) return rect;
        }
        return null;
    }

    private int textOffsetAt(ChatMessageStore.ChatMessage msg, int[] rect, double mouseX) {
        String value = msg.content().getString();
        int localX = Math.max(0, (int) mouseX - rect[0] - BUBBLE_PAD_X);
        int best = 0; int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i <= value.length(); i++) {
            int distance = Math.abs(textRenderer.getWidth(value.substring(0, i)) - localX);
            if (distance < bestDistance) { bestDistance = distance; best = i; }
        }
        return best;
    }

    private void renderSelectionHighlight(DrawContext g, ChatMessageStore.ChatMessage msg, int index, int x, int y) {
        if (index != selectedMessageIndex || selectionStart < 0 || selectionEnd < 0) return;
        String value = msg.content().getString();
        int from = Math.max(0, Math.min(value.length(), Math.min(selectionStart, selectionEnd)));
        int to = Math.max(from, Math.min(value.length(), Math.max(selectionStart, selectionEnd)));
        if (from >= to) return;
        int left = x + BUBBLE_PAD_X + textRenderer.getWidth(value.substring(0, from));
        int right = x + BUBBLE_PAD_X + textRenderer.getWidth(value.substring(0, to));
        g.fill(left, y + BUBBLE_PAD_Y, Math.max(left + 1, right), y + BUBBLE_PAD_Y + textRenderer.fontHeight, 0x6699CCFF);
    }

    private void renderBubble(DrawContext g, ChatMessageStore.ChatMessage msg, int index, int baseY, int mouseX, int mouseY, float alpha, boolean showAvatar) {
        if (msg.isSystem()) {
            List<OrderedText> lines = wrapContent(msg.content(), panelW - PAD * 2 - 20);
            int yy = baseY + 2;
            Style fb = findClickStyle(msg.content());
            int sysColor = ChatBubbleTheme.alphaBlend(c().textMuted(), (int)(255 * alpha));
            for (var line : lines) {
                int lw = textRenderer.getWidth(line);
                renderLineWithClicks(g, line, panelX + (panelW - lw) / 2, yy, sysColor, fb);
                yy += textRenderer.fontHeight;
            }
            return;
        }

        boolean own = msg.isOwn();
        int bubbleMaxW = panelW - avatarSize() - PAD * 2 - BUBBLE_PAD_X * 2 - 16;
        BracketCodec.ParseResult parsed = parseImages(msg);
        // 2.4.0 sync: pure emote-pack sends (E33Emote) render at the small emote
        // size, bubble-less (QQ style).
        if (!parsed.images().isEmpty()
                && parsed.images().stream().allMatch(BracketCodec.ImageRef::emote)
                && parsed.textWithoutImages().getString().isBlank()) {
            renderEmoteMessage(g, msg, index, baseY, own, alpha, parsed, showAvatar);
            return;
        }
        // 2.4.0 sync: image-bearing messages render bubble-less 鈥?just the name,
        // avatar, text and stacked images, no bubble background (official behaviour).
        if (!parsed.images().isEmpty()) {
            renderNoBubbleMessage(g, msg, index, baseY, own, alpha, parsed, showAvatar);
            return;
        }
        float s = bubbleScale();
        // Bubble text is re-wrapped at the scaled width so bigger bubbles fit
        // fewer characters per line.
        List<OrderedText> lines = wrapContent(parsed.textWithoutImages(), scaledWrapWidth(bubbleMaxW, s));

        int textW = 0;
        for (var line : lines) textW = Math.max(textW, textRenderer.getWidth(line));
        // Bubble coords/dims are numerically pre-scaled: hit-testing on bubbleRects
        // needs no inverse-matrix transform at any bubble size.
        int bubbleW = (int) ((textW + BUBBLE_PAD_X * 2) * s);
        int bubbleH = (int) ((lines.size() * textRenderer.fontHeight + BUBBLE_PAD_Y * 2) * s);

        int avatarX, bubbleX;
        if (own) {
            avatarX = panelX + panelW - PAD - avatarSize();
            bubbleX = avatarX - 4 - bubbleW;
        } else {
            avatarX = panelX + PAD;
            bubbleX = avatarX + avatarSize() + 4;
        }

        int nameY = baseY;

        if (!msg.senderName().getString().isEmpty()) {
            int maxNameW = panelW - avatarSize() - PAD * 2 - 20;
            Text sn = msg.senderName();
            OrderedText nameSeq;
            if (textRenderer.getWidth(sn) > maxNameW) {
                //#if MC >= 26000
                //$$ var cut = textRenderer.substrByWidth(sn, maxNameW - textRenderer.getWidth("..."));
                //#else
                var cut = textRenderer.trimToWidth(sn, maxNameW - textRenderer.getWidth("..."));
                //#endif
                nameSeq = Language.getInstance().reorder(
                    StringVisitable.concat(cut, StringVisitable.plain("...")));
            } else {
                nameSeq = sn.asOrderedText();
            }
            int nameW = textRenderer.getWidth(nameSeq);
            int startX = own ? (bubbleX + bubbleW - nameW) : bubbleX;
            g.drawText(textRenderer, nameSeq, startX, nameY, ChatBubbleTheme.alphaBlend(c().nameColor(), (int)(255 * alpha)), false);
        }

        int bubbleY = baseY + NAME_H;
        int avatarY = baseY;

        int bg = own
            ? ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().ownBubbleColor(), 0xFF1E90FF)
            : ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().otherBubbleColor(), c().contextHover());
        int fg = own
            ? ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().ownTextColor(), 0xFFFFFFFF)
            : ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().otherTextColor(), c().textPrimary());

        // 姘旀场鑳屾櫙锛歋DF 鍦嗚锛坰hader 鏁板锛屼换浣曞崐寰勫钩婊戯紱閰嶇疆瀹炴椂鐢熸晥锛屼笉鍙璧勬簮鍖呰鐩栵級
        // 鍧愭爣宸插惈 bubble_size 缂╂斁锛屽渾瑙掑崐寰勫悓涔?s锛屽惁鍒欐斁澶у悗鍦嗚鐩稿鍙樺皬
        int radius = ChatBubbleClientSetup.config().bubbleCornerRadius();
        int borderWidth = ChatBubbleClientSetup.config().bubbleBorderWidth() == null
            ? 0 : Math.max(0, Math.min(6, ChatBubbleClientSetup.config().bubbleBorderWidth()));
        if (borderWidth > 0 && bubbleW > borderWidth * 2 && bubbleH > borderWidth * 2) {
            String configuredBorderColor = own
                ? ChatBubbleClientSetup.config().bubbleBorderColor()
                : ChatBubbleClientSetup.config().otherBubbleBorderColor();
            int borderColor = ChatBubbleConfig.parseHexColor(
                configuredBorderColor, own ? 0xFFFFFFFF : 0xFFAAAAAA);
            RoundRectRenderer.fill(g, bubbleX, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH,
                radius, ChatBubbleTheme.alphaBlend(borderColor, (int) (255 * alpha)));
            RoundRectRenderer.fill(g,
                bubbleX + borderWidth, bubbleY + borderWidth,
                bubbleX + bubbleW - borderWidth, bubbleY + bubbleH - borderWidth,
                Math.max(0, radius - borderWidth), ChatBubbleTheme.alphaBlend(bg, (int) (255 * alpha)));
        } else {
            RoundRectRenderer.fill(g, bubbleX, bubbleY, bubbleX + bubbleW, bubbleY + bubbleH,
                radius, ChatBubbleTheme.alphaBlend(bg, (int) (255 * alpha)));
        }

        Style fbP = findClickStyle(msg.content());
        int fgA = ChatBubbleTheme.alphaBlend(fg, (int)(255 * alpha));
        int scaledLineHeight = Math.max(1, (int) (textRenderer.fontHeight * s));
        int textBlockHeight = scaledLineHeight * lines.size();
        int centeredTextY = bubbleY + Math.max(0, (bubbleH - textBlockHeight) / 2);
        for (int li = 0; li < lines.size(); li++) {
            // Bubble text draws at the matrix origin; the translate is unconditional
            // (s == 1 still needs the offset, only the scale is skipped). Clickable
            // spans are recorded in origin space, then transformed back to screen
            // space so hit-testing and the visual position stay in sync at every size.
            int textSX = bubbleX + (int) (BUBBLE_PAD_X * s);
            int textSY = centeredTextY + li * scaledLineHeight;
            int beforeLine = clickableSpans.size();
            RenderHelper.pushMatrix(g);
            RenderHelper.translate(g, textSX, textSY);
            if (s != 1f) RenderHelper.scale(g, s, s);
            renderLineWithClicks(g, lines.get(li), 0, 0, fgA, fbP);
            RenderHelper.popMatrix(g);
            for (int i = beforeLine; i < clickableSpans.size(); i++) {
                ClickableSpan sp = clickableSpans.get(i);
                clickableSpans.set(i, new ClickableSpan(
                    textSX + (int) (sp.x * s),
                    textSY + (int) (sp.y * s),
                    Math.max(1, (int) (sp.w * s)),
                    Math.max(1, (int) (sp.h * s)),
                    sp.style));
            }
        }

        String skinName = (msg.rawPlayerName() != null && !msg.rawPlayerName().isEmpty())
            ? msg.rawPlayerName() : msg.senderName().getString();
        Identifier skin = getSkin(msg.senderUUID(), skinName);
        if (showAvatar) drawPlayerHead(g, skin, avatarX, avatarY, avatarSize(), avatarSize() + 2, alpha);

        if (msg.duplicateCount() > 1) {
            String label = "x" + msg.duplicateCount();
            int labelW = (int) (textRenderer.getWidth(label) * s);
            int labelX, labelY = bubbleY + (bubbleH - (int) (textRenderer.fontHeight * s)) / 2;
            if (own) { labelX = bubbleX - labelW - 3; } else { labelX = bubbleX + bubbleW + 3; }
            RenderHelper.pushMatrix(g);
            RenderHelper.translate(g, labelX, labelY);
            if (s != 1f) RenderHelper.scale(g, s, s);
            g.drawText(textRenderer, label, 0, 0, ChatBubbleTheme.alphaBlend(c().duplicateLabel(), (int)(255 * alpha)), false);
            RenderHelper.popMatrix(g);
        }

        if (msg.replyContent() != null) {
            int quoteMaxW = panelW - PAD * 2 - avatarSize() - 24;
            String quoteText = "\u21AA " + msg.replySender() + ": " + msg.replyContent();
            String quoteDisplay = textRenderer.trimToWidth(quoteText, Math.max(8, (int) ((quoteMaxW - 10) / s)));
            if (!quoteDisplay.equals(quoteText)) quoteDisplay += "...";
            int quoteTextW = (int) (textRenderer.getWidth(quoteDisplay) * s);
            int quoteW = Math.min(quoteTextW + (int) (8 * s), quoteMaxW);
            int quoteH = Math.max(1, (int) ((textRenderer.fontHeight + 4) * s));
            int quoteY = bubbleY + bubbleH + 3;
            int quoteX;
            if (own) { quoteX = bubbleX + bubbleW - quoteW; } else { quoteX = bubbleX; }
            if (quoteX < panelX + PAD) quoteX = panelX + PAD;
            if (quoteX + quoteW > panelX + panelW - PAD) quoteW = panelX + panelW - PAD - quoteX;
            // 寮曠敤鍧楋細SDF 鍦嗚锛堥殢 bubble_size 缂╂斁锛屽渾瑙掕窡闅?bubbleCornerRadius 閰嶇疆锛?
            RoundRectRenderer.fill(g, quoteX, quoteY, quoteX + quoteW, quoteY + quoteH,
                ChatBubbleClientSetup.config().bubbleCornerRadius(), ChatBubbleTheme.alphaBlend(c().contextHover(), (int)(255 * alpha)));
            RenderHelper.pushMatrix(g);
            RenderHelper.translate(g, quoteX + (int) (4 * s), quoteY + (int) (2 * s));
            if (s != 1f) RenderHelper.scale(g, s, s);
            g.drawText(textRenderer, quoteDisplay, 0, 0, ChatBubbleTheme.alphaBlend(c().textSecondary(), (int)(255 * alpha)), false);
            RenderHelper.popMatrix(g);
        }

        bubbleRects.add(new int[]{bubbleX, bubbleY, bubbleW, bubbleH, index});

        if (index == searchHighlightIndex)
            drawRectBorder(g, bubbleX - 1, bubbleY - 1, bubbleW + 2, bubbleH + 2, ChatSearchPanel.HIGHLIGHT);
    }

    /** Bubble-less image message (2.4.0 sync): name + avatar + text + stacked
     * images, no bubble background. Images clamp their long edge to the panel's
     * usable width (aspect preserved, never upscaled). */
    private void renderNoBubbleMessage(DrawContext g, ChatMessageStore.ChatMessage msg, int index, int baseY,
            boolean own, float alpha, BracketCodec.ParseResult parsed, boolean showAvatar) {
        int bubbleMaxW = panelW - avatarSize() - PAD * 2 - BUBBLE_PAD_X * 2 - 16;
        List<OrderedText> lines = wrapContent(parsed.textWithoutImages(), bubbleMaxW);
        int avatarX = own ? panelX + panelW - PAD - avatarSize() : panelX + PAD;

        if (!msg.senderName().getString().isEmpty()) {
            int maxNameW = panelW - avatarSize() - PAD * 2 - 20;
            Text sn = msg.senderName();
            OrderedText nameSeq;
            if (textRenderer.getWidth(sn) > maxNameW) {
                //#if MC >= 26000
                //$$ var cut = textRenderer.substrByWidth(sn, maxNameW - textRenderer.getWidth("..."));
                //#else
                var cut = textRenderer.trimToWidth(sn, maxNameW - textRenderer.getWidth("..."));
                //#endif
                nameSeq = Language.getInstance().reorder(
                    StringVisitable.concat(cut, StringVisitable.plain("...")));
            } else {
                nameSeq = sn.asOrderedText();
            }
            int nameW = textRenderer.getWidth(nameSeq);
            int startX = own ? (avatarX - 4 - nameW) : (avatarX + avatarSize() + 4);
            g.drawText(textRenderer, nameSeq, startX, baseY,
                ChatBubbleTheme.alphaBlend(c().nameColor(), (int)(255 * alpha)), false);
        }

        String skinName = (msg.rawPlayerName() != null && !msg.rawPlayerName().isEmpty())
            ? msg.rawPlayerName() : msg.senderName().getString();
        Identifier skin = getSkin(msg.senderUUID(), skinName);
        if (showAvatar) drawPlayerHead(g, skin, avatarX, baseY, avatarSize(), avatarSize() + 2, alpha);

        int maxTextW = 0;
        for (var line : lines) maxTextW = Math.max(maxTextW, textRenderer.getWidth(line));
        int textX = own ? (avatarX - 4 - maxTextW) : (avatarX + avatarSize() + 4);

        int y = baseY + NAME_H;
        if (!lines.isEmpty()) {
            int fg = own
                ? ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().ownTextColor(), 0xFFFFFFFF)
                : ChatBubbleConfig.parseHexColor(ChatBubbleClientSetup.config().otherTextColor(), c().textPrimary());
            Style fb = findClickStyle(msg.content());
            int fgA = ChatBubbleTheme.alphaBlend(fg, (int)(255 * alpha));
            for (int li = 0; li < lines.size(); li++)
                renderLineWithClicks(g, lines.get(li), textX, y + li * textRenderer.fontHeight, fgA, fb);
            y += lines.size() * textRenderer.fontHeight;
        }

        int maxImgW = Math.max(1, Math.min(CHAT_IMAGE_PREVIEW_MAX, panelW - avatarSize() - PAD * 2 - 16));
        for (var ref : parsed.images()) {
            int w = maxImgW, h = maxImgW;
            ImageEntry entry = ImageLoader.getOrLoad(ref.url());
            if (entry != null && entry.state() == ImageEntry.State.LOADED
                    && entry.width() > 0 && entry.height() > 0) {
                float ratio = Math.min((float) maxImgW / entry.width(), (float) maxImgW / entry.height());
                ratio = Math.min(1f, ratio); // never upscale
                w = Math.max(1, (int) (entry.width() * ratio));
                h = Math.max(1, (int) (entry.height() * ratio));
            }
            int imgX = own ? (avatarX - 4 - w) : (avatarX + avatarSize() + 4);
            if (entry != null && entry.state() == ImageEntry.State.LOADED && entry.textureId() != null) {
                //#if MC >= 12106
                g.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, entry.textureId(), imgX, y, 0.0F, 0.0F, w, h, entry.width(), entry.height(), entry.width(), entry.height());
                //#else
                //#if MC >= 12102
                //$$ g.drawTexture(id -> net.minecraft.client.render.RenderLayer.getGuiTextured(id), entry.textureId(), imgX, y, 0, 0, w, h, entry.width(), entry.height(), entry.width(), entry.height());
                //#else
                //$$ g.drawTexture(entry.textureId(), imgX, y, w, h, 0, 0, entry.width(), entry.height(), entry.width(), entry.height());
                //#endif
                //#endif
            } else {
                boolean limited = entry != null && entry.state() == ImageEntry.State.FAILED
                    && entry.failure() != null && entry.failure().contains("rate limited");
                String txt = limited
                    ? Text.translatable("alfriendschat.image.ratelimited").getString()
                    : entry != null && entry.state() == ImageEntry.State.FAILED
                        ? Text.translatable("alfriendschat.image.failed").getString()
                        : Text.translatable("alfriendschat.image.loading").getString();
                g.drawText(textRenderer, txt, imgX, y,
                    ChatBubbleTheme.alphaBlend(limited ? 0xFFFF5555 : c().textSecondary(), (int)(255 * alpha)), false);
            }
            // Open the URL in the system browser on click; hover shows the URL
            Style st = Style.EMPTY
                //#if MC >= 12105
                .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(ref.url())))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(ref.url())));
                //#else
                //$$ .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, ref.url()))
                //$$ .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(ref.url())));
                //#endif
            clickableSpans.add(new ClickableSpan(imgX, y, w, h, st, ref.url()));
            y += h + 2;
        }

        if (msg.duplicateCount() > 1) {
            String label = "x" + msg.duplicateCount();
            int labelX = own ? (avatarX - 4 - textRenderer.getWidth(label) - 3) : (avatarX + avatarSize() + 4 + 3);
            g.drawText(textRenderer, label, labelX, baseY + NAME_H + 2,
                ChatBubbleTheme.alphaBlend(c().duplicateLabel(), (int)(255 * alpha)), false);
        }

        if (msg.replyContent() != null) {
            int quoteMaxW = panelW - PAD * 2 - avatarSize() - 24;
            String quoteText = "\u21AA " + msg.replySender() + ": " + msg.replyContent();
            String quoteDisplay = textRenderer.trimToWidth(quoteText, quoteMaxW - 10);
            if (!quoteDisplay.equals(quoteText)) quoteDisplay += "...";
            int quoteW = Math.min(textRenderer.getWidth(quoteDisplay) + 8, quoteMaxW);
            int quoteX = own ? (avatarX - 4 - quoteW) : (avatarX + avatarSize() + 4);
            if (quoteX < panelX + PAD) quoteX = panelX + PAD;
            if (quoteX + quoteW > panelX + panelW - PAD) quoteW = panelX + panelW - PAD - quoteX;
            RoundRectRenderer.fill(g, quoteX, y, quoteX + quoteW, y + textRenderer.fontHeight + 4,
                ChatBubbleClientSetup.config().bubbleCornerRadius(), ChatBubbleTheme.alphaBlend(c().contextHover(), (int)(255 * alpha)));
            g.drawText(textRenderer, quoteDisplay, quoteX + 4, y + 2,
                ChatBubbleTheme.alphaBlend(c().textSecondary(), (int)(255 * alpha)), false);
        }

        // Hit-test region for avatar clicks / context menus: the message span
        bubbleRects.add(new int[]{own ? avatarX - 8 - maxTextW : avatarX + avatarSize() + 4,
            baseY, Math.max(maxTextW, maxImgW), y - baseY, index});
    }

    /** Pure E33Emote messages render bubble-less at source size when possible. */
    private void renderEmoteMessage(DrawContext g, ChatMessageStore.ChatMessage msg, int index, int baseY,
            boolean own, float alpha, BracketCodec.ParseResult parsed, boolean showAvatar) {
        if (parsed.images().isEmpty()) return;
        BracketCodec.ImageRef ref = parsed.images().get(0);

        int avatarX = own ? panelX + panelW - PAD - avatarSize() : panelX + PAD;
        int nameY = baseY;

        if (!msg.senderName().getString().isEmpty()) {
            int maxNameW = panelW - avatarSize() - PAD * 2 - 20;
            Text sn = msg.senderName();
            OrderedText nameSeq;
            if (textRenderer.getWidth(sn) > maxNameW) {
                //#if MC >= 26000
                //$$ var cut = textRenderer.substrByWidth(sn, maxNameW - textRenderer.getWidth("..."));
                //#else
                var cut = textRenderer.trimToWidth(sn, maxNameW - textRenderer.getWidth("..."));
                //#endif
                nameSeq = Language.getInstance().reorder(
                    StringVisitable.concat(cut, StringVisitable.plain("...")));
            } else {
                nameSeq = sn.asOrderedText();
            }
            int nameW = textRenderer.getWidth(nameSeq);
            int startX = own ? (avatarX - 4 - nameW) : (avatarX + avatarSize() + 4);
            g.drawText(textRenderer, nameSeq, startX, nameY,
                ChatBubbleTheme.alphaBlend(c().nameColor(), (int)(255 * alpha)), false);
        }

        String skinName = (msg.rawPlayerName() != null && !msg.rawPlayerName().isEmpty())
            ? msg.rawPlayerName() : msg.senderName().getString();
        Identifier skin = getSkin(msg.senderUUID(), skinName);
        if (showAvatar) drawPlayerHead(g, skin, avatarX, baseY, avatarSize(), avatarSize() + 2, alpha);

        int emoteY = baseY + NAME_H + 2;
        int maxE = Math.max(80, panelW - avatarSize() - PAD * 2 - 16);
        int w = maxE, h = maxE;
        ImageEntry entry = ImageLoader.getOrLoad(ref.url());
        if (entry != null && entry.state() == ImageEntry.State.LOADED
                && entry.width() > 0 && entry.height() > 0) {
            float ratio = Math.min((float) maxE / entry.width(), (float) maxE / entry.height());
            ratio = Math.min(1f, ratio); // never upscale
            w = Math.max(1, (int) (entry.width() * ratio));
            h = Math.max(1, (int) (entry.height() * ratio));
        }
        int emoteX = own ? (avatarX - 4 - w) : (avatarX + avatarSize() + 4);
        if (entry != null && entry.state() == ImageEntry.State.LOADED && entry.textureId() != null) {
            //#if MC >= 12106
            g.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, entry.textureId(), emoteX, emoteY, 0.0F, 0.0F, w, h, entry.width(), entry.height(), entry.width(), entry.height());
            //#else
            //#if MC >= 12102
            //$$ g.drawTexture(id -> net.minecraft.client.render.RenderLayer.getGuiTextured(id), entry.textureId(), emoteX, emoteY, 0, 0, w, h, entry.width(), entry.height(), entry.width(), entry.height());
            //#else
            //$$ g.drawTexture(entry.textureId(), emoteX, emoteY, w, h, 0, 0, entry.width(), entry.height(), entry.width(), entry.height());
            //#endif
            //#endif
        } else {
            boolean limited = entry != null && entry.state() == ImageEntry.State.FAILED
                && entry.failure() != null && entry.failure().contains("rate limited");
            String txt = limited
                ? Text.translatable("alfriendschat.image.ratelimited").getString()
                : entry != null && entry.state() == ImageEntry.State.FAILED
                    ? Text.translatable("alfriendschat.image.failed").getString()
                    : Text.translatable("alfriendschat.image.loading").getString();
            g.drawText(textRenderer, txt, emoteX, emoteY,
                ChatBubbleTheme.alphaBlend(limited ? 0xFFFF5555 : c().textSecondary(), (int)(255 * alpha)), false);
        }
        clickableSpans.add(new ClickableSpan(emoteX, emoteY, w, h, Style.EMPTY, ref.url()));
    }

    private void renderLineWithClicks(DrawContext g, OrderedText line, int x, int y, int color) {
        renderLineWithClicks(g, line, x, y, color, null);
    }

    private void renderLineWithClicks(DrawContext g, OrderedText line, int x, int y, int color, Style fallback) {
        final List<Style> styles = new ArrayList<>();
        line.accept((i, st, cp) -> { styles.add(st); return true; });

        final int beforeCount = clickableSpans.size();
        int runStart = -1;
        Style runStyle = null;
        List<int[]> clickableCharRanges = new ArrayList<>();
        for (int idx = 0; idx <= styles.size(); idx++) {
            Style st = idx < styles.size() ? styles.get(idx) : null;
            boolean clickable = st != null && (st.getClickEvent() != null || st.getHoverEvent() != null);
            if (runStyle == null) {
                if (clickable) { runStart = idx; runStyle = st; }
            } else if (!clickable || !st.equals(runStyle)) {
                int x0 = prefixWidth(line, runStart);
                int x1 = prefixWidth(line, idx);
                clickableSpans.add(new ClickableSpan(x + x0, y, x1 - x0, textRenderer.fontHeight, runStyle));
                clickableCharRanges.add(new int[]{runStart, idx});
                runStart = clickable ? idx : -1;
                runStyle = clickable ? st : null;
            }
        }

        if (fallback != null && fallback.getClickEvent() != null) {
            if (clickableSpans.size() == beforeCount) {
                clickableSpans.add(new ClickableSpan(x, y, textRenderer.getWidth(line), textRenderer.fontHeight,
                    fallback.withUnderline(true)));
                clickableCharRanges.add(new int[]{0, styles.size()});
            } else {
                for (int i = beforeCount; i < clickableSpans.size(); i++) {
                    ClickableSpan s = clickableSpans.get(i);
                    if (s.style.getClickEvent() == null) {
                        clickableSpans.set(i, new ClickableSpan(s.x, s.y, s.w, s.h,
                            s.style.withClickEvent(fallback.getClickEvent())));
                    }
                }
            }
        }

        int styleLen = styles.size();
        boolean[] hasClickEvent = new boolean[styleLen];
        for (int ri = 0; ri < clickableCharRanges.size(); ri++) {
            int spanIdx = beforeCount + ri;
            if (spanIdx < clickableSpans.size()
                && clickableSpans.get(spanIdx).style.getClickEvent() != null) {
                int[] r = clickableCharRanges.get(ri);
                for (int i = r[0]; i < r[1]; i++) hasClickEvent[i] = true;
            }
        }

        OrderedText decorated = sink -> line.accept((i, st, cp) ->
            sink.accept(i, (i < styleLen ? hasClickEvent[i] : st.getClickEvent() != null)
                && !st.isUnderlined() ? st.withUnderline(true) : st, cp));
        g.drawText(textRenderer, decorated, x, y, color, false);
    }

    private int prefixWidth(OrderedText line, int count) {
        if (count <= 0) return 0;
        return textRenderer.getWidth((OrderedText) sink -> {
            int[] left = {count};
            line.accept((i, st, cp) -> left[0]-- > 0 && sink.accept(i, st, cp));
            return true;
        });
    }

    private Style findClickStyle(Text c) {
        Style s = c.getStyle();
        if (s != null && s.getClickEvent() != null) return s;
        for (Text child : c.getSiblings()) {
            s = findClickStyle(child);
            if (s != null) return s;
        }
        return null;
    }

    private Style getHoveredStyle(double mouseX, double mouseY) {
        for (ClickableSpan s : clickableSpans) {
            if (mouseX >= s.x && mouseX <= s.x + s.w
                && mouseY >= s.y && mouseY <= s.y + s.h)
                return s.style;
        }
        return null;
    }

    private void renderNotificationBar(DrawContext g, int mouseX, int mouseY) {
        if (newMessageCount <= 0) return;
        int notifY = barTop - NOTIF_H;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), panelX, notifY - 1, panelW, 1, getAnimProgress());
        int yellow = c().notificationText();
        int textY = notifY + (NOTIF_H - textRenderer.fontHeight) / 2;
        String ct = Text.translatable("alfriendschat.notif.new_messages", newMessageCount).getString() + " ↓";
        notifCountLeft = panelX + PAD;
        notifCountRight = notifCountLeft + textRenderer.getWidth(ct);
        notifBarTextY = textY;
        boolean h = mouseX >= notifCountLeft && mouseX <= notifCountRight
            && mouseY >= textY && mouseY <= textY + textRenderer.fontHeight;
        g.drawText(textRenderer, ct, notifCountLeft, textY, h ? c().notificationText() : yellow, false);
        if (hasNewMentionOrQuote) {
            String mt = Text.translatable("alfriendschat.notif.mention").getString() + " ↓";
            notifMentionLeft = panelX + panelW - PAD - textRenderer.getWidth(mt);
            notifMentionRight = notifMentionLeft + textRenderer.getWidth(mt);
            h = mouseX >= notifMentionLeft && mouseX <= notifMentionRight
                && mouseY >= textY && mouseY <= textY + textRenderer.fontHeight;
            g.drawText(textRenderer, mt, notifMentionLeft, textY, h ? c().notificationText() : yellow, false);
        } else {
            notifMentionLeft = -1; notifMentionRight = -1;
        }
    }

    private void renderContextMenu(DrawContext g, int mouseX, int mouseY) {
        if (contextMsgIndex < 0) return;
        int menuH = CTX_ITEM_H * 2 + 2;
        int menuX = Math.min(contextX, panelX + panelW - CTX_W - 2);
        int menuY = contextY - menuH;
        if (menuY < msgTop) menuY = contextY + 4;
        float alpha = getAnimProgress();

        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.CONTEXT_MENU_BG), menuX, menuY, CTX_W, menuH, alpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), menuX, menuY, CTX_W, 1, alpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), menuX, menuY + menuH - 1, CTX_W, 1, alpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), menuX, menuY, 1, menuH, alpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), menuX + CTX_W - 1, menuY, 1, menuH, alpha);

        boolean hoverCopy = mouseX >= menuX && mouseX <= menuX + CTX_W
            && mouseY >= menuY && mouseY <= menuY + CTX_ITEM_H;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(hoverCopy ? UiElement.CONTEXT_HOVER : UiElement.SIDEBAR_SELECTED),
            menuX + 1, menuY + 1, CTX_W - 2, CTX_ITEM_H - 1, alpha);
        drawTextureIconAlpha(g, iconTex("copy"), menuX + 5, menuY + 3, 12, alpha);
        g.drawText(textRenderer, Text.translatable("alfriendschat.context.copy").getString(), menuX + 22, menuY + 4, c().textPrimary(), false);

        g.fill(menuX + 4, menuY + CTX_ITEM_H, menuX + CTX_W - 4, menuY + CTX_ITEM_H + 1, c().closeHoverBg());

        boolean hoverQuote = mouseX >= menuX && mouseX <= menuX + CTX_W
            && mouseY >= menuY + CTX_ITEM_H + 1 && mouseY <= menuY + menuH;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(hoverQuote ? UiElement.CONTEXT_HOVER : UiElement.SIDEBAR_SELECTED),
            menuX + 1, menuY + CTX_ITEM_H + 1, CTX_W - 2, CTX_ITEM_H, alpha);
        drawTextureIconAlpha(g, iconTex("quote"), menuX + 5, menuY + CTX_ITEM_H + 3, 12, alpha);
        g.drawText(textRenderer, Text.translatable("alfriendschat.context.quote").getString(), menuX + 22, menuY + CTX_ITEM_H + 5, c().textPrimary(), false);
    }

    private void renderAvatarContextMenu(DrawContext g, int mouseX, int mouseY) {
        if (contextAvatarIndex < 0) return;
        int menuH = CTX_ITEM_H * 3 + 4;
        int menuX = Math.min(contextAvatarX, panelX + panelW - CTX_W - 2);
        int menuY = MathHelper.clamp(contextAvatarY, msgTop + 2,
            Math.max(msgTop + 2, msgBottom - menuH - 2));
        float alpha = getAnimProgress();

        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.CONTEXT_MENU_BG), menuX, menuY, CTX_W, menuH, alpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), menuX, menuY, CTX_W, 1, alpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), menuX, menuY + menuH - 1, CTX_W, 1, alpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), menuX, menuY, 1, menuH, alpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), menuX + CTX_W - 1, menuY, 1, menuH, alpha);

        ChatMessageStore.ChatMessage avaMsg = ChatMessageStore.getMessageAt(contextAvatarIndex);
        String avatarName = avaMsg != null ? avaMsg.rawPlayerName() : null;
        boolean privateView = whisperPartner != null;
        boolean friend = ChatMessageStore.isFriendContact(avatarName);

        boolean hoverPrimary = mouseX >= menuX && mouseX <= menuX + CTX_W
            && mouseY >= menuY && mouseY <= menuY + CTX_ITEM_H;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(hoverPrimary ? UiElement.CONTEXT_HOVER : UiElement.SIDEBAR_SELECTED),
            menuX + 1, menuY + 1, CTX_W - 2, CTX_ITEM_H - 1, alpha);
        String primaryIcon = friend && !privateView ? "whisper" : "friends";
        String primaryKey = privateView ? "alfriendschat.context.invite"
            : (friend ? "alfriendschat.context.whisper" : "alfriendschat.context.add_friend");
        drawTextureIconAlpha(g, iconTex(primaryIcon), menuX + 5, menuY + 3, 12, alpha);
        g.drawText(textRenderer, Text.translatable(primaryKey).getString(), menuX + 22, menuY + 4, c().textPrimary(), false);

        g.fill(menuX + 4, menuY + CTX_ITEM_H + 1, menuX + CTX_W - 4, menuY + CTX_ITEM_H + 2, c().closeHoverBg());

        boolean hoverTp = mouseX >= menuX && mouseX <= menuX + CTX_W
            && mouseY >= menuY + CTX_ITEM_H + 2 && mouseY <= menuY + CTX_ITEM_H * 2 + 2;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(hoverTp ? UiElement.CONTEXT_HOVER : UiElement.SIDEBAR_SELECTED),
            menuX + 1, menuY + CTX_ITEM_H + 2, CTX_W - 2, CTX_ITEM_H, alpha);
        drawTextureIconAlpha(g, iconTex("tp"), menuX + 5, menuY + CTX_ITEM_H + 4, 12, alpha);
        g.drawText(textRenderer, Text.translatable("alfriendschat.context.tp").getString(), menuX + 22, menuY + CTX_ITEM_H + 6, c().textPrimary(), false);

        g.fill(menuX + 4, menuY + CTX_ITEM_H * 2 + 3, menuX + CTX_W - 4, menuY + CTX_ITEM_H * 2 + 4, c().closeHoverBg());

        boolean hoverBlock = mouseX >= menuX && mouseX <= menuX + CTX_W
            && mouseY >= menuY + CTX_ITEM_H * 2 + 4 && mouseY <= menuY + menuH;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(hoverBlock ? UiElement.CONTEXT_HOVER : UiElement.SIDEBAR_SELECTED),
            menuX + 1, menuY + CTX_ITEM_H * 2 + 4, CTX_W - 2, CTX_ITEM_H, alpha);
        drawTextureIconAlpha(g, iconTex("block"), menuX + 5, menuY + CTX_ITEM_H * 2 + 6, 12, alpha);
        boolean blocked = avaMsg != null && ChatMessageStore.isPlayerBlocked(avatarName, avaMsg.senderName(),
            ChatBubbleClientSetup.config().blockedPlayers());
        String blockKey = blocked ? "alfriendschat.context.unblock" : "alfriendschat.context.block";
        g.drawText(textRenderer, Text.translatable(blockKey).getString(),
            menuX + 22, menuY + CTX_ITEM_H * 2 + 8, c().textPrimary(), false);
    }

    private static final int REPLY_BAR_H = 18;

    private void renderReplyBar(DrawContext g, int mouseX, int mouseY) {
        if (replyTargetIndex < 0) return;
        ChatMessageStore.ChatMessage target = ChatMessageStore.getMessageAt(replyTargetIndex);
        if (target == null) { replyTargetIndex = -1; return; }

        int notifOffset = (newMessageCount > 0) ? NOTIF_H : 0;
        int gearX = panelX + 4;
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int barX = gearX + ICON_S + 4;
        int barW = sendX - 6 - barX;
        int barY = barTop - REPLY_BAR_H - notifOffset;

        float panelBgAlpha = (c().panelBg() >>> 24) / 255f;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.PANEL_BG),
            barX, barY, barW, barTop - notifOffset - barY, panelBgAlpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), barX, barTop - notifOffset - 1, barW, 1, getAnimProgress());

        String sender = target.senderName().getString();
        if (sender.isEmpty()) sender = Text.translatable("alfriendschat.sender.system").getString();
        String preview = sender + ": " + target.content().getString();
        int maxW = barW - 24;
        String display = textRenderer.trimToWidth(preview, maxW - textRenderer.getWidth("..."));
        if (!display.equals(preview)) display += "...";
        g.drawText(textRenderer, display, barX + 6, barY + 4, c().textSecondary(), false);

        int cx = barX + barW - 16;
        int cy = barY + 3;
        boolean hoverX = mouseX >= cx && mouseX <= cx + 12 && mouseY >= cy && mouseY <= cy + 12;
        int xBg = hoverX ? c().closeHoverBg() : c().sidebarItemSelected();
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(hoverX ? UiElement.CLOSE_HOVER : UiElement.SIDEBAR_SELECTED),
            cx, cy, 12, 12, getAnimProgress());
        g.drawText(textRenderer, "×", cx + 6 - textRenderer.getWidth("×") / 2, cy + 2, c().closeText(), false);
    }

    private boolean isMouseOverReplyCancel(double mx, double my) {
        if (replyTargetIndex < 0) return false;
        int notifOffset = (newMessageCount > 0) ? NOTIF_H : 0;
        int gearX = panelX + 4;
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int barX = gearX + ICON_S + 4;
        int barW = sendX - 6 - barX;
        int barY = barTop - REPLY_BAR_H - notifOffset;
        int cx = barX + barW - 16;
        int cy = barY + 3;
        return mx >= cx && mx <= cx + 12 && my >= cy && my <= cy + 12;
    }

    private void renderMentionPopup(DrawContext g, int mouseX, int mouseY) {
        if (!showMentions || mentionCandidates.isEmpty()) return;
        int maxW = 60;
        for (String name : mentionCandidates) maxW = Math.max(maxW, textRenderer.getWidth(name));
        int popupW = maxW + 12;
        int visible = Math.min(mentionCandidates.size(), 8);
        int popupH = visible * textRenderer.fontHeight + 4;
        //#if MC >= 12000
        int popupX = chatField.getX();
        int popupY = chatField.getY() - popupH - 2;
        if (popupY < msgTop) popupY = chatField.getY() + chatField.getHeight() + 2;
        //#else
        //$$ int popupX = GuiCompat.getWidgetX(chatField);
        //$$ int popupY = GuiCompat.getWidgetY(chatField) - popupH - 2;
        //$$ if (popupY < msgTop) popupY = GuiCompat.getWidgetY(chatField) + chatField.getHeight() + 2;
        //#endif

        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.POPUP_BG), popupX, popupY, popupW, popupH, getAnimProgress());
        drawRectBorder(g, popupX, popupY, popupW, popupH, ChatBubbleTheme.alphaBlend(c().divider(), (int) (255 * getAnimProgress())));

        int startIdx = Math.max(0, mentionIdx - visible + 1);
        int endIdx = Math.min(mentionCandidates.size(), startIdx + visible);
        if (endIdx - startIdx < visible) startIdx = Math.max(0, endIdx - visible);
        for (int i = startIdx; i < endIdx; i++) {
            int ly = popupY + 2 + (i - startIdx) * textRenderer.fontHeight;
            if (i == mentionIdx)
                g.fill(popupX + 1, ly, popupX + popupW - 1, ly + textRenderer.fontHeight, c().popupHover());
            g.drawText(textRenderer, mentionCandidates.get(i), popupX + 4, ly, c().textPrimary(), false);
        }
    }

    private void renderToast(DrawContext g) {
        // Priority: upload-failed (red) > uploading (orange) > copied (theme).
        int ticks;
        int rgb;
        String key;
        if (uploadToastTicks > 0) {
            ticks = uploadToastTicks;
            rgb = 0xFF5555; // red 鈥?upload failed
            key = "alfriendschat.upload.failed";
        } else if (uploadBusyTicks > 0) {
            ticks = uploadBusyTicks;
            rgb = 0xFFAA00; // orange 鈥?upload in progress
            key = "alfriendschat.upload.start";
        } else if (actionToastTicks > 0) {
            ticks = actionToastTicks;
            rgb = c().toastText() & 0x00FFFFFF;
            key = actionToastKey;
        } else if (copyToastTicks > 0) {
            ticks = copyToastTicks;
            rgb = c().toastText() & 0x00FFFFFF;
            key = "alfriendschat.toast.copied";
        } else if (emoteHintTicks > 0) {
            ticks = emoteHintTicks;
            rgb = c().toastText() & 0x00FFFFFF;
            key = "alfriendschat.emoji.add_hint";
        } else {
            return;
        }
        int alpha = Animation.fadeInOut(ticks, 5, 20, 5);
        int color = (alpha << 24) | (rgb & 0x00FFFFFF);
        String text = actionToastTicks > 0 && actionToastTarget != null
            ? Text.translatable(key, actionToastTarget).getString()
            : Text.translatable(key).getString();
        int tw = textRenderer.getWidth(text);
        int tx = UiLayout.centerX(panelX, panelW, tw);
        int ty = msgBottom - 24;
        // Background fades with the text, at half opacity like the strong-hint bar
        // TOAST_BG 鐑樼剻涓嶉€忔槑 toastBg锛涚汗鐞?脳 鍔ㄦ€?alpha = 鍗婇€忔槑娣″叆娣″嚭銆?.2.4 榛戝潡鏍瑰洜锛?
        // 褰撴椂 blit 鏃?alpha 閫氶亾娓叉煋涓嶉€忔槑绾粦 鈫?drawWithAlpha 鍚庣汗鐞嗗彲瑕嗙洊 + 閫忔槑搴﹀彲鎺?
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.TOAST_BG),
            tx - 6, ty - 2, tw + 12, textRenderer.fontHeight + 4, (alpha / 2) / 255f);
        g.drawText(textRenderer, text, tx, ty, color, false);
    }

    private void executeMenuAction(int action) {
        switch (action) {
            case 0: // search
                if (quickChatPanel.visible) { quickChatPanel.visible = false; quickChatInput.setVisible(false); }
                if (emojiPanel.visible) emojiPanel.visible = false;
                searchPanel.visible = true;
                searchAnimStart = Util.getMeasuringTimeMs();
                searchInput.setText("");
                searchMatches.clear(); searchMatchIdx = -1; searchHighlightIndex = -1;
                setFocused(searchInput);
                break;
            case 1: // quick_chat
                if (searchPanel.visible) closeSearchPanel();
                if (emojiPanel.visible) emojiPanel.visible = false;
                quickChatPanel.visible = true;
                quickAnimStart = Util.getMeasuringTimeMs();
                quickChatPanel.scrollOffset = 0;
                quickChatInput.setText("");
                setFocused(chatField);
                break;
            case 2: { // theme
                ChatBubbleTheme next = theme().next();
                ChatBubbleClientSetup.saveConfig(ChatBubbleClientSetup.config().withTheme(next.name().toLowerCase()));
                int editColor = next.usesLightControls() ? next.colors().textSecondary() : next.colors().textPrimary();
                chatField.setEditableColor(editColor);
                chatField.setUneditableColor(c().textMuted());
                sidebarSearchBox.setEditableColor(editColor);
                sidebarSearchBox.setUneditableColor(editColor);
                quickChatInput.setEditableColor(editColor);
                quickChatInput.setUneditableColor(c().textMuted());
                searchInput.setEditableColor(editColor);
                searchInput.setUneditableColor(c().textMuted());
                int cmdAlpha = next.usesLightControls() ? 0x99 : 0xDD;
                //#if MC >= 11900
                commandSuggestions = new ChatInputSuggestor(client, this, chatField, textRenderer,
                    false, false, 1, 10, true, ChatBubbleTheme.alphaBlend(c().panelBg(), cmdAlpha));
                //#endif
                //#if MC >= 11900
                commandSuggestions.setWindowActive(true);
                commandSuggestions.refresh();
                //#endif
                break;
            }
            case 3: // settings
                //#if MC >= 11700
                client.setScreen(new ChatBubbleConfigScreen(this));
                //#else
                //$$ client.openScreen(new ChatBubbleConfigScreen(this));
                //#endif
                break;
        }
    }

    private void closeSearchPanel() {
        searchPanel.visible = false;
        searchInput.setVisible(false);
        searchMatches.clear(); searchMatchIdx = -1; searchHighlightIndex = -1;
        setFocused(chatField);
    }

    private void renderBottomBar(DrawContext g, int mouseX, int mouseY, float panelAlpha) {
        int a255 = (int) (255 * panelAlpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.BOTTOM_BAR), panelX, barTop, panelW, height - barTop, panelAlpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), panelX, barTop, panelW, 1, panelAlpha);

        int iconY = barTop + (BAR_H - ICON_S) / 2;

        int ibX = inputX;
        int ibY = inputY;
        int ibW = chatField.getWidth();
        int ibH = INPUT_H;
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.DIVIDER), ibX - 1, ibY - 1, ibW + 1, 1, panelAlpha);
        ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.INPUT_BG), ibX - 1, ibY, ibW + 1, ibH, panelAlpha);

        boolean hoverInput = mouseX >= ibX - 1 && mouseX <= ibX + ibW && mouseY >= ibY && mouseY <= ibY + ibH;
        if (hoverInput || chatField.isFocused())
            drawRectBorder(g, ibX - 1, ibY, ibW + 1, ibH, ChatBubbleTheme.alphaBlend(c().textMuted(), a255));

        int gearX = panelX + 4;
        int sendX = panelX + panelW - PAD - ICON_S + 2;
        int emojiX = sendX - ICON_S - 6;

        boolean hoverGear = mouseX >= gearX && mouseX <= gearX + ICON_S
            && mouseY >= iconY && mouseY <= iconY + ICON_S;
        if (hoverGear) ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.HOVER_BG), gearX - 1, iconY - 1, ICON_S + 2, ICON_S + 2, panelAlpha);
        drawTextureIconAlpha(g, iconTex("settings"), gearX, iconY, ICON_S, getAnimProgress());

        boolean hoverEmoji = mouseX >= emojiX && mouseX <= emojiX + ICON_S
            && mouseY >= iconY && mouseY <= iconY + ICON_S;
        if (hoverEmoji || emojiPanel.visible) ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.HOVER_BG), emojiX - 1, iconY - 1, ICON_S + 2, ICON_S + 2, panelAlpha);
        drawTextureIconAlpha(g, iconTex("emoji"), emojiX, iconY, ICON_S, getAnimProgress());

        boolean hoverSend = mouseX >= sendX && mouseX <= sendX + ICON_S
            && mouseY >= iconY && mouseY <= iconY + ICON_S;
        if (hoverSend) ColoredTextureRenderer.drawWithAlpha(g, UiTextureManager.rl(UiElement.HOVER_BG), sendX - 1, iconY - 1, ICON_S + 2, ICON_S + 2, panelAlpha);
        drawTextureIconAlpha(g, iconTex("send"), sendX, iconY, ICON_S, getAnimProgress());
    }



    static void drawTextureIcon(DrawContext g, Identifier tex, int x, int y, int size) {
        // getTexture 鏃犵紦瀛樻椂鑷姩 new ResourceTexture 鎳掑姞杞斤紙璧勬簮鍖呭彲瑕嗙洊锛孎3+T 鍗虫椂鐢熸晥锛?
        //#if MC >= 11700
        //#if MC < 12102
        RenderSystem.setShaderTexture(0, tex);
        //#endif
        //#else
        //$$ MinecraftClient.getInstance().getTextureManager().bindTexture(tex);
        //#endif
        //#if MC >= 11700
        //#if MC < 12102
        // getPositionTexShader() was removed in 1.19.3 (MC 11903); getPositionTexProgram() introduced in 1.19.3.
        //#if MC >= 11903
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        //#else
        //$$ RenderSystem.setShader(GameRenderer::getPositionTexShader);
        //#endif
        //#endif
        //#endif
        //#if MC < 12105
        RenderSystem.enableBlend();
        //#endif
        if (size < 16) {
            // 鍥炬爣绾圭悊绾﹀畾 16x16锛堝唴瀹瑰眳涓紝鍥涘懆 1px 閫忔槑杈癸紝鍐呭鍗?14x14锛夈€傞噰鏍峰唴瀹瑰尯
            // (鍋忕Щ1,1) 瀹屾暣 14x14 缁樺埗鈥斺€旂獥鍙ｅ彇 size 浼氬垏鎺夊唴瀹瑰彸/涓?2px锛坈opy 鍙抽〉琚垏锛夈€?
            //#if MC >= 12106
            g.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, tex, x, y, 1.0F, 1.0F, size, size, 14, 14, 16, 16);
            //#else
            //#if MC >= 12102
            //$$ g.drawTexture(id -> net.minecraft.client.render.RenderLayer.getGuiTextured(id), tex, x, y, 1.0F, 1.0F, size, size, 14, 14, 16, 16);
            //#else
            //$$ g.drawTexture(tex, x, y, size, size, 1.0F, 1.0F, 14, 14, 16, 16);
            //#endif
            //#endif
        } else {
            //#if MC >= 12106
            g.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, tex, x, y, 0.0F, 0.0F, size, size, size, size);
            //#else
            //#if MC >= 12102
            //$$ g.drawTexture(id -> net.minecraft.client.render.RenderLayer.getGuiTextured(id), tex, x, y, 0, 0, size, size, size, size);
            //#else
            //$$ g.drawTexture(tex, x, y, 0, 0, size, size, size, size);
            //#endif
            //#endif
        }
    }

    /** 甯﹂€忔槑搴﹀浘鏍囩殑缁樺埗锛氫笌 drawTextureIcon 鍚岄噰鏍疯涔夛紝浣嗚蛋甯?alpha 鐨勬覆鏌撹矾寰勶紙寮瑰眰娣″叆鐢級銆?*/
    static void drawTextureIconAlpha(Object g, Identifier tex, int x, int y, int size, float alpha) {
        if (alpha <= 0.003f) return;
        if (size < 16) {
            ColoredTextureRenderer.drawWithAlpha(g, tex, x, y, size, size, 1f, 1f, 14, 14, 16, 16, alpha);
        } else {
            ColoredTextureRenderer.drawWithAlpha(g, tex, x, y, size, size, 0f, 0f, size, size, size, size, alpha);
        }
    }

    private static void drawFullTextureIconAlpha(Object g, Identifier tex, int x, int y, int size, float alpha) {
        ColoredTextureRenderer.drawWithAlpha(g, tex, x, y, size, size, alpha);
    }

    private static final UUID NIL_UUID = new UUID(0, 0);
    // Name-keyed skin cache: an offline player seen in chat history keeps the
    // real head when the UUID lookup fails (cracked servers, uuid dropped in
    // old history files). Key is the 搂-stripped lowercase name.
    private static final java.util.Map<String, Identifier> skinNameCache = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Identifier> eldest) {
            return size() > SKIN_CACHE_CAP;
        }
    };

    private static String skinNameKey(String name) {
        if (name == null) return null;
        String key = name.replaceAll("\u00A7.", "").trim().toLowerCase(java.util.Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    private void rememberSkin(UUID uuid, String name, Identifier tex) {
        if (tex == null || isDefaultSkin(tex)) return;
        if (uuid != null && !uuid.equals(NIL_UUID)) skinCache.put(uuid, tex);
        String key = skinNameKey(name);
        if (key != null) skinNameCache.put(key, tex);
    }

    private void drawPlayerHead(DrawContext g, Identifier skin, int x, int y, int baseSize, int hatSize, float alpha) {
        if (alpha <= 0.003f) return;
        ColoredTextureRenderer.drawWithAlpha(g, skin, x, y, baseSize, baseSize, 8.0F, 8.0F, 8, 8, 64, 64, alpha);
        int hatOff = (hatSize - baseSize) / 2;
        ColoredTextureRenderer.drawWithAlpha(g, skin, x - hatOff, y - hatOff, hatSize, hatSize, 40.0F, 8.0F, 8, 8, 64, 64, alpha);
    }

    private Identifier getSkin(UUID uuid, String name) {
        // Online players: read PlayerInfo fresh every frame 鈥?caching the first result
        // (default Steve/Alex while async download is in progress) would freeze the head
        // forever even after the real skin loaded. CSL intercepts the underlying lookup.
        if (client.getNetworkHandler() != null && uuid != null && !uuid.equals(NIL_UUID)) {
            PlayerListEntry info = client.getNetworkHandler().getPlayerListEntry(uuid);
            if (info != null) {
                try {
                    //#if MC >= 12109
                    Identifier tex = info.getSkinTextures().body().texturePath();
                    //#else
                    //#if MC >= 12004
                    //$$ Identifier tex = info.getSkinTextures().texture();
                    //#else
                    //$$ Identifier tex = info.getSkinTexture();
                    //#endif
                    //#endif
                    rememberSkin(uuid, name, tex);
                    return tex;
                } catch (Exception ignored) {
                    // Skin not loaded yet (texturesSupplier may be null on 1.21.11+);
                    // fall through to resolveSkin for async resolution.
                }
            }
        }
        // Offline player / history mention: route through SkinProvider with a name-bearing
        // GameProfile so CSL can match offline names to imported skins. Cache this result.
        if (uuid != null && !uuid.equals(NIL_UUID)) {
            Identifier cached = skinCache.get(uuid);
            if (cached != null && !isDefaultSkin(cached)) return cached;
        }
        String nameKey = skinNameKey(name);
        if (nameKey != null) {
            Identifier cachedByName = skinNameCache.get(nameKey);
            if (cachedByName != null && !isDefaultSkin(cachedByName)) return cachedByName;
        }
        Identifier resolved = resolveSkin(uuid, name);
        // Do NOT let a default-skin fallback overwrite a previously-cached real
        // skin. When a player was online we cached their actual texture; once they
        // go offline getPlayerListEntry returns null and resolveSkin can only
        // produce the default Steve/Alex. Overwriting would make their head vanish.
        // Instead, prefer the previously-cached real skin when available.
        if (isDefaultSkin(resolved)) {
            Identifier kept = (uuid != null && !uuid.equals(NIL_UUID)) ? skinCache.get(uuid) : null;
            if (kept == null) {
                String nk = skinNameKey(name);
                if (nk != null) kept = skinNameCache.get(nk);
            }
            if (kept != null) return kept;
            return resolved;
        }
        rememberSkin(uuid, name, resolved);
        return resolved;
    }

    private boolean isDefaultSkin(Identifier tex) {
        if (tex == null) return true;
        //#if MC >= 12109
        return tex.equals(DefaultSkinHelper.getSkinTextures(
            new GameProfile(NIL_UUID, "")).body().texturePath());
        //#else
        //#if MC >= 12004
        //$$ return tex.equals(DefaultSkinHelper.getSkinTextures(NIL_UUID).texture());
        //#else
        //$$ return tex.equals(DefaultSkinHelper.getTexture());
        //#endif
        //#endif
    }

    private Identifier resolveSkin(UUID uuid, String name) {
        // Disconnect guard: when the network is down (server disconnect in
        // progress) the synchronous supplySkinTextures().get() below can block
        // the render thread for a long time -> gray frozen window. Fall back to
        // the default skin instead.
        if (BlurRenderer.disconnecting || client.world == null || client.player == null) {
            return defaultSkinIdentifier(uuid, name);
        }
        // Route through PlayerSkinProvider with a name-bearing GameProfile so CSL
        // can match offline players to imported skins. getSkinTextures(GameProfile)
        // is the Yarn equivalent of Mojang's SkinManager.getInsecureSkin().
        if (name != null && !name.isEmpty()) {
            try {
                GameProfile profile = new GameProfile(
                    uuid != null && !uuid.equals(NIL_UUID) ? uuid : NIL_UUID, name);
                //#if MC >= 12109
                // supplySkinTextures returns a Supplier whose get() synchronously
                // performs the profile lookup. During a server disconnect the
                // network is down and this can block the render thread for a long
                // time (gray frozen window). The disconnecting guard at the top of
                // resolveSkin short-circuits before we ever reach this call.
                return client.getSkinProvider().supplySkinTextures(profile, false).get().body().texturePath();
                //#else
                //#if MC >= 12004
                //$$ return client.getSkinProvider().getSkinTextures(profile).texture();
                //#else
                //$$ java.util.Map<com.mojang.authlib.minecraft.MinecraftProfileTexture.Type, com.mojang.authlib.minecraft.MinecraftProfileTexture> skinMap = client.getSkinProvider().getTextures(profile);
                //$$ com.mojang.authlib.minecraft.MinecraftProfileTexture skinTex = skinMap.get(com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN);
                //$$ if (skinTex != null) return client.getSkinProvider().loadSkin(skinTex, com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN);
                //#endif
                //#endif
            } catch (Exception ignored) {}
        }
        //#if MC >= 12109
        return DefaultSkinHelper.getSkinTextures(
            new GameProfile(uuid != null ? uuid : NIL_UUID, name != null ? name : "")).body().texturePath();
        //#else
        //#if MC >= 12004
        //$$ return DefaultSkinHelper.getSkinTextures(uuid != null ? uuid : NIL_UUID).texture();
        //#else
        //$$ return DefaultSkinHelper.getTexture();
        //#endif
        //#endif
    }

    private Identifier defaultSkinIdentifier(UUID uuid, String name) {
        //#if MC >= 12109
        return DefaultSkinHelper.getSkinTextures(
            new GameProfile(uuid != null ? uuid : NIL_UUID, name != null ? name : "")).body().texturePath();
        //#else
        //#if MC >= 12004
        //$$ return DefaultSkinHelper.getSkinTextures(uuid != null ? uuid : NIL_UUID).texture();
        //#else
        //$$ return DefaultSkinHelper.getTexture();
        //#endif
        //#endif
    }

    private void jumpToMessage(int msgIndex) {
        var msgs = ChatMessageStore.getMessages();
        if (msgIndex < 0 || msgIndex >= msgs.size()) return;
        int cy = 0;
        String lk = null;
        for (int i = 0; i < msgIndex && i < msgs.size(); i++) {
            var m = msgs.get(i);
            if (!m.isSystem()) {
                String k = timeKey(m.time());
                if (lk == null || !k.equals(lk)) { lk = k; cy += TIME_SEP_H + messageGap(); }
            }
            cy += getMsgHeight(m) + messageGap();
        }
        scrollOffset = Math.max(0, cy - 20);
        newMessageCount = 0; hasNewMentionOrQuote = false;
        latestMentionIndex = -1; lastSeenMessageCount = msgs.size();
    }

    private static Text parseColorCodes(String s) {
        if (s.indexOf('&') < 0) return Text.literal(s);
        MutableText out = Text.empty();
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && isFormatCode(s.charAt(i + 1))) {
                if (run.length() > 0) {
                    out.append(Text.literal(run.toString()).fillStyle(style));
                    run.setLength(0);
                }
                style = applyCode(style, s.charAt(i + 1));
                i++;
            } else {
                run.append(c);
            }
        }
        if (run.length() > 0) out.append(Text.literal(run.toString()).fillStyle(style));
        return out;
    }

    private static Style applyCode(Style st, char c) {
        switch (Character.toLowerCase(c)) {
            case '0': return st.withColor(Formatting.BLACK.getColorValue() != null ? Formatting.BLACK.getColorValue() : null);
            case '1': return st.withColor(Formatting.DARK_BLUE.getColorValue() != null ? Formatting.DARK_BLUE.getColorValue() : null);
            case '2': return st.withColor(Formatting.DARK_GREEN.getColorValue() != null ? Formatting.DARK_GREEN.getColorValue() : null);
            case '3': return st.withColor(Formatting.DARK_AQUA.getColorValue() != null ? Formatting.DARK_AQUA.getColorValue() : null);
            case '4': return st.withColor(Formatting.DARK_RED.getColorValue() != null ? Formatting.DARK_RED.getColorValue() : null);
            case '5': return st.withColor(Formatting.DARK_PURPLE.getColorValue() != null ? Formatting.DARK_PURPLE.getColorValue() : null);
            case '6': return st.withColor(Formatting.GOLD.getColorValue() != null ? Formatting.GOLD.getColorValue() : null);
            case '7': return st.withColor(Formatting.GRAY.getColorValue() != null ? Formatting.GRAY.getColorValue() : null);
            case '8': return st.withColor(Formatting.DARK_GRAY.getColorValue() != null ? Formatting.DARK_GRAY.getColorValue() : null);
            case '9': return st.withColor(Formatting.BLUE.getColorValue() != null ? Formatting.BLUE.getColorValue() : null);
            case 'a': return st.withColor(Formatting.GREEN.getColorValue() != null ? Formatting.GREEN.getColorValue() : null);
            case 'b': return st.withColor(Formatting.AQUA.getColorValue() != null ? Formatting.AQUA.getColorValue() : null);
            case 'c': return st.withColor(Formatting.RED.getColorValue() != null ? Formatting.RED.getColorValue() : null);
            case 'd': return st.withColor(Formatting.LIGHT_PURPLE.getColorValue() != null ? Formatting.LIGHT_PURPLE.getColorValue() : null);
            case 'e': return st.withColor(Formatting.YELLOW.getColorValue() != null ? Formatting.YELLOW.getColorValue() : null);
            case 'f': return st.withColor(Formatting.WHITE.getColorValue() != null ? Formatting.WHITE.getColorValue() : null);
            case 'k': return st.withObfuscated(true);
            case 'l': return st.withBold(true);
            case 'm': return st.withStrikethrough(true);
            case 'n': return st.withUnderline(true);
            case 'o': return st.withItalic(true);
            case 'r': return Style.EMPTY;
            default: return st;
        }
    }

    private static boolean isFormatCode(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
            || (c >= 'k' && c <= 'o') || (c >= 'A' && c <= 'F')
            || (c >= 'K' && c <= 'O');
    }

    /** Extracts the local path from [[CICode,url=file:///...]] (chatimage appends Windows backslash paths). */
    private static String extractLocalPath(String cicode) {
        int start = cicode.indexOf("url=file:///");
        if (start < 0) return null;
        start += "url=file:///".length();
        int end = cicode.indexOf("]]", start);
        if (end < 0) end = cicode.length();
        String path = cicode.substring(start, end);
        // file:///C:\... 鈫?C:\... (drop the leading slash before the drive letter)
        if (path.startsWith("/") && path.length() > 1 && path.charAt(1) == ':') return path.substring(1);
        return path;
    }

    private void sendMessage() {
        String raw = chatField.getText().trim();
        if (raw.isEmpty()) return;
        if (whisperPartner != null && !ALFriendsBridge.active()) return;
        if (whisperPartner != null && raw.charAt(0) != '/') {
            String quoteSender = "";
            String quoteContent = "";
            if (replyTargetIndex >= 0) {
                ChatMessageStore.ChatMessage target = ChatMessageStore.getMessageAt(replyTargetIndex);
                if (target != null) {
                    quoteSender = target.rawPlayerName() != null && !target.rawPlayerName().isEmpty()
                        ? target.rawPlayerName() : target.senderName().getString();
                    quoteContent = ChatMessageStore.singleLine(target.content().getString());
                }
            }
            if (ALFriendsBridge.send(whisperPartnerUuid, raw, quoteSender, quoteContent)) {
                replyTargetIndex = -1;
                client.inGameHud.getChatHud().addToMessageHistory(raw);
                chatField.setText(String.valueOf(new char[0]));
                return;
            }
        }
        if (raw.contains("[[CICode,url=file://")) {
            // A local file:// CICode (chatimage's drag/paste handler inserts
            // these) is a local-only broken link. Kick off our own upload and
            // block the send until it replaces the link.
            if (!uploading) {
                String localPath = extractLocalPath(raw);
                if (localPath != null) upload(new java.io.File(localPath));
            }
            //#if MC >= 26000
            //$$ minecraft.player.sendSystemMessage(Text.translatable("alfriendschat.upload.wait"));
            //#else
            client.player.sendMessage(Text.translatable("alfriendschat.upload.wait"), false);
            //#endif
            ChatMessageStore.debugLog("[alfriendschat] upload block | uploading=" + uploading + " | raw=" + raw);
            return;
        }
        var cfg = ChatBubbleClientSetup.config();
        // Send the text UNCHANGED (raw '&', never '搂'): vanilla servers reject '搂' in
        // player chat and kick, so converting client-side is a dead end. Server color
        // plugins (Essentials etc.) translate '&' for everyone; on plain servers others
        // see the literal '&'. Local coloring of our own bubble is done at addMessage.
        String text = raw;

        String whisperTarget = null;
        String displayText = text;
        if (text.startsWith("/msg ") || text.startsWith("/tell ") || text.startsWith("/w ") || text.startsWith("/whisper ")) {
            String[] parts = text.split(" ", 3);
            if (parts.length >= 3) { whisperTarget = parts[1]; displayText = parts[2]; }
        }

        boolean localBubble = !text.startsWith("/") || whisperTarget != null;

        if (replyTargetIndex >= 0) {
            if (localBubble) {
                ChatMessageStore.ChatMessage target = ChatMessageStore.getMessageAt(replyTargetIndex);
                if (target != null) {
                    String quoteSender = (target.rawPlayerName() != null && !target.rawPlayerName().isEmpty())
                        ? target.rawPlayerName() : target.senderName().getString();
                    String quoted = ChatMessageStore.singleLine(target.content().getString());
                    ChatMessageStore.setPendingReply(quoted, quoteSender);
                    //#if MC >= 12005
                    ClientPlayNetworking.send(new QuoteSyncPayload(quoteSender, quoted, displayText));
                    //#endif
                }
            }
            replyTargetIndex = -1;
        }

        GuiCompat.sendChat(client.player.networkHandler, text);
        client.inGameHud.getChatHud().addToMessageHistory(text);

        // fix3: re-sync historyPos to the newest end 鈥?while the screen stays open
        // the init() snapshot goes stale, and up-arrow would skip freshly sent entries.
        historyPos = client.inGameHud.getChatHud().getMessageHistory().size();

        ChatMessageStore.debugLog("[alfriendschat] Send | cmd='" + text + "' | display='" + displayText + "' | whisperTarget=" + whisperTarget + " | localBubble=" + localBubble);
        if (localBubble) {
            Text contentForSend = cfg != null && cfg.colorCodes() ? parseColorCodes(displayText) : Text.literal(displayText);
            // 2.3.10+: keep image bracket codes raw so the local bubble renders
            // the picture natively (BracketCodec + ImageLoader); the vanilla chat
            // echo is converted by ChatImage's own mixins when installed.
            String playerName = client.player.getName().getString();
            String replySender = ChatMessageStore.getPendingReplySender();

            ChatMessageStore.addMessage(contentForSend,
                client.player.getUuid(),
                ChatMessageStore.ownDisplayName(),
                false,
                playerName,
                whisperTarget != null, whisperTarget, true);
            ChatMessageStore.incrementPendingEcho(text);

            // Trigger mention detection directly from send path.
            // The echo will be consumed (preventing duplicate bubbles),
            // so the controller must fire here for self-@ notifications.
            if (com.alinegames.alfriends.client.chat.MentionDetector.isMentioned(
                    contentForSend.getString(), playerName,
                    cfg.mentionRequireAt(), replySender)) {
                com.alinegames.alfriends.client.chat.notification.MentionNotificationController.INSTANCE.onMessageCaptured(
                    contentForSend,
                    new ChatMessageStore.SenderMeta(client.player.getUuid(),
                        Text.literal(playerName), contentForSend, false,
                        playerName, whisperTarget != null, whisperTarget),
                    ChatMessageStore.size(), replySender);
            }
        }
        if (whisperTarget != null) ChatMessageStore.markPendingWhisperEcho(whisperTarget);

        chatField.setText("");
        savedInput = "";
        scrollToBottom = true;

        // 2.4.0 sync: close the chat screen right after the message goes out
        // (off by default 鈥?this screen supports multi-send).
        if (cfg != null && cfg.closeChatOnSend()) onClose();
    }


    // 鐖剁被 setChatFromHistory 璁块棶 package-private chatInputSuggestor锛堣法鍖?null锛夛紝
    // override 鐢ㄨ嚜宸辩殑瀹炵幇锛坔istory 瀛楁涔熺鏈夊寲鍒版湰绫伙級
    @Override
    public void setChatFromHistory(int offset) {
        int size = client.inGameHud.getChatHud().getMessageHistory().size();
        int newPos = MathHelper.clamp(historyPos + offset, 0, size);
        if (newPos != historyPos) {
            if (newPos == size) {
                historyPos = size;
                chatField.setText(historyBuffer);
            } else {
                if (historyPos == size) historyBuffer = chatField.getText();
                chatField.setText(client.inGameHud.getChatHud().getMessageHistory().get(newPos));
                historyPos = newPos;
            }
        }
    }

    @Override
    //#if MC >= 12111
    public void resize(int width, int height) {
    //#else
    //$$ public void resize(MinecraftClient client, int width, int height) {
    //#endif
        String cur = chatField.getText();
        //#if MC >= 12111
        this.init(width, height);
        //#else
        // this.init(client, width, height);
        //#endif
        chatField.setText(cur);
    }

    @Override
    public void removed() {
        if (ChatBubbleClientSetup.config().preserveInput()) savedInput = chatField.getText();
        ChatMessageStore.setOpenWhisperPartner(null);
        ChatMessageStore.setScreenOpen(false);
        // Guard: during server disconnect, world may already be null 鈥?calling
        // ChatHud.reset() at that point can trigger unsafe state access.
        if (client != null && client.world != null && client.inGameHud != null && client.inGameHud.getChatHud() != null) {
            client.inGameHud.getChatHud().reset();
        }
    }

    public void onClose() {
        if (ChatBubbleClientSetup.config().preserveInput()) savedInput = chatField.getText();
        if (!ChatBubbleClientSetup.config().animationEnabled()) {
            //#if MC >= 11700
            client.setScreen(null); return;
            //#else
            //$$ client.openScreen(null);
            //#endif
        }
        if (closing) return;
        closing = true;
        animStart = Util.getMeasuringTimeMs();
    }

    //#if MC >= 11700
    public boolean shouldPause() { return false; }
    //#else
    //$$ public boolean isPauseScreen() { return false; }
    //#endif

    private static class ClickableSpan {
        final int x, y, w, h;
        final Style style;
        final String imageUrl;
        ClickableSpan(int x, int y, int w, int h, Style style) { this(x, y, w, h, style, null); }
        ClickableSpan(int x, int y, int w, int h, Style style, String imageUrl) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.style = style; this.imageUrl = imageUrl;
        }
    }
    private void openImagePreview(String url) {
        imagePreviewUrl = url;
        imagePreviewScale = 1.0F;
        imagePreviewOffsetX = 0.0;
        imagePreviewOffsetY = 0.0;
        imagePreviewDragging = false;
    }

    private void closeImagePreview() {
        imagePreviewUrl = null;
        imagePreviewScale = 1.0F;
        imagePreviewOffsetX = 0.0;
        imagePreviewOffsetY = 0.0;
        imagePreviewDragging = false;
    }

    private void renderImagePreview(DrawContext g) {
        if (imagePreviewUrl == null) return;
        ImageEntry entry = ImageLoader.getOrLoad(imagePreviewUrl);
        g.fill(0, 0, width, height, 0xB8000000);
        if (entry == null || entry.state() != ImageEntry.State.LOADED || entry.textureId() == null) {
            String text = Text.translatable("alfriendschat.image.loading").getString();
            g.drawText(textRenderer, text, width / 2 - textRenderer.getWidth(text) / 2, height / 2, 0xFFFFFFFF, false);
            return;
        }
        int maxW = Math.max(1, width - 40), maxH = Math.max(1, height - 60);
        float fitScale = Math.min(1.0F,
            Math.min((float) maxW / entry.width(), (float) maxH / entry.height()));
        float scale = fitScale * imagePreviewScale;
        int w = Math.max(1, (int)(entry.width() * scale)), h = Math.max(1, (int)(entry.height() * scale));
        int x = (width - w) / 2 + (int) Math.round(imagePreviewOffsetX);
        int y = (height - h) / 2 + (int) Math.round(imagePreviewOffsetY);
        //#if MC >= 12106
        g.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, entry.textureId(), x, y, 0.0F, 0.0F, w, h, entry.width(), entry.height(), entry.width(), entry.height());
        //#else
        //$$ g.drawTexture(entry.textureId(), x, y, w, h, 0, 0, entry.width(), entry.height(), entry.width(), entry.height());
        //#endif
        String hint = "左键拖动 · 右键/ESC关闭";
        String zoomHint = "滚轮缩放 " + Math.round(imagePreviewScale * 100.0F) + "%";
        String previewHint = hint + "  ·  " + zoomHint;
        g.drawText(textRenderer, previewHint, width / 2 - textRenderer.getWidth(previewHint) / 2, height - 24, 0xFFFFFFFF, false);
    }

}
