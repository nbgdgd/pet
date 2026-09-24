package com.aniblaze.tokyoghoul.compat;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;

public class ModMenuIntegration {
    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static String getVersion() {
        return ModConfig.VERSION;
    }
}
