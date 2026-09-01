package com.alinegames.alfriends.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.UUID;

public record ALFriendsSendPayload(UUID receiver, String message, String quoteSender, String quoteContent) implements CustomPayload {
    public static final Id<ALFriendsSendPayload> ID = new Id<>(PayloadIds.send());
    public static final PacketCodec<PacketByteBuf, ALFriendsSendPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> encode(buf, value),
        //#else
        //$$ (value, buf) -> encode(buf, value),
        //#endif
        ALFriendsSendPayload::decode);
    private static void encode(PacketByteBuf buf, ALFriendsSendPayload value) {
        buf.writeString(value.receiver.toString());
        buf.writeString(value.message);
        buf.writeString(value.quoteSender == null ? "" : value.quoteSender);
        buf.writeString(value.quoteContent == null ? "" : value.quoteContent);
    }
    private static ALFriendsSendPayload decode(PacketByteBuf buf) {
        return new ALFriendsSendPayload(UUID.fromString(buf.readString(64)), buf.readString(8000),
            buf.readString(256), buf.readString(1000));
    }
    @Override public Id<ALFriendsSendPayload> getId() { return ID; }
}
