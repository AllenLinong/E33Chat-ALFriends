package com.alinegames.alfriends.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.UUID;

public record ALFriendsOpenChatPayload(UUID target, String targetName) implements CustomPayload {
    public static final Id<ALFriendsOpenChatPayload> ID = new Id<>(PayloadIds.openChat());
    public static final PacketCodec<PacketByteBuf, ALFriendsOpenChatPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> { buf.writeString(value.target.toString()); buf.writeString(value.targetName); },
        //#else
        //$$ (value, buf) -> { buf.writeString(value.target.toString()); buf.writeString(value.targetName); },
        //#endif
        buf -> new ALFriendsOpenChatPayload(UUID.fromString(buf.readString()), buf.readString(256)));
    @Override public Id<ALFriendsOpenChatPayload> getId() { return ID; }
}
