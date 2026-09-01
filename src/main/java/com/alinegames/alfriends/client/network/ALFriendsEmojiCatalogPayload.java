package com.alinegames.alfriends.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

import java.util.ArrayList;
import java.util.List;

public record ALFriendsEmojiCatalogPayload(List<Entry> entries) implements CustomPayload {
    public static final Id<ALFriendsEmojiCatalogPayload> ID = new Id<>(PayloadIds.emojiCatalog());
    public static final PacketCodec<PacketByteBuf, ALFriendsEmojiCatalogPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> encode(buf, value),
        //#else
        //$$ (value, buf) -> encode(buf, value),
        //#endif
        ALFriendsEmojiCatalogPayload::decode);

    private static void encode(PacketByteBuf buf, ALFriendsEmojiCatalogPayload value) {
        buf.writeVarInt(value.entries.size());
        for (Entry entry : value.entries) {
            buf.writeString(entry.name, 256);
            buf.writeString(entry.glyph, 64);
        }
    }

    private static ALFriendsEmojiCatalogPayload decode(PacketByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > 1_000) throw new IllegalArgumentException();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readString(256), buf.readString(64)));
        }
        return new ALFriendsEmojiCatalogPayload(List.copyOf(entries));
    }

    @Override public Id<ALFriendsEmojiCatalogPayload> getId() { return ID; }

    public record Entry(String name, String glyph) {}
}
