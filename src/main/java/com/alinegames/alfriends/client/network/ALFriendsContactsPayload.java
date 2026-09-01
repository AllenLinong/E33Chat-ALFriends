package com.alinegames.alfriends.client.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ALFriendsContactsPayload(List<Contact> contacts) implements CustomPayload {
    public static final Id<ALFriendsContactsPayload> ID = new Id<>(PayloadIds.contacts());
    public static final PacketCodec<PacketByteBuf, ALFriendsContactsPayload> CODEC = PacketCodec.of(
        //#if MC >= 26000
        (buf, value) -> encode(buf, value),
        //#else
        //$$ (value, buf) -> encode(buf, value),
        //#endif
        ALFriendsContactsPayload::decode);

    private static void encode(PacketByteBuf buf, ALFriendsContactsPayload value) {
        buf.writeVarInt(value.contacts.size());
        for (Contact contact : value.contacts) {
            buf.writeString(contact.uuid.toString());
            buf.writeString(contact.name);
            buf.writeVarInt(contact.unread);
            buf.writeLong(contact.lastMessageAt);
            buf.writeBoolean(contact.friend);
            buf.writeBoolean(contact.blocked);
        }
    }

    private static ALFriendsContactsPayload decode(PacketByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > 10_000) throw new IllegalArgumentException();
        List<Contact> contacts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            contacts.add(new Contact(UUID.fromString(buf.readString(64)), buf.readString(256),
                buf.readVarInt(), buf.readLong(), buf.readBoolean(), buf.readBoolean()));
        }
        return new ALFriendsContactsPayload(contacts);
    }

    @Override public Id<ALFriendsContactsPayload> getId() { return ID; }

    public record Contact(UUID uuid, String name, int unread, long lastMessageAt,
                          boolean friend, boolean blocked) {}
}
