package com.alinegames.alfriends.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.UUID;

public record ALFriendsMessagePayload(UUID partner, String partnerName, UUID sender, String senderName,
                                      String content, long sentAt, boolean outgoing,
                                      String quoteSender, String quoteContent) implements CustomPayload {
    public static final Id<ALFriendsMessagePayload> ID = new Id<>(PayloadIds.message());
    public static final PacketCodec<PacketByteBuf, ALFriendsMessagePayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> encode(buf, value),
        //#else
        //$$ (value, buf) -> encode(buf, value),
        //#endif
        ALFriendsMessagePayload::decode);
    private static void encode(PacketByteBuf buf, ALFriendsMessagePayload value) {
        buf.writeString(value.partner.toString()); buf.writeString(value.partnerName);
        buf.writeString(value.sender.toString()); buf.writeString(value.senderName);
        buf.writeString(value.content); buf.writeLong(value.sentAt); buf.writeBoolean(value.outgoing);
        buf.writeString(value.quoteSender == null ? "" : value.quoteSender);
        buf.writeString(value.quoteContent == null ? "" : value.quoteContent);
    }
    private static ALFriendsMessagePayload decode(PacketByteBuf buf) {
        return new ALFriendsMessagePayload(UUID.fromString(buf.readString()), buf.readString(256),
            UUID.fromString(buf.readString()), buf.readString(256), buf.readString(8000), buf.readLong(), buf.readBoolean(),
            buf.readString(256), buf.readString(1000));
    }
    @Override public Id<ALFriendsMessagePayload> getId() { return ID; }
}
