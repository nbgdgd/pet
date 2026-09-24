package com.aniblaze.tokyoghoul.entity.abilities;

import com.aniblaze.tokyoghoul.entity.ModEntities;
import com.aniblaze.tokyoghoul.util.ModDamageSources;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;

public class BloodSpearEntity extends ProjectileEntity {
    private static final TrackedData<Integer> OWNER_ID = DataTracker.registerData(
        BloodSpearEntity.class, TrackedDataHandlerRegistry.INTEGER
    );

    private int pierceCount = 3;
    private float damage = 10.0f;

    public BloodSpearEntity(EntityType<? extends ProjectileEntity> type, World world) {
        super(type, world);
    }

    public BloodSpearEntity(World world, LivingEntity owner) {
        super(ModEntities.BLOOD_SPEAR, world);
        setOwner(owner);
        setPosition(owner.getX(), owner.getEyeY() - 0.1, owner.getZ());
    }

    @Override
    protected void initDataTracker() {
        dataTracker.startTracking(OWNER_ID, 0);
    }

    public void setDamage(float damage) {
        this.damage = damage;
    }

    @Override
    public void tick() {
        super.tick();
        if (!getWorld().isClient) {
            HitResult hitResult = ProjectileUtil.getCollision(this, this::canHit);
            if (hitResult.getType() != HitResult.Type.MISS) {
                onCollision(hitResult);
            }
        }

        if (getWorld().isClient) {
            for (int i = 0; i < 3; i++) {
                getWorld().addParticle(
                    ParticleTypes.CRIMSON_SPORE,
                    getX() + (random.nextDouble() - 0.5) * 0.3,
                    getY() + (random.nextDouble() - 0.5) * 0.3,
                    getZ() + (random.nextDouble() - 0.5) * 0.3,
                    0, 0, 0
                );
            }
        }

        if (age > 100) {
            discard();
        }
    }

    @Override
    protected void onEntityHit(EntityHitResult entityHitResult) {
        Entity target = entityHitResult.getEntity();
        if (target == getOwner()) return;

        if (target instanceof LivingEntity living) {
            Entity owner = getOwner();
            var damageSource = owner != null
                ? ModDamageSources.of(getWorld(), ModDamageSources.BLOOD_SPEAR)
                : getWorld().getDamageSources().magic();

            living.damage(damageSource, damage);
            living.setVelocity(
                living.getVelocity().add(
                    getVelocity().normalize().multiply(0.5)
                )
            );
            living.velocityModified = true;

            pierceCount--;
            if (pierceCount <= 0) {
                discard();
            }
        }
    }

    @Override
    protected void onCollision(HitResult hitResult) {
        super.onCollision(hitResult);
        if (!getWorld().isClient) {
            discard();
        }
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        pierceCount = nbt.getInt("PierceCount");
        damage = nbt.getFloat("Damage");
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("PierceCount", pierceCount);
        nbt.putFloat("Damage", damage);
    }

    @Override
    public boolean hasNoGravity() {
        return true;
    }

    @Override
    public boolean shouldRender(double cameraX, double cameraY, double cameraZ) {
        return true;
    }
}
