package com.aniblaze.tokyoghoul.init;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.api.KaguneType;
import net.minecraft.registry.Registry;

public class ModKaguneTypes {
    public static final KaguneType RINKAKU = Registry.register(ModRegistries.KAGUNE_TYPE,
        TokyoGhoulMod.id("rinkaku"), new KaguneType(TokyoGhoulMod.id("rinkaku"), 0xFF4444, "kagune.tokyoghoul.rinkaku", false));
    public static final KaguneType UKAKU = Registry.register(ModRegistries.KAGUNE_TYPE,
        TokyoGhoulMod.id("ukaku"), new KaguneType(TokyoGhoulMod.id("ukaku"), 0x4488FF, "kagune.tokyoghoul.ukaku", false));
    public static final KaguneType KOUKAKU = Registry.register(ModRegistries.KAGUNE_TYPE,
        TokyoGhoulMod.id("koukaku"), new KaguneType(TokyoGhoulMod.id("koukaku"), 0xCC44FF, "kagune.tokyoghoul.koukaku", false));
    public static final KaguneType BIKAKU = Registry.register(ModRegistries.KAGUNE_TYPE,
        TokyoGhoulMod.id("bikaku"), new KaguneType(TokyoGhoulMod.id("bikaku"), 0x44FF44, "kagune.tokyoghoul.bikaku", false));
    public static final KaguneType KAKUJA = Registry.register(ModRegistries.KAGUNE_TYPE,
        TokyoGhoulMod.id("kakuja"), new KaguneType(TokyoGhoulMod.id("kakuja"), 0x8800AA, "kagune.tokyoghoul.kakuja", true));

    public static void init() {}
}
