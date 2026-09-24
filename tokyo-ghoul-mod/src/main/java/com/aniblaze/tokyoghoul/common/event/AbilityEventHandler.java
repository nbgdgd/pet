package com.aniblaze.tokyoghoul.common.event;

import com.aniblaze.tokyoghoul.api.AbilityInstance;
import com.aniblaze.tokyoghoul.api.KaguneAbility;
import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import com.aniblaze.tokyoghoul.config.ModConfig;
import com.aniblaze.tokyoghoul.entity.abilities.BloodSpearEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class AbilityEventHandler {

    public static void executeAbility(ServerPlayerEntity player, int slot) {
        GhoulComponent data = GhoulComponent.get(player);
        if (!data.isGhoul()) return;

        List<AbilityInstance> abilities = data.getAbilities();
        if (slot < 0 || slot >= abilities.size()) return;

        AbilityInstance inst = abilities.get(slot);
        KaguneAbility ability = inst.getAbility();

        if (inst.isOnCooldown()) return;
        if (data.getBlood() < ability.getRcCost()) return;

        data.setBlood(data.getBlood() - ability.getRcCost());
        inst.startCooldown();

        String id = ability.getId().toString();
        switch (id) {
            case "tokyoghoul:rinkaku_tentacle_lash" -> tentacleLash(player, data);
            case "tokyoghoul:rinkaku_grab" -> kaguneGrab(player, data);
            case "tokyoghoul:rinkaku_berserk" -> berserk(player, data);
            case "tokyoghoul:ukaku_crystal_shot" -> crystalShot(player, data);
            case "tokyoghoul:ukaku_feather_rain" -> featherRain(player, data);
            case "tokyoghoul:ukaku_flash_step" -> flashStep(player, data);
            case "tokyoghoul:koukaku_blade_slash" -> bladeSlash(player, data);
            case "tokyoghoul:koukaku_shield_wall" -> shieldWall(player, data);
            case "tokyoghoul:koukaku_armor_mode" -> armorMode(player, data);
            case "tokyoghoul:bikaku_tail_whip" -> tailWhip(player, data);
            case "tokyoghoul:bikaku_sweep" -> sweep(player, data);
            case "tokyoghoul:bikaku_quick_dash" -> quickDash(player, data);
            case "tokyoghoul:kakuja_centipede" -> centipede(player, data);
            case "tokyoghoul:kakuja_blood_torrent" -> bloodTorrent(player, data);
            case "tokyoghoul:kakuja_domination" -> domination(player, data);
        }

        data.sync();
    }

    private static void tentacleLash(ServerPlayerEntity player, GhoulComponent data) {
        double reach = 6.0 + data.getStrengthLevel() * 0.1;
        float damage = 6.0f + data.getStrengthLevel() * 0.2f;

        Vec3d look = player.getRotationVec(1.0f);
        Box box = player.getBoundingBox().expand(look.x * reach, look.y * reach, look.z * reach).expand(1.5);
        List<LivingEntity> targets = player.getWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e != player && e.isAlive() && e.distanceTo(player) <= reach);

        for (LivingEntity target : targets) {
            target.damage(player.getDamageSources().playerAttack(player), damage);
            target.takeKnockback(0.8, look.x, look.z);
        }

        player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
            SoundCategory.PLAYERS, 1.0f, 0.5f);
    }

    private static void kaguneGrab(ServerPlayerEntity player, GhoulComponent data) {
        Vec3d look = player.getRotationVec(1.0f);
        double reach = 8.0;
        Box box = player.getBoundingBox().expand(look.x * reach, look.y * reach, look.z * reach).expand(2);
        List<LivingEntity> targets = player.getWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e != player && e.isAlive() && e.distanceTo(player) <= reach);

        for (LivingEntity target : targets) {
            target.damage(player.getDamageSources().playerAttack(player), 10.0f);
            target.setVelocity(look.multiply(-0.3));
            target.velocityModified = true;
            break;
        }
    }

    private static void berserk(ServerPlayerEntity player, GhoulComponent data) {
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
            net.minecraft.entity.effect.StatusEffects.STRENGTH, 120, 1, false, false, true));
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
            net.minecraft.entity.effect.StatusEffects.SPEED, 120, 0, false, false, true));
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
            net.minecraft.entity.effect.StatusEffects.RESISTANCE, 120, 0, false, false, true));
        player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR,
            SoundCategory.PLAYERS, 1.0f, 0.7f);
    }

    private static void crystalShot(ServerPlayerEntity player, GhoulComponent data) {
        BloodSpearEntity spear = new BloodSpearEntity(player.getWorld(), player);
        spear.setVelocity(player, player.getPitch(), player.getYaw(), 0, 2.5f, 0.5f);
        player.getWorld().spawnEntity(spear);
        player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_LLAMA_SPIT,
            SoundCategory.PLAYERS, 1.0f, 1.5f);
    }

    private static void featherRain(ServerPlayerEntity player, GhoulComponent data) {
        Vec3d pos = player.getPos();
        double radius = 4.0 + data.getStrengthLevel() * 0.05;
        float damage = 4.0f + data.getStrengthLevel() * 0.15f;

        Box box = new Box(pos.x - radius, pos.y - 2, pos.z - radius, pos.x + radius, pos.y + 2, pos.z + radius);
        List<LivingEntity> targets = player.getWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e != player && e.isAlive());

        for (LivingEntity target : targets) {
            target.damage(player.getDamageSources().playerAttack(player), damage);
        }
    }

    private static void flashStep(ServerPlayerEntity player, GhoulComponent data) {
        Vec3d look = player.getRotationVec(1.0f);
        double distance = 6.0 + data.getSpeedLevel() * 0.1;
        Vec3d newPos = player.getPos().add(look.x * distance, 0, look.z * distance);

        player.teleport(newPos.x, newPos.y, newPos.z, false);
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
            net.minecraft.entity.effect.StatusEffects.SPEED, 40, 2, false, false, true));
    }

    private static void bladeSlash(ServerPlayerEntity player, GhoulComponent data) {
        float baseDamage = 8.0f + data.getStrengthLevel() * 0.25f;
        Vec3d look = player.getRotationVec(1.0f);
        Box box = player.getBoundingBox().expand(look.x * 4, 1, look.z * 4).expand(1.5);
        List<LivingEntity> targets = player.getWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e != player && e.isAlive() && e.distanceTo(player) <= 4);

        for (LivingEntity target : targets) {
            target.damage(player.getDamageSources().playerAttack(player), baseDamage);
            target.takeKnockback(0.5, look.x, look.z);
        }

        player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
            SoundCategory.PLAYERS, 1.0f, 0.8f);
    }

    private static void shieldWall(ServerPlayerEntity player, GhoulComponent data) {
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
            net.minecraft.entity.effect.StatusEffects.RESISTANCE, 100, 2, false, false, true));
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
            net.minecraft.entity.effect.StatusEffects.ABSORPTION, 100, 1, false, false, true));
    }

    private static void armorMode(ServerPlayerEntity player, GhoulComponent data) {
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
            net.minecraft.entity.effect.StatusEffects.RESISTANCE, 200, 3, false, false, true));
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
            net.minecraft.entity.effect.StatusEffects.ABSORPTION, 200, 3, false, false, true));
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
            net.minecraft.entity.effect.StatusEffects.SLOWNESS, 200, 0, false, false, true));
    }

    private static void tailWhip(ServerPlayerEntity player, GhoulComponent data) {
        Vec3d look = player.getRotationVec(1.0f);
        Box box = player.getBoundingBox().expand(look.x * 3, 1, look.z * 3).expand(1.5);
        List<LivingEntity> targets = player.getWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e != player && e.isAlive() && e.distanceTo(player) <= 3);

        for (LivingEntity target : targets) {
            target.damage(player.getDamageSources().playerAttack(player), 5.0f);
            target.takeKnockback(1.2, look.x, look.z);
        }
    }

    private static void sweep(ServerPlayerEntity player, GhoulComponent data) {
        Vec3d pos = player.getPos();
        double radius = 5.0;
        float damage = 7.0f + data.getStrengthLevel() * 0.2f;

        Box box = new Box(pos.x - radius, pos.y - 1, pos.z - radius, pos.x + radius, pos.y + 1, pos.z + radius);
        List<LivingEntity> targets = player.getWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e != player && e.isAlive());

        for (LivingEntity target : targets) {
            Vec3d away = target.getPos().subtract(pos).normalize();
            target.damage(player.getDamageSources().playerAttack(player), damage);
            target.setVelocity(away.multiply(0.8));
            target.velocityModified = true;
        }
    }

    private static void quickDash(ServerPlayerEntity player, GhoulComponent data) {
        Vec3d look = player.getRotationVec(1.0f);
        player.setVelocity(look.x * 2.5, 0.3, look.z * 2.5);
        player.velocityModified = true;
        player.velocityDirty = true;
    }

    private static void centipede(ServerPlayerEntity player, GhoulComponent data) {
        Vec3d pos = player.getPos();
        double radius = 8.0;
        float damage = 15.0f + data.getStrengthLevel() * 0.3f;

        Box box = new Box(pos.x - radius, pos.y - 3, pos.z - radius, pos.x + radius, pos.y + 3, pos.z + radius);
        List<LivingEntity> targets = player.getWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e != player && e.isAlive());

        for (LivingEntity target : targets) {
            Vec3d pull = player.getPos().subtract(target.getPos()).normalize();
            target.damage(player.getDamageSources().playerAttack(player), damage);
            target.setVelocity(pull.multiply(1.5));
            target.velocityModified = true;
        }

        player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_ROAR,
            SoundCategory.PLAYERS, 1.0f, 0.3f);
    }

    private static void bloodTorrent(ServerPlayerEntity player, GhoulComponent data) {
        Vec3d pos = player.getPos();
        double radius = 10.0;
        float damage = 12.0f + data.getStrengthLevel() * 0.25f;

        Box box = new Box(pos.x - radius, pos.y - 4, pos.z - radius, pos.x + radius, pos.y + 4, pos.z + radius);
        List<LivingEntity> targets = player.getWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e != player && e.isAlive());

        for (LivingEntity target : targets) {
            target.damage(player.getDamageSources().magic(), damage);
            target.setFireTicks(target.getFireTicks() + 60);
        }

        player.heal(10.0f);
    }

    private static void domination(ServerPlayerEntity player, GhoulComponent data) {
        Vec3d pos = player.getPos();
        double radius = 6.0;
        float damage = 25.0f + data.getStrengthLevel() * 0.5f;

        Box box = new Box(pos.x - radius, pos.y - 2, pos.z - radius, pos.x + radius, pos.y + 2, pos.z + radius);
        List<LivingEntity> targets = player.getWorld().getEntitiesByClass(LivingEntity.class, box,
            e -> e != player && e.isAlive());

        for (LivingEntity target : targets) {
            target.damage(player.getDamageSources().playerAttack(player), damage);
            target.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.WEAKNESS, 100, 2, false, false, true));
            target.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.SLOWNESS, 100, 2, false, false, true));
        }
    }
}
