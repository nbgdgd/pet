package com.aniblaze.tokyoghoul.client.render.entity;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.entity.abilities.BloodSpearEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

public class BloodSpearRenderer extends EntityRenderer<BloodSpearEntity> {
    private static final Identifier TEXTURE = TokyoGhoulMod.id("textures/entity/blood_spear.png");

    public BloodSpearRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(BloodSpearEntity entity, float yaw, float tickDelta,
                        MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(entity.getPitch()));

        float scale = 1.5f;
        matrices.scale(scale, scale, scale);

        VertexConsumer consumer = vertexConsumers.getBuffer(
            net.minecraft.client.render.RenderLayer.getEntityTranslucent(getTexture(entity))
        );

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float hw = 0.1f;
        float hh = 0.1f;
        float len = 1.0f;

        for (int i = 0; i < 3; i++) {
            float offset = i * 0.3f - 0.3f;
            consumer.vertex(matrix, -hw, -hh, offset).color(1, 0, 0, 1).texture(0, 0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1).next();
            consumer.vertex(matrix, hw, -hh, offset).color(1, 0, 0, 1).texture(1, 0).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1).next();
            consumer.vertex(matrix, hw, hh, offset + len).color(1, 0, 0, 1).texture(1, 1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1).next();
            consumer.vertex(matrix, -hw, hh, offset + len).color(1, 0, 0, 1).texture(0, 1).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 0, 1).next();
        }

        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override
    public Identifier getTexture(BloodSpearEntity entity) {
        return TEXTURE;
    }
}
