package com.aniblaze.tokyoghoul.client.render.entity;

import com.aniblaze.tokyoghoul.entity.ghoul.BaseGhoulEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class GeckoGhoulEntityRenderer extends GeoEntityRenderer<BaseGhoulEntity> {
    public GeckoGhoulEntityRenderer(EntityRendererFactory.Context renderManager) {
        super(renderManager, new GhoulModel());
        this.shadowRadius = 0.7f;
    }
}
