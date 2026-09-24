package com.aniblaze.tokyoghoul.client.effect;

import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;

public class PredatorInstinctHandler {
    private final MinecraftClient client = MinecraftClient.getInstance();

    public void render(WorldRenderContext context) {
        ClientPlayerEntity player = client.player;
        if (player == null || player.getWorld() == null) return;

        GhoulComponent data = GhoulComponent.get(player);
        if (!data.isPredatorInstinctActive()) return;

        double range = 32.0;
        Box searchBox = player.getBoundingBox().expand(range);
        var entities = player.getWorld().getEntitiesByClass(LivingEntity.class, searchBox,
            e -> e != player && e.isAlive() && e.squaredDistanceTo(player) <= range * range);
        if (entities.isEmpty()) return;

        float tickDelta = context.tickDelta();
        Vec3d cam = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer lines = consumers.getBuffer(RenderLayer.getLines());

        for (LivingEntity entity : entities) {
            if (!shouldHighlight(entity)) continue;
            Color c = getEntityColor(entity);
            float r = c.getRed() / 255f;
            float g = c.getGreen() / 255f;
            float b = c.getBlue() / 255f;

            double ex = MathHelper.lerp(tickDelta, entity.prevX, entity.getX());
            double ey = MathHelper.lerp(tickDelta, entity.prevY, entity.getY());
            double ez = MathHelper.lerp(tickDelta, entity.prevZ, entity.getZ());

            Box dim = entity.getBoundingBox().offset(-entity.getX(), -entity.getY(), -entity.getZ());
            double minX = ex + dim.minX - cam.x;
            double minY = ey + dim.minY - cam.y;
            double minZ = ez + dim.minZ - cam.z;
            double maxX = ex + dim.maxX - cam.x;
            double maxY = ey + dim.maxY - cam.y;
            double maxZ = ez + dim.maxZ - cam.z;

            WorldRenderer.drawBox(matrices, lines,
                minX, minY, minZ, maxX, maxY, maxZ,
                r, g, b, 1.0f);
        }

        consumers.draw(RenderLayer.getLines());
    }

    private boolean shouldHighlight(LivingEntity entity) {
        return entity instanceof PlayerEntity
            || entity instanceof VillagerEntity
            || entity instanceof AnimalEntity
            || entity instanceof Monster;
    }

    private Color getEntityColor(LivingEntity entity) {
        if (entity instanceof PlayerEntity) return Color.RED;
        if (entity instanceof VillagerEntity) return Color.YELLOW;
        if (entity instanceof AnimalEntity) return Color.WHITE;
        if (entity instanceof Monster) {
            if (entity.getMaxHealth() >= 100) return new Color(150, 0, 255);
            if (entity.getMaxHealth() >= 50) return new Color(200, 0, 200);
            return Color.PINK;
        }
        return Color.GRAY;
    }
}
