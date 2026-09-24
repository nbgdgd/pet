package com.aniblaze.tokyoghoul.common.component;

import com.aniblaze.tokyoghoul.api.AbilityInstance;
import com.aniblaze.tokyoghoul.api.KaguneAbility;
import com.aniblaze.tokyoghoul.api.KaguneType;
import com.aniblaze.tokyoghoul.config.ModConfig;
import com.aniblaze.tokyoghoul.init.ModAbilities;
import com.aniblaze.tokyoghoul.init.ModComponents;
import dev.onyxstudios.cca.api.v3.component.sync.AutoSyncedComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

public class GhoulComponent implements AutoSyncedComponent {
    private final PlayerEntity player;
    private boolean isGhoul = false;
    private double rc = 0;
    private double blood = 0;
    private int ghoulLevel = 1;
    private int skillPoints = 0;
    private String kaguneTypeId = "";
    private int kaguneStage = 1;
    private boolean kakuganActive = false;
    private boolean predatorInstinctActive = false;
    private boolean kakujaActive = false;
    private double hunger = 100;
    private int madness = 0;
    private int rank = 0;
    private int strengthLevel = 0;
    private int speedLevel = 0;
    private int regenLevel = 0;
    private int rcControlLevel = 0;
    private int maxBloodLevel = 0;
    private int selectedSlot = 0;
    private final List<AbilityInstance> abilities = new ArrayList<>();
    private long lastTick = 0;

    public GhoulComponent(PlayerEntity player) {
        this.player = player;
    }

    public static GhoulComponent get(PlayerEntity player) {
        return ModComponents.GHOUL.get(player);
    }

    public void sync() {
        ModComponents.GHOUL.sync(this.player);
    }

    public boolean isGhoul() { return isGhoul; }
    public void setGhoul(boolean ghoul) { this.isGhoul = ghoul; sync(); }

    public double getRc() { return rc; }
    public void setRc(double rc) {
        double clamped = MathHelper.clamp(rc, 0, ModConfig.MAX_RC);
        if (clamped != this.rc) { this.rc = clamped; sync(); }
    }

    public double getBlood() { return blood; }
    public void setBlood(double blood) {
        double clamped = MathHelper.clamp(blood, 0, getMaxBlood());
        if (clamped != this.blood) { this.blood = clamped; sync(); }
    }

    public int getGhoulLevel() { return ghoulLevel; }
    public int getSkillPoints() { return skillPoints; }

    public String getKaguneTypeId() { return kaguneTypeId; }
    public void setKaguneTypeId(String id) {
        this.kaguneTypeId = id;
        rebuildAbilities();
        sync();
    }

    /** Пересоздаёт список способностей под текущий тип кагуне. */
    private void rebuildAbilities() {
        abilities.clear();
        for (KaguneAbility ab : ModAbilities.forType(kaguneTypeId)) {
            abilities.add(new AbilityInstance(ab));
        }
    }

    public int getKaguneStage() { return kaguneStage; }

    public boolean isKakuganActive() { return kakuganActive; }
    public void setKakuganActive(boolean v) { this.kakuganActive = v; sync(); }

    public boolean isPredatorInstinctActive() { return predatorInstinctActive; }
    public void setPredatorInstinctActive(boolean v) { this.predatorInstinctActive = v; sync(); }

    public boolean isKakujaActive() { return kakujaActive; }
    public void setKakujaActive(boolean v) { this.kakujaActive = v; sync(); }

    public double getHunger() { return hunger; }
    public int getMadness() { return madness; }
    public int getRank() { return rank; }

    public int getStrengthLevel() { return strengthLevel; }
    public int getSpeedLevel() { return speedLevel; }
    public int getRegenLevel() { return regenLevel; }
    public int getRcControlLevel() { return rcControlLevel; }
    public int getMaxBloodLevel() { return maxBloodLevel; }

    public void setGhoulLevel(int v) { this.ghoulLevel = v; }
    public void setSkillPoints(int v) { this.skillPoints = Math.max(0, v); }
    public void setKaguneStage(int v) { this.kaguneStage = v; }
    public void setHunger(double v) { this.hunger = MathHelper.clamp(v, 0, 100); }
    public void setMadness(int v) { this.madness = MathHelper.clamp(v, 0, 100); }
    public void setRank(int v) { this.rank = v; }
    public void setStrengthLevel(int v) { this.strengthLevel = v; }
    public void setSpeedLevel(int v) { this.speedLevel = v; }
    public void setRegenLevel(int v) { this.regenLevel = v; }
    public void setRcControlLevel(int v) { this.rcControlLevel = v; }
    public void setMaxBloodLevel(int v) { this.maxBloodLevel = v; }

    public void setKakuganActiveRaw(boolean v) { this.kakuganActive = v; }
    public void setPredatorInstinctActiveRaw(boolean v) { this.predatorInstinctActive = v; }
    public void setKakujaActiveRaw(boolean v) { this.kakujaActive = v; }
    public void setRcRaw(double v) { this.rc = v; }
    public void setBloodRaw(double v) { this.blood = v; }
    public void setHungerRaw(double v) { this.hunger = v; }
    public void setMadnessRaw(int v) { this.madness = v; }
    public void setGhoulLevelRaw(int v) { this.ghoulLevel = v; }
    public void setRankRaw(int v) { this.rank = v; }
    public void setKaguneStageRaw(int v) { this.kaguneStage = v; }
    public void setSkillPointsRaw(int v) { this.skillPoints = v; }

    public int getSelectedSlot() { return selectedSlot; }
    public void setSelectedSlot(int slot) { this.selectedSlot = slot; sync(); }

    public List<AbilityInstance> getAbilities() { return abilities; }

