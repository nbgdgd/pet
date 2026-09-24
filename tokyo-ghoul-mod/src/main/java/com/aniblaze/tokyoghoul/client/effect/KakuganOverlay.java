package com.aniblaze.tokyoghoul.client.effect;

import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;

public class KakuganOverlay {
    private final MinecraftClient client = MinecraftClient.getInstance();
    private float intensity = 0f;

    public void render(DrawContext context) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        GhoulComponent data = GhoulComponent.get(player);
        if (!data.isGhoul()) {
            intensity *= 0.85f;
            return;
        }

        boolean active = data.isKakuganActive() || data.isKakujaActive();
        intensity = active ? Math.min(1.0f, intensity + 0.05f) : Math.max(0.0f, intensity - 0.05f);
        if (intensity <= 0.01f) return;

        float alpha = intensity * 0.12f;
        if (data.getMadness() > 70) alpha += (data.getMadness() / 100.0f) * 0.08f;
        alpha = Math.min(alpha, 0.25f);

        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        int vignetteWidth = Math.max(w, h) / 6;
        int a = (int) (alpha * 255) & 0xFF;

        int vignette = (a << 24) | 0x440000;
        context.fill(0, 0, vignetteWidth, h, vignette);
        context.fill(w - vignetteWidth, 0, w, h, vignette);
        context.fill(0, 0, w, vignetteWidth / 2, vignette);
        context.fill(0, h - vignetteWidth / 2, w, h, vignette);
    }
}
