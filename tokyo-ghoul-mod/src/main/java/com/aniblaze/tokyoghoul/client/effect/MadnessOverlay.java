package com.aniblaze.tokyoghoul.client.effect;

import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;

public class MadnessOverlay {
    private final MinecraftClient client = MinecraftClient.getInstance();

    public void render(DrawContext context) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        GhoulComponent data = GhoulComponent.get(player);
        if (!data.isGhoul() || data.getMadness() < 30) return;

        float intensity = data.getMadness() / 100.0f * 0.08f;

        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        int a = (int) (intensity * 255) & 0xFF;
        int color = (a << 24) | 0x220000;
        context.fill(0, 0, w, h, color);
    }
}
