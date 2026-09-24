package com.aniblaze.tokyoghoul.client.render.entity;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.entity.boss.KakujaMutantEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class KakujaMutantModel extends GeoModel<KakujaMutantEntity> {
    private static final Identifier MODEL = TokyoGhoulMod.id("geo/entity/kakuja_mutant.geo.json");
    private static final Identifier TEXTURE = TokyoGhoulMod.id("textures/entity/kakuja_mutant.png");
    private static final Identifier RAGE_TEXTURE = TokyoGhoulMod.id("textures/entity/kakuja_mutant_rage.png");
    private static final Identifier ANIMATION = TokyoGhoulMod.id("animations/entity/kakuja_mutant.animation.json");

    @Override
    public Identifier getModelResource(KakujaMutantEntity entity) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(KakujaMutantEntity entity) {
        return entity.getHealth() < entity.getMaxHealth() * 0.3f ? RAGE_TEXTURE : TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(KakujaMutantEntity entity) {
        return ANIMATION;
    }
}
