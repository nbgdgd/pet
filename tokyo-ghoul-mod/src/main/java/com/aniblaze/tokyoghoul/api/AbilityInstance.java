package com.aniblaze.tokyoghoul.api;

public class AbilityInstance {
    private final KaguneAbility ability;
    private int cooldown;

    public AbilityInstance(KaguneAbility ability) {
        this.ability = ability;
        this.cooldown = 0;
    }

    public KaguneAbility getAbility() { return ability; }
    public int getCooldown() { return cooldown; }
    public void setCooldown(int cooldown) { this.cooldown = cooldown; }
    public boolean isOnCooldown() { return cooldown > 0; }

    public void tick() {
        if (cooldown > 0) cooldown--;
    }

    public void startCooldown() {
        this.cooldown = ability.getCooldownTicks();
    }
}
