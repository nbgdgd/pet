package com.aniblaze.tokyoghoul.entity.boss;

import com.aniblaze.tokyoghoul.entity.ModEntities;
import com.aniblaze.tokyoghoul.entity.ghoul.BaseGhoulEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.world.World;

public class KakujaMutantEntity extends BaseGhoulEntity {
    private int rageModeTimer = 0;
    private boolean rageMode = false;
    private int groundSlamCooldown = 0;

    public KakujaMutantEntity(EntityType<? extends HostileEntity> entityType, World world) {
        super(entityType, world);
        this.kaguneStage = 5;
        this.experiencePoints = 500;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return BaseGhoulEntity.createGhoulAttributes()
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 500.0)
            .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 30.0)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
            .add(EntityAttributes.GENERIC_ARMOR, 15.0)
            .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
            .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    public void tick() {
        super.tick();

        if (!getWorld().isClient && isAlive()) {
            if (getHealth() < getMaxHealth() * 0.3 && !rageMode) {
                enterRageMode();
            }

            if (rageMode) {
                rageModeTimer--;
                if (rageModeTimer <= 0) {
                    exitRageMode();
                }
            }

            if (groundSlamCooldown > 0) groundSlamCooldown--;

            if (groundSlamCooldown == 0 && getTarget() != null) {
                performGroundSlam();
                groundSlamCooldown = rageMode ? 60 : 120;
            }
        }

        if (getWorld().isClient) {
            for (int i = 0; i < 3; i++) {
                getWorld().addParticle(
                    rageMode ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.CRIMSON_SPORE,
                    getX() + (random.nextDouble() - 0.5) * 3,
                    getY() + random.nextDouble() * 3,
                    getZ() + (random.nextDouble() - 0.5) * 3,
                    0, 0, 0
                );
            }
        }
    }

    private void enterRageMode() {
        rageMode = true;
        rageModeTimer = 400;
        getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.35);
        getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(40.0);
        getAttributeInstance(EntityAttributes.GENERIC_ARMOR).setBaseValue(20.0);
    }

    private void exitRageMode() {
        rageMode = false;
        getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.25);
        getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE).setBaseValue(30.0);
        getAttributeInstance(EntityAttributes.GENERIC_ARMOR).setBaseValue(15.0);
    }

    private void performGroundSlam() {
        getWorld().getEntitiesByClass(LivingEntity.class,
            getBoundingBox().expand(6), e -> e != this && e.isAlive()
        ).forEach(e -> {
            float dmg = rageMode ? 25.0f : 15.0f;
            e.damage(getDamageSources().mobAttack(this), dmg);
            e.setVelocity(
                e.getX() - getX(),
                0.5,
                e.getZ() - getZ()
            );
            e.velocityModified = true;
        });
    }

    @Override
    public void onDeath(DamageSource source) {
        if (!getWorld().isClient && source.getAttacker() instanceof PlayerEntity player) {
            player.sendMessage(Text.literal("§4The Kakuja Mutant has been defeated!"), false);
            if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                com.aniblaze.tokyoghoul.util.AdvancementHelper.grant(sp, "boss_ghoul", "kill_boss");
            }
        }
        super.onDeath(source);
    }
}
