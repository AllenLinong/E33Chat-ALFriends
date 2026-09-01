package com.alinegames.alfriends.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public record ALFriendsHelloPayload(int protocol) implements CustomPayload {
    public static final Id<ALFriendsHelloPayload> ID = new Id<>(PayloadIds.hello());
    public static final PacketCodec<PacketByteBuf, ALFriendsHelloPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> buf.writeVarInt(value.protocol),
        //#else
        //$$ (value, buf) -> buf.writeVarInt(value.protocol),
        //#endif
        buf -> new ALFriendsHelloPayload(buf.readVarInt()));
    @Override public Id<ALFriendsHelloPayload> getId() { return ID; }
}
