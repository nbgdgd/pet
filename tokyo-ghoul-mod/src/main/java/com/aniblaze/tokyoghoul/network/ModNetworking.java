package com.aniblaze.tokyoghoul.network;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import com.aniblaze.tokyoghoul.common.event.AbilityEventHandler;
import com.aniblaze.tokyoghoul.player.RCMap;
import com.aniblaze.tokyoghoul.util.ModSounds;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class ModNetworking {
    public static final Identifier KAKUGAN_TOGGLE = TokyoGhoulMod.id("kakugan_toggle");
    public static final Identifier PREDATOR_INSTINCT_TOGGLE = TokyoGhoulMod.id("predator_instinct_toggle");
    public static final Identifier KAKUJA_TRANSFORM = TokyoGhoulMod.id("kakuja_transform");
    public static final Identifier CONSUME_ENTITY = TokyoGhoulMod.id("consume_entity");
    public static final Identifier USE_ABILITY = TokyoGhoulMod.id("use_ability");
    public static final Identifier SELECT_ABILITY = TokyoGhoulMod.id("select_ability");

    public static void registerC2SPackets() {
        ServerPlayNetworking.registerGlobalReceiver(KAKUGAN_TOGGLE, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                GhoulComponent data = GhoulComponent.get(player);
                if (data.isGhoul()) {
                    data.setKakuganActive(!data.isKakuganActive());
                    playSound(player, data.isKakuganActive()
                        ? ModSounds.KAGUNE_ACTIVATE : ModSounds.KAGUNE_DEACTIVATE);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(PREDATOR_INSTINCT_TOGGLE, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                GhoulComponent data = GhoulComponent.get(player);
                if (data.isGhoul()) {
                    data.setPredatorInstinctActive(!data.isPredatorInstinctActive());
                    if (data.isPredatorInstinctActive()) playSound(player, ModSounds.GHOUL_ROAR);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(KAKUJA_TRANSFORM, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                GhoulComponent data = GhoulComponent.get(player);
                if (data.isGhoul() && data.getKaguneStage() >= 5) {
                    data.setKakujaActive(!data.isKakujaActive());
                    playSound(player, ModSounds.KAKUJA_TRANSFORM);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(CONSUME_ENTITY, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            server.execute(() -> {
                Entity target = player.getWorld().getEntityById(entityId);
                if (target instanceof PlayerEntity) {
                    GhoulComponent gd = GhoulComponent.get((PlayerEntity) target);
                    if (gd.isGhoul()) return;
                }
                if (target != null && !target.isAlive()) {
                    GhoulComponent data = GhoulComponent.get(player);
                    if (data.isGhoul()) {
                        String entityType = Registries.ENTITY_TYPE.getId(target.getType()).toString();
                        int amount = RCMap.getRcFor(entityType);
                        data.addRc(amount);
                        data.addBlood(amount * 0.5);
                        target.remove(Entity.RemovalReason.KILLED);
                    }
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(USE_ABILITY, (server, player, handler, buf, responseSender) -> {
            int slot = buf.readInt();
            server.execute(() -> {
                AbilityEventHandler.executeAbility(player, slot);
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SELECT_ABILITY, (server, player, handler, buf, responseSender) -> {
            int slot = buf.readInt();
            server.execute(() -> {
                GhoulComponent data = GhoulComponent.get(player);
                data.setSelectedSlot(slot);
            });
        });
    }

    private static void playSound(ServerPlayerEntity player, net.minecraft.sound.SoundEvent sound) {
        player.getServerWorld().playSound(null, player.getBlockPos(), sound,
            net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
    }
}
