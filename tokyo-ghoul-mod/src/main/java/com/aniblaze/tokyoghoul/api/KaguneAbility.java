package com.aniblaze.tokyoghoul.api;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;

public class KaguneAbility {
    private final Identifier id;
    private final String translationKey;
    private final int rcCost;
    private final int cooldownTicks;
    private final int slot;

    public KaguneAbility(Identifier id, String translationKey, int rcCost, int cooldownTicks, int slot) {
        this.id = id;
        this.translationKey = translationKey;
        this.rcCost = rcCost;
        this.cooldownTicks = cooldownTicks;
        this.slot = slot;
    }

    public Identifier getId() { return id; }
    public String getTranslationKey() { return translationKey; }
    public int getRcCost() { return rcCost; }
    public int getCooldownTicks() { return cooldownTicks; }
    public int getSlot() { return slot; }

    public boolean canUse(PlayerEntity player) {
        return true;
    }

    public void use(PlayerEntity player) {
    }
}
