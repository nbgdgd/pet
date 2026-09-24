package com.aniblaze.tokyoghoul;

import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import com.aniblaze.tokyoghoul.config.ModConfig;
import com.aniblaze.tokyoghoul.entity.ModEntities;
import com.aniblaze.tokyoghoul.init.ModAbilities;
import com.aniblaze.tokyoghoul.init.ModComponents;
import com.aniblaze.tokyoghoul.init.ModKaguneTypes;
import com.aniblaze.tokyoghoul.init.ModRegistries;
import com.aniblaze.tokyoghoul.items.ModItems;
import com.aniblaze.tokyoghoul.network.ModNetworking;
import com.aniblaze.tokyoghoul.util.ModDamageSources;
import com.aniblaze.tokyoghoul.util.ModSounds;
import com.aniblaze.tokyoghoul.util.TokyoGhoulEventHandler;
import dev.onyxstudios.cca.api.v3.entity.EntityComponentFactoryRegistry;
import dev.onyxstudios.cca.api.v3.entity.EntityComponentInitializer;
import dev.onyxstudios.cca.api.v3.entity.RespawnCopyStrategy;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TokyoGhoulMod implements ModInitializer, EntityComponentInitializer {
    public static final String MOD_ID = "tokyoghoul";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static TokyoGhoulMod instance;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("Initializing Tokyo Ghoul Mod v{}", ModConfig.VERSION);

        ModConfig.init();

        ModRegistries.KAGUNE_TYPE.toString();
        ModRegistries.ABILITY.toString();
        ModKaguneTypes.init();
        ModAbilities.init();

        ModItems.register();
        ModEntities.register();
        ModSounds.register();
        ModDamageSources.register();

        ModNetworking.registerC2SPackets();
        TokyoGhoulEventHandler.register();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : server.getPlayerManager().getPlayerList()) {
                GhoulComponent data = GhoulComponent.get(player);
                if (data.isGhoul()) {
                    data.tick();
                }
            }
        });

        LOGGER.info("Tokyo Ghoul Mod initialized successfully!");
    }

    @Override
    public void registerEntityComponentFactories(EntityComponentFactoryRegistry registry) {
        registry.registerForPlayers(ModComponents.GHOUL, GhoulComponent::new, RespawnCopyStrategy.ALWAYS_COPY);
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    public static TokyoGhoulMod getInstance() {
        return instance;
    }
}
