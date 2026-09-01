package com.alinegames.alfriends.client.network;

import net.minecraft.util.Identifier;

/**
 * Server-safe Identifier factory for network payloads.
 * MUST NOT import any client-only classes (MinecraftClient, Screen, etc.).
 * Extracted from GuiCompat to avoid pulling client classes into server-side
 * payload static initializers, which causes NoClassDefFoundError on dedicated servers.
 */
public final class PayloadIds {
    private PayloadIds() {}

    public static Identifier of(String path) {
        //#if MC >= 12000
        return Identifier.of("alfriendschat", path);
        //#else
        //$$ return new Identifier("alfriendschat", path);
        //#endif
    }

    public static Identifier hello() { return of(chars('h', 'e', 'l', 'l', 'o')); }
    public static Identifier helloAck() { return of(chars('h', 'e', 'l', 'l', 'o', '_', 'a', 'c', 'k')); }
    public static Identifier send() { return of(chars('s', 'e', 'n', 'd')); }
    public static Identifier message() { return of(chars('m', 'e', 's', 's', 'a', 'g', 'e')); }
    public static Identifier openChat() { return of(chars('o', 'p', 'e', 'n', '_', 'c', 'h', 'a', 't')); }
    public static Identifier contacts() { return of(chars('c', 'o', 'n', 't', 'a', 'c', 't', 's')); }
    public static Identifier history() { return of(chars('h', 'i', 's', 't', 'o', 'r', 'y')); }

    private static String chars(char... value) { return new String(value); }
}
