package com.aniblaze.tokyoghoul.mixins;

import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import com.aniblaze.tokyoghoul.util.TokyoGhoulEventHandler;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin extends LivingEntity {
    @Unique
    private boolean hasCheckedGhoul = false;

    private PlayerEntityMixin(EntityType<? extends LivingEntity> type, World world) {
        super(type, world);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (!hasCheckedGhoul && !getWorld().isClient) {
            hasCheckedGhoul = true;
            TokyoGhoulEventHandler.tryBecomeGhoul((PlayerEntity) (Object) this);
        }
    }

    @Redirect(method = "eatFood", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/entity/player/HungerManager;eat(Lnet/minecraft/item/Item;Lnet/minecraft/item/ItemStack;)V"))
    private void redirectHungerEat(HungerManager manager, Item item, ItemStack stack) {
        GhoulComponent data = GhoulComponent.get((PlayerEntity) (Object) this);
        if (data.isGhoul() && item.isFood()) {
            FoodComponent food = item.getFoodComponent();
            int reducedFood = Math.max(1, (int) Math.round(food.getHunger() * 0.1));
            float reducedSaturation = food.getSaturationModifier() * 0.1f;
            manager.add(reducedFood, reducedSaturation);
        } else {
            manager.eat(item, stack);
        }
    }
}
