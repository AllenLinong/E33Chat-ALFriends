package com.alinegames.alfriends.client.integration;

import com.alinegames.alfriends.client.ChatBubbleConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ChatBubbleConfigScreen::new;
    }
}
