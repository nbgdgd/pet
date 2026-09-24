package com.aniblaze.tokyoghoul.init;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.api.KaguneAbility;
import com.aniblaze.tokyoghoul.api.KaguneType;
import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.SimpleRegistry;

public class ModRegistries {
    public static final RegistryKey<Registry<KaguneType>> KAGUNE_TYPE_KEY = RegistryKey.ofRegistry(TokyoGhoulMod.id("kagune_type"));
    public static final SimpleRegistry<KaguneType> KAGUNE_TYPE = FabricRegistryBuilder.createSimple(KAGUNE_TYPE_KEY).buildAndRegister();

    public static final RegistryKey<Registry<KaguneAbility>> ABILITY_KEY = RegistryKey.ofRegistry(TokyoGhoulMod.id("ability"));
    public static final SimpleRegistry<KaguneAbility> ABILITY = FabricRegistryBuilder.createSimple(ABILITY_KEY).buildAndRegister();
}
