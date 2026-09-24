package com.aniblaze.tokyoghoul.client;

import com.aniblaze.tokyoghoul.network.ModNetworking;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;

@Environment(EnvType.CLIENT)
public class TokyoGhoulClientNetworking {

    public static void registerS2CPackets() {
        // Ghoul data is synced via CCA auto-sync, no manual packets needed
    }

    public static void sendKakuganToggle() {
        ClientPlayNetworking.send(ModNetworking.KAKUGAN_TOGGLE, new PacketByteBuf(Unpooled.buffer()));
    }

    public static void sendPredatorInstinctToggle() {
        ClientPlayNetworking.send(ModNetworking.PREDATOR_INSTINCT_TOGGLE, new PacketByteBuf(Unpooled.buffer()));
    }

    public static void sendKakujaTransform() {
        ClientPlayNetworking.send(ModNetworking.KAKUJA_TRANSFORM, new PacketByteBuf(Unpooled.buffer()));
    }

    public static void sendAbilityUse(int slot) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(slot);
        ClientPlayNetworking.send(ModNetworking.USE_ABILITY, buf);
    }

    public static void sendConsumeEntity(int entityId) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(entityId);
        ClientPlayNetworking.send(ModNetworking.CONSUME_ENTITY, buf);
    }

    public static void sendSelectAbility(int slot) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(slot);
        ClientPlayNetworking.send(ModNetworking.SELECT_ABILITY, buf);
    }
}
