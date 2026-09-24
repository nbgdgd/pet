package com.aniblaze.tokyoghoul.entity.boss;

import com.aniblaze.tokyoghoul.entity.ModEntities;
import com.aniblaze.tokyoghoul.entity.ghoul.BaseGhoulEntity;
import com.aniblaze.tokyoghoul.entity.abilities.BloodSpearEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.world.World;

public class OneEyedGhoulEntity extends BaseGhoulEntity {
    private int rangedAttackCooldown = 0;

    public OneEyedGhoulEntity(EntityType<? extends HostileEntity> entityType, World world) {
        super(entityType, world);
        this.kaguneStage = 4;
        this.experiencePoints = 150;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return BaseGhoulEntity.createGhoulAttributes()
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 300.0)
            .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 20.0)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.4)
            .add(EntityAttributes.GENERIC_ARMOR, 8.0)
            .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
    }

    @Override
    public void tick() {
        super.tick();
        if (rangedAttackCooldown > 0) rangedAttackCooldown--;

        if (!getWorld().isClient && isAlive() && rangedAttackCooldown == 0 && getTarget() != null) {
            shootBloodSpear();
            rangedAttackCooldown = 80;
        }
    }

    private void shootBloodSpear() {
        LivingEntity target = getTarget();
        if (target == null) return;

        BloodSpearEntity spear = new BloodSpearEntity(getWorld(), this);
        spear.setDamage(15.0f);
        double dx = target.getX() - getX();
        double dy = target.getEyeY() - getEyeY();
        double dz = target.getZ() - getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        spear.setVelocity(dx / dist * 2.5, dy / dist * 2.5, dz / dist * 2.5);
        getWorld().spawnEntity(spear);
    }

    @Override
    public void onDeath(net.minecraft.entity.damage.DamageSource source) {
        if (!getWorld().isClient && source.getAttacker() instanceof net.minecraft.entity.player.PlayerEntity player) {
            player.sendMessage(net.minecraft.text.Text.literal("§cThe One-Eyed Ghoul has fallen!"), false);
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                com.aniblaze.tokyoghoul.util.AdvancementHelper.grant(sp, "boss_ghoul", "kill_boss");
            }
        }
        super.onDeath(source);
    }
}
