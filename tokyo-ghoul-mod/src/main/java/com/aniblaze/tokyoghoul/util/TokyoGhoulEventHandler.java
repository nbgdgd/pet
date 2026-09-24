package com.aniblaze.tokyoghoul.util;

import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import com.aniblaze.tokyoghoul.config.ModConfig;
import com.aniblaze.tokyoghoul.player.RCMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

import java.util.Random;

public class TokyoGhoulEventHandler {

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

            GhoulComponent data = GhoulComponent.get(player);
            if (!data.isGhoul()) return ActionResult.PASS;

            if (player.isSneaking() && entity instanceof LivingEntity living) {
                boolean canDevour = !living.isAlive()
                    || living.getHealth() <= living.getMaxHealth() * 0.3f;

                if (canDevour) {
                    String entityType = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).toString();
                    int amount = RCMap.getRcFor(entityType);
                    data.addRc(amount);
                    data.addBlood(amount * 0.5);
                    if (entity instanceof PlayerEntity) {
                        player.sendMessage(Text.translatable("text.tokyoghoul.rc_gain", RCMap.getRcForPlayer()), true);
                    } else {
                        player.sendMessage(Text.translatable("text.tokyoghoul.rc_gain", RCMap.getRcFor(entityType)), true);
                    }
                    living.damage(player.getDamageSources().playerAttack(player), Float.MAX_VALUE);
                    if (player instanceof ServerPlayerEntity serverPlayer) {
                        serverPlayer.getServerWorld().playSound(null, serverPlayer.getBlockPos(),
                            ModSounds.CONSUME, net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
                        AdvancementHelper.grant(serverPlayer, "first_kill", "first_consume");
                        if (entity instanceof net.minecraft.entity.passive.VillagerEntity) {
                            AdvancementHelper.grant(serverPlayer, "village_hunter", "kill_villagers");
                        }
                        AdvancementHelper.checkProgress(serverPlayer, data);
                    }
                    return ActionResult.SUCCESS;
                } else {
                    float bite = 6.0f + data.getKaguneStage() * 2.0f;
                    living.damage(player.getDamageSources().playerAttack(player), bite);
                    living.takeKnockback(0.3, player.getX() - living.getX(), player.getZ() - living.getZ());
                    player.swingHand(Hand.MAIN_HAND, true);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            GhoulComponent newData = GhoulComponent.get(newPlayer);
            if (newData.isGhoul()) {
                newData.sync();
            }
        });
    }

    public static void tryBecomeGhoul(PlayerEntity player) {
        if (player.getWorld().isClient) return;
        GhoulComponent data = GhoulComponent.get(player);
        if (data.isGhoul()) return;

        Random rand = new Random();
        if (rand.nextInt(100) < ModConfig.GHUL_SPAWN_CHANCE) {
            data.setGhoul(true);
            int typeIndex = rand.nextInt(4);
            String[] typeIds = {"tokyoghoul:rinkaku", "tokyoghoul:ukaku", "tokyoghoul:koukaku", "tokyoghoul:bikaku"};
            data.setKaguneTypeId(typeIds[typeIndex]);
            data.setRcRaw(500);
            data.setBloodRaw(100);
            data.setHungerRaw(100);
            if (player instanceof ServerPlayerEntity serverPlayer) {
                data.sync();
                AdvancementHelper.grant(serverPlayer, "root", "become_ghoul");
                player.sendMessage(Text.translatable("text.tokyoghoul.you_are_ghoul"), false);
            }
        }
    }
}
