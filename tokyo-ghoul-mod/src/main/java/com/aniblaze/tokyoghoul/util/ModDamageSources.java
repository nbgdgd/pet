package com.aniblaze.tokyoghoul.util;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

public class ModDamageSources {
    public static final RegistryKey<DamageType> BLOOD_SPEAR = RegistryKey.of(
        RegistryKeys.DAMAGE_TYPE, new Identifier("tokyoghoul", "blood_spear")
    );
    public static final RegistryKey<DamageType> GHOUL_DRAIN = RegistryKey.of(
        RegistryKeys.DAMAGE_TYPE, new Identifier("tokyoghoul", "ghoul_drain")
    );
    public static final RegistryKey<DamageType> KAGUNE = RegistryKey.of(
        RegistryKeys.DAMAGE_TYPE, new Identifier("tokyoghoul", "kagune_damage")
    );

    public static DamageSource of(World world, RegistryKey<DamageType> key) {
        return new DamageSource(world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(key));
    }

    public static void register() {}
}
