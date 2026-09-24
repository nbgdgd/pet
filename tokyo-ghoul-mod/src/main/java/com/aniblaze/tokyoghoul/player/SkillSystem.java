package com.aniblaze.tokyoghoul.player;

import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import com.aniblaze.tokyoghoul.config.ModConfig;
import net.minecraft.entity.player.PlayerEntity;

public class SkillSystem {
    public enum SkillType {
        STRENGTH("text.tokyoghoul.skill_strength"),
        SPEED("text.tokyoghoul.skill_speed"),
        REGENERATION("text.tokyoghoul.skill_regen"),
        RC_CONTROL("text.tokyoghoul.skill_rc_control"),
        MAX_BLOOD("text.tokyoghoul.skill_max_blood");

        private final String translationKey;
        SkillType(String key) { this.translationKey = key; }
        public String getTranslationKey() { return translationKey; }
    }

    public static final int MAX_LEVEL = 50;

    public static boolean upgradeSkill(PlayerEntity player, SkillType skill) {
        GhoulComponent data = GhoulComponent.get(player);
        if (data.getSkillPoints() <= 0) return false;

        switch (skill) {
            case STRENGTH -> {
                if (data.getStrengthLevel() >= MAX_LEVEL) return false;
                data.setStrengthLevel(data.getStrengthLevel() + 1);
            }
            case SPEED -> {
                if (data.getSpeedLevel() >= MAX_LEVEL) return false;
                data.setSpeedLevel(data.getSpeedLevel() + 1);
            }
            case REGENERATION -> {
                if (data.getRegenLevel() >= MAX_LEVEL) return false;
                data.setRegenLevel(data.getRegenLevel() + 1);
            }
            case RC_CONTROL -> {
                if (data.getRcControlLevel() >= MAX_LEVEL) return false;
                data.setRcControlLevel(data.getRcControlLevel() + 1);
            }
            case MAX_BLOOD -> {
                if (data.getMaxBloodLevel() >= MAX_LEVEL) return false;
                data.setMaxBloodLevel(data.getMaxBloodLevel() + 1);
            }
        }
        data.setSkillPoints(data.getSkillPoints() - 1);
        return true;
    }

    public static double getSkillBonus(int level, double perLevel) {
        return level * perLevel;
    }

    public static double getDamageMultiplier(GhoulComponent data) {
        return 1.0 + getSkillBonus(data.getStrengthLevel(), 0.02);
    }

    public static double getSpeedMultiplier(GhoulComponent data) {
        return 1.0 + getSkillBonus(data.getSpeedLevel(), 0.015);
    }

    public static double getRegenBonus(GhoulComponent data) {
        return getSkillBonus(data.getRegenLevel(), 0.1);
    }

    public static double getRcControlBonus(GhoulComponent data) {
        return getSkillBonus(data.getRcControlLevel(), 0.02);
    }

    public static double getMaxBloodBonus(GhoulComponent data) {
        return ModConfig.MAX_BLOOD + getSkillBonus(data.getMaxBloodLevel(), 50);
    }
}
