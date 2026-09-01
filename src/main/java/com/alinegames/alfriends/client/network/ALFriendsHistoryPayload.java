package com.alinegames.alfriends.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ALFriendsHistoryPayload(UUID partner, String partnerName, List<Entry> entries) implements CustomPayload {
    public static final Id<ALFriendsHistoryPayload> ID = new Id<>(PayloadIds.history());
    public static final PacketCodec<PacketByteBuf, ALFriendsHistoryPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> encode(buf, value),
        //#else
        //$$ (value, buf) -> encode(buf, value),
        //#endif
        ALFriendsHistoryPayload::decode);

    private static void encode(PacketByteBuf buf, ALFriendsHistoryPayload value) {
        buf.writeString(value.partner.toString());
        buf.writeString(value.partnerName);
        buf.writeVarInt(value.entries.size());
        for (Entry entry : value.entries) {
            buf.writeString(entry.sender.toString());
            buf.writeString(entry.senderName);
            buf.writeString(entry.content);
            buf.writeLong(entry.sentAt);
            buf.writeString(entry.quoteSender == null ? "" : entry.quoteSender);
            buf.writeString(entry.quoteContent == null ? "" : entry.quoteContent);
        }
    }

    private static ALFriendsHistoryPayload decode(PacketByteBuf buf) {
        UUID partner = UUID.fromString(buf.readString(64));
        String partnerName = buf.readString(256);
        int count = buf.readVarInt();
        if (count < 0 || count > 500) throw new IllegalArgumentException();
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(UUID.fromString(buf.readString(64)), buf.readString(256),
                buf.readString(8000), buf.readLong(), buf.readString(256), buf.readString(1000)));
        }
        return new ALFriendsHistoryPayload(partner, partnerName, entries);
    }

    @Override public Id<ALFriendsHistoryPayload> getId() { return ID; }

    public record Entry(UUID sender, String senderName, String content, long sentAt,
                        String quoteSender, String quoteContent) {}
}
