package com.aniblaze.tokyoghoul.init;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.api.KaguneAbility;
import net.minecraft.registry.Registry;

public class ModAbilities {

    public static final KaguneAbility RINKAKU_TENTACLE_LASH = register("rinkaku_tentacle_lash",
        "ability.tokyoghoul.tentacle_lash", 30, 30, 0);
    public static final KaguneAbility RINKAKU_GRAB = register("rinkaku_grab",
        "ability.tokyoghoul.kagune_grab", 50, 60, 1);
    public static final KaguneAbility RINKAKU_BERSERK = register("rinkaku_berserk",
        "ability.tokyoghoul.berserk", 80, 200, 2);

    public static final KaguneAbility UKAKU_CRYSTAL_SHOT = register("ukaku_crystal_shot",
        "ability.tokyoghoul.crystal_shot", 25, 15, 0);
    public static final KaguneAbility UKAKU_FEATHER_RAIN = register("ukaku_feather_rain",
        "ability.tokyoghoul.feather_rain", 60, 80, 1);
    public static final KaguneAbility UKAKU_FLASH_STEP = register("ukaku_flash_step",
        "ability.tokyoghoul.flash_step", 40, 40, 2);

    public static final KaguneAbility KOUKAKU_BLADE_SLASH = register("koukaku_blade_slash",
        "ability.tokyoghoul.blade_slash", 35, 25, 0);
    public static final KaguneAbility KOUKAKU_SHIELD_WALL = register("koukaku_shield_wall",
        "ability.tokyoghoul.shield_wall", 45, 120, 1);
    public static final KaguneAbility KOUKAKU_ARMOR_MODE = register("koukaku_armor_mode",
        "ability.tokyoghoul.armor_mode", 70, 300, 2);

    public static final KaguneAbility BIKAKU_TAIL_WHIP = register("bikaku_tail_whip",
        "ability.tokyoghoul.tail_whip", 20, 10, 0);
    public static final KaguneAbility BIKAKU_SWEEP = register("bikaku_sweep",
        "ability.tokyoghoul.sweep", 40, 40, 1);
    public static final KaguneAbility BIKAKU_QUICK_DASH = register("bikaku_quick_dash",
        "ability.tokyoghoul.quick_dash", 30, 30, 2);

    public static final KaguneAbility KAKUJA_CENTIPEDE = register("kakuja_centipede",
        "ability.tokyoghoul.centipede", 100, 100, 0);
    public static final KaguneAbility KAKUJA_BLOOD_TORRENT = register("kakuja_blood_torrent",
        "ability.tokyoghoul.blood_torrent", 150, 200, 1);
    public static final KaguneAbility KAKUJA_DOMINATION = register("kakuja_domination",
        "ability.tokyoghoul.domination", 200, 400, 2);

    private static final KaguneAbility[] EMPTY = new KaguneAbility[0];

    /** Три способности (по слотам 0..2) для типа кагуне. */
    public static KaguneAbility[] forType(String typeId) {
        if (typeId == null) return EMPTY;
        return switch (typeId) {
            case "tokyoghoul:rinkaku" -> new KaguneAbility[]{RINKAKU_TENTACLE_LASH, RINKAKU_GRAB, RINKAKU_BERSERK};
            case "tokyoghoul:ukaku"   -> new KaguneAbility[]{UKAKU_CRYSTAL_SHOT, UKAKU_FEATHER_RAIN, UKAKU_FLASH_STEP};
            case "tokyoghoul:koukaku" -> new KaguneAbility[]{KOUKAKU_BLADE_SLASH, KOUKAKU_SHIELD_WALL, KOUKAKU_ARMOR_MODE};
            case "tokyoghoul:bikaku"  -> new KaguneAbility[]{BIKAKU_TAIL_WHIP, BIKAKU_SWEEP, BIKAKU_QUICK_DASH};
            case "tokyoghoul:kakuja"  -> new KaguneAbility[]{KAKUJA_CENTIPEDE, KAKUJA_BLOOD_TORRENT, KAKUJA_DOMINATION};
            default -> EMPTY;
        };
    }

    private static KaguneAbility register(String id, String translationKey, int rcCost, int cooldownTicks, int slot) {
        return Registry.register(ModRegistries.ABILITY, TokyoGhoulMod.id(id),
            new KaguneAbility(TokyoGhoulMod.id(id), translationKey, rcCost, cooldownTicks, slot));
    }

    public static void init() {}
}