    public double getMaxBlood() {
        return ModConfig.MAX_BLOOD + (maxBloodLevel * 50);
    }

    public void addRc(double amount) {
        setRc(rc + amount);
        recalculateLevel();
    }

    public void addBlood(double amount) {
        setBlood(blood + amount);
    }

    public void tick() {
        if (!isGhoul || player.getWorld().isClient) return;

        long now = player.getWorld().getTime();
        if (now == lastTick) return;
        lastTick = now;

        boolean dirty = false;

        for (AbilityInstance inst : abilities) {
            if (inst.isOnCooldown()) { inst.tick(); dirty = true; }
        }

        hunger -= 0.01 * (1 + rcControlLevel * 0.05);

        if (hunger < 10) {
            madness = Math.min(100, madness + 1);
            if (hunger <= 0) {
                player.damage(player.getDamageSources().starve(), 1.0f);
            }
            dirty = true;
        } else if (madness > 0) {
            madness = Math.max(0, madness - 1);
            dirty = true;
        }

        if (madness > 50 && player.getRandom().nextFloat() < 0.02f) {
            int amp = madness > 80 ? 1 : 0;
            player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.STRENGTH, 60, amp, false, false));
            if (madness > 70 && player.getRandom().nextFloat() < 0.5f) {
                player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.NAUSEA, 80, 0, false, false));
            }
        }

        if (kakuganActive) {
            if (player.age % 100 == 0) {
                addBlood(2 + rcControlLevel);
            }
        }

        if (player.age % 20 == 0) {
            if (kaguneStage >= 3 && player.getHealth() < player.getMaxHealth()) {
                float regenAmount = 0.5f + regenLevel * 0.1f;
                player.heal(regenAmount);
            }
        }

        if (dirty) sync();
    }

    private void recalculateLevel() {
        int newLevel = (int)(rc / 1000) + 1;
        if (newLevel > ghoulLevel) {
            skillPoints += (newLevel - ghoulLevel);
        }
        ghoulLevel = Math.min(newLevel, 100);
        recalculateKaguneStage();
        recalculateRank();
    }

    private void recalculateKaguneStage() {
        if (rc >= ModConfig.KAKUJA_RC) kaguneStage = 5;
        else if (rc >= ModConfig.STAGE_4_RC) kaguneStage = 4;
        else if (rc >= ModConfig.STAGE_3_RC) kaguneStage = 3;
        else if (rc >= ModConfig.STAGE_2_RC) kaguneStage = 2;
        else kaguneStage = 1;
    }

    private void recalculateRank() {
        if (rc >= 500000) rank = 5;
        else if (rc >= 250000) rank = 4;
        else if (rc >= 100000) rank = 3;
        else if (rc >= 50000) rank = 2;
        else if (rc >= 10000) rank = 1;
        else rank = 0;
    }

    @Override
    public void readFromNbt(NbtCompound tag) {
        isGhoul = tag.getBoolean("IsGhoul");
        rc = tag.getDouble("RC");
        blood = tag.getDouble("Blood");
        ghoulLevel = tag.getInt("GhoulLevel");
        skillPoints = tag.getInt("SkillPoints");
        kaguneTypeId = tag.getString("KaguneType");
        kaguneStage = tag.getInt("KaguneStage");
        kakuganActive = tag.getBoolean("KakuganActive");
        predatorInstinctActive = tag.getBoolean("PredatorInstinctActive");
        kakujaActive = tag.getBoolean("KakujaActive");
        hunger = tag.getDouble("Hunger");
        madness = tag.getInt("Madness");
        rank = tag.getInt("Rank");
        strengthLevel = tag.getInt("StrengthLevel");
        speedLevel = tag.getInt("SpeedLevel");
        regenLevel = tag.getInt("RegenLevel");
        rcControlLevel = tag.getInt("RcControlLevel");
        maxBloodLevel = tag.getInt("MaxBloodLevel");
        selectedSlot = tag.getInt("SelectedSlot");

        // Пересобрать способности под тип и восстановить кулдауны (синхр. на клиент).
        rebuildAbilities();
        NbtList cd = tag.getList("AbilityCooldowns", NbtElement.INT_TYPE);
        for (int i = 0; i < abilities.size() && i < cd.size(); i++) {
            abilities.get(i).setCooldown(cd.getInt(i));
        }
    }

    @Override
    public void writeToNbt(NbtCompound tag) {
        tag.putBoolean("IsGhoul", isGhoul);
        tag.putDouble("RC", rc);
        tag.putDouble("Blood", blood);
        tag.putInt("GhoulLevel", ghoulLevel);
        tag.putInt("SkillPoints", skillPoints);
        tag.putString("KaguneType", kaguneTypeId);
        tag.putInt("KaguneStage", kaguneStage);
        tag.putBoolean("KakuganActive", kakuganActive);
        tag.putBoolean("PredatorInstinctActive", predatorInstinctActive);
        tag.putBoolean("KakujaActive", kakujaActive);
        tag.putDouble("Hunger", hunger);
        tag.putInt("Madness", madness);
        tag.putInt("Rank", rank);
        tag.putInt("StrengthLevel", strengthLevel);
        tag.putInt("SpeedLevel", speedLevel);
        tag.putInt("RegenLevel", regenLevel);
        tag.putInt("RcControlLevel", rcControlLevel);
        tag.putInt("MaxBloodLevel", maxBloodLevel);
        tag.putInt("SelectedSlot", selectedSlot);

        NbtList cd = new NbtList();
        for (AbilityInstance inst : abilities) {
            cd.add(NbtInt.of(inst.getCooldown()));
        }
        tag.put("AbilityCooldowns", cd);
    }
}
