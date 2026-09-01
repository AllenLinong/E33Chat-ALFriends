package com.alinegames.alfriends.client.mixin;

//#if MC >= 11900
import com.alinegames.alfriends.client.ChatMessageStore.SenderMeta;
import net.minecraft.client.network.message.MessageHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MessageHandler.class)
public interface MessageHandlerAccessor {
    @Invoker(value = "tryParseAsPlayerMessage", remap = false)
    static SenderMeta alfriendschat$invokeTryParseAsPlayerMessage(Text message, String text) {
        throw new AssertionError();
    }
}
//#else
//$$ public interface MessageHandlerAccessor {
//$$ }
//#endif
