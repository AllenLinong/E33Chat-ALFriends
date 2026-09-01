package com.alinegames.alfriends.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import com.alinegames.alfriends.client.image.RasterImageDecoder;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CustomBackgroundTexture {
    private static final Identifier ID = Identifier.of("alfriendschat", "custom/background");
    private static String loadedPath;
    private static long loadedModified = Long.MIN_VALUE;
    private static int width;
    private static int height;
    private static boolean failed;

    private CustomBackgroundTexture() {}

    public static Entry get(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) return null;
        MinecraftClient client = MinecraftClient.getInstance();
        Path path = Path.of(configuredPath.trim());
        if (!path.isAbsolute()) path = client.runDirectory.toPath().resolve(path);
        path = path.normalize();
        long modified;
        try {
            if (!Files.isRegularFile(path)) return null;
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return null;
        }
        String key = path.toAbsolutePath().toString();
        if (key.equals(loadedPath) && modified == loadedModified) {
            return failed ? null : new Entry(ID, width, height);
        }
        loadedPath = key;
        loadedModified = modified;
        failed = true;
        try {
            RasterImageDecoder.DecodedImage decoded = RasterImageDecoder.decode(Files.readAllBytes(path));
            if (decoded == null) return null;
            var image = decoded.image();
            width = decoded.width();
            height = decoded.height();
            if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
                image.close();
                return null;
            }
            client.getTextureManager().destroyTexture(ID);
            //#if MC >= 12105
            client.getTextureManager().registerTexture(ID, new NativeImageBackedTexture(() -> ID.toString(), image));
            //#else
            //$$ client.getTextureManager().registerTexture(ID, new NativeImageBackedTexture(image));
            //#endif
            failed = false;
            return new Entry(ID, width, height);
        } catch (Exception ignored) {
            return null;
        }
    }

    public record Entry(Identifier id, int width, int height) {}
}
