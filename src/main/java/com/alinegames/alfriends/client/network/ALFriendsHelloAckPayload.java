package com.alinegames.alfriends.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public record ALFriendsHelloAckPayload(int protocol, boolean accepted) implements CustomPayload {
    public static final Id<ALFriendsHelloAckPayload> ID = new Id<>(PayloadIds.helloAck());
    public static final PacketCodec<PacketByteBuf, ALFriendsHelloAckPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> { buf.writeVarInt(value.protocol); buf.writeBoolean(value.accepted); },
        //#else
        //$$ (value, buf) -> { buf.writeVarInt(value.protocol); buf.writeBoolean(value.accepted); },
        //#endif
        buf -> new ALFriendsHelloAckPayload(buf.readVarInt(), buf.readBoolean()));
    @Override public Id<ALFriendsHelloAckPayload> getId() { return ID; }
}
