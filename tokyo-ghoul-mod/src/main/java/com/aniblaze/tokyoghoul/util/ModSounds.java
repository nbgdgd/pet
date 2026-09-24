package com.aniblaze.tokyoghoul.util;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public class ModSounds {
    public static final SoundEvent KAGUNE_ACTIVATE = register("kagune_activate");
    public static final SoundEvent KAGUNE_DEACTIVATE = register("kagune_deactivate");
    public static final SoundEvent BLOOD_SPEAR_THROW = register("blood_spear_throw");
    public static final SoundEvent BLOOD_EXPLOSION = register("blood_explosion");
    public static final SoundEvent GHOUL_ROAR = register("ghoul_roar");
    public static final SoundEvent CONSUME = register("consume");
    public static final SoundEvent KAKUJA_TRANSFORM = register("kakuja_transform");

    private static SoundEvent register(String id) {
        Identifier identifier = TokyoGhoulMod.id(id);
        return Registry.register(Registries.SOUND_EVENT, identifier, SoundEvent.of(identifier));
    }

    public static void register() {
        TokyoGhoulMod.LOGGER.info("Registered Tokyo Ghoul sounds");
    }
}
