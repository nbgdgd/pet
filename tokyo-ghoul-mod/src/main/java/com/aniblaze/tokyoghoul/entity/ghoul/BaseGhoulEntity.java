package com.aniblaze.tokyoghoul.entity.ghoul;

import com.aniblaze.tokyoghoul.util.ModDamageSources;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.*;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

public abstract class BaseGhoulEntity extends HostileEntity implements GeoEntity {
    protected int kaguneType;
    protected int kaguneStage;
    protected boolean kakuganActive;
    protected int attackCooldown = 0;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    protected static final RawAnimation IDLE_ANIM = RawAnimation.begin().then("animation.ghoul.idle", Animation.LoopType.LOOP);
    protected static final RawAnimation WALK_ANIM = RawAnimation.begin().then("animation.ghoul.walk", Animation.LoopType.LOOP);
    protected static final RawAnimation ATTACK_ANIM = RawAnimation.begin().then("animation.ghoul.attack", Animation.LoopType.PLAY_ONCE);
    protected static final RawAnimation KAGUNE_ACTIVATE_ANIM = RawAnimation.begin().then("animation.ghoul.kagune_activate", Animation.LoopType.PLAY_ONCE);
    protected static final RawAnimation CONSUME_ANIM = RawAnimation.begin().then("animation.ghoul.consume", Animation.LoopType.PLAY_ONCE);

    protected BaseGhoulEntity(EntityType<? extends HostileEntity> entityType, World world) {
        super(entityType, world);
        this.kaguneType = random.nextInt(4);
        this.kaguneStage = 1 + random.nextInt(3);
    }

    @Override
    protected void initGoals() {
        goalSelector.add(1, new SwimGoal(this));
        goalSelector.add(2, new MeleeAttackGoal(this, 1.2, false));
        goalSelector.add(3, new WanderAroundFarGoal(this, 0.8));
        goalSelector.add(4, new LookAtEntityGoal(this, PlayerEntity.class, 16f));
        goalSelector.add(5, new LookAroundGoal(this));

        targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
        targetSelector.add(2, new ActiveTargetGoal<>(this, LivingEntity.class, true,
            e -> !(e instanceof BaseGhoulEntity)));
    }

    public static DefaultAttributeContainer.Builder createGhoulAttributes() {
        return MobEntity.createMobAttributes()
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 60.0)
            .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 8.0)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
            .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0)
            .add(EntityAttributes.GENERIC_ARMOR, 4.0)
            .add(EntityAttributes.GENERIC_ATTACK_SPEED, 1.5);
    }

    public int getKaguneType() { return kaguneType; }
    public int getKaguneStage() { return kaguneStage; }
    public boolean isKakuganActive() { return kakuganActive; }

    public void setKakuganActive(boolean active) {
        this.kakuganActive = active;
        if (active) {
            getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED)
                .setBaseValue(0.36);
            getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                .setBaseValue(9.6);
        } else {
            getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED)
                .setBaseValue(0.3);
            getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                .setBaseValue(8.0);
        }
    }

    @Override
    public boolean tryAttack(Entity target) {
        boolean attacked = super.tryAttack(target);
        if (attacked && target instanceof LivingEntity living) {
            living.damage(ModDamageSources.of(getWorld(), ModDamageSources.KAGUNE),
                (float) (getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE) * 0.3));
        }
        return attacked;
    }

    @Override
    public void tick() {
        super.tick();
        if (attackCooldown > 0) attackCooldown--;

        if (!getWorld().isClient && isAlive() && age % 100 == 0) {
            heal(0.5f * kaguneStage);
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "idle", 5, this::idleAnimController));
        controllers.add(new AnimationController<>(this, "attack", 2, this::attackAnimController));
    }

    protected <E extends GeoAnimatable> PlayState idleAnimController(AnimationState<E> state) {
        if (state.isMoving()) {
            state.setAnimation(WALK_ANIM);
        } else {
            state.setAnimation(IDLE_ANIM);
        }
        return PlayState.CONTINUE;
    }

    protected <E extends GeoAnimatable> PlayState attackAnimController(AnimationState<E> state) {
        if (this.handSwinging) {
            state.setAnimation(ATTACK_ANIM);
            return PlayState.CONTINUE;
        }
        state.getController().forceAnimationReset();
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        super.onDeath(damageSource);
    }
}
