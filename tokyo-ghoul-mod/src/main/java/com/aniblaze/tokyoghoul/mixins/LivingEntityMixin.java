package com.aniblaze.tokyoghoul.mixins;

import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    @Inject(method = "tryAttack", at = @At("RETURN"))
    private void onAttack(net.minecraft.entity.Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && (Object) this instanceof PlayerEntity player) {
            GhoulComponent data = GhoulComponent.get(player);
            if (data.isGhoul()) {
                double bloodGain = 2 + data.getStrengthLevel() * 0.1;
                data.addBlood(bloodGain);

                if (target instanceof LivingEntity living && !living.isAlive()) {
                    double rcGain = 1 + data.getStrengthLevel() * 0.05;
                    data.addRc(rcGain);
                }

                if (player instanceof ServerPlayerEntity serverPlayer) {
                    data.sync();
                }
            }
        }
    }
}
