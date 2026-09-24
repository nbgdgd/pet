package com.aniblaze.tokyoghoul.entity.boss;

import com.aniblaze.tokyoghoul.entity.ModEntities;
import com.aniblaze.tokyoghoul.entity.ghoul.BaseGhoulEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;

public class RoamingGhoulEntity extends BaseGhoulEntity {
    private int specialAttackCooldown = 0;

    public RoamingGhoulEntity(EntityType<? extends HostileEntity> entityType, World world) {
        super(entityType, world);
        this.kaguneStage = 4;
        this.experiencePoints = 100;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return BaseGhoulEntity.createGhoulAttributes()
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 200.0)
            .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 18.0)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.35)
            .add(EntityAttributes.GENERIC_ARMOR, 10.0)
            .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
    }

    @Override
    public void tick() {
        super.tick();
        if (specialAttackCooldown > 0) specialAttackCooldown--;

        if (!getWorld().isClient && isAlive() && specialAttackCooldown == 0 && getTarget() != null) {
            performSpecialAttack();
            specialAttackCooldown = 100;
        }

        if (getWorld().isClient) {
            getWorld().addParticle(ParticleTypes.CRIMSON_SPORE,
                getX(), getY() + 2, getZ(), 0, 0, 0);
        }
    }

    private void performSpecialAttack() {
        LivingEntity target = getTarget();
        if (target == null) return;

        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1) return;

        setVelocity(dx / dist * 2.0, 0.4, dz / dist * 2.0);
        velocityModified = true;

        var nearby = getWorld().getEntitiesByClass(LivingEntity.class,
            getBoundingBox().expand(4), e -> e != this && e.isAlive());
        for (LivingEntity e : nearby) {
            e.damage(getDamageSources().mobAttack(this), 12.0f);
        }
    }

    @Override
    public void onDeath(DamageSource source) {
        if (!getWorld().isClient && source.getAttacker() instanceof PlayerEntity player) {
            player.sendMessage(Text.literal("§5The SSS rank ghoul has fallen!"), false);
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                com.aniblaze.tokyoghoul.util.AdvancementHelper.grant(sp, "boss_ghoul", "kill_boss");
            }
        }
        super.onDeath(source);
    }
}
