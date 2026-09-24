package com.aniblaze.tokyoghoul.client.render.entity;

import com.aniblaze.tokyoghoul.entity.boss.KakujaMutantEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class GeckoKakujaMutantRenderer extends GeoEntityRenderer<KakujaMutantEntity> {
    public GeckoKakujaMutantRenderer(EntityRendererFactory.Context renderManager) {
        super(renderManager, new KakujaMutantModel());
        this.shadowRadius = 1.2f;
    }
}
