package com.aniblaze.tokyoghoul.client.render.entity;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.entity.ghoul.BaseGhoulEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

public class GhoulModel extends GeoModel<BaseGhoulEntity> {
    private static final Identifier MODEL = TokyoGhoulMod.id("geo/entity/ghoul.geo.json");
    private static final Identifier TEXTURE = TokyoGhoulMod.id("textures/entity/ghoul.png");
    private static final Identifier KAKUGAN_TEXTURE = TokyoGhoulMod.id("textures/entity/ghoul_kakugan.png");
    private static final Identifier ANIMATION = TokyoGhoulMod.id("animations/entity/ghoul.animation.json");

    @Override
    public Identifier getModelResource(BaseGhoulEntity entity) {
        return MODEL;
    }

    @Override
    public Identifier getTextureResource(BaseGhoulEntity entity) {
        return entity.isKakuganActive() ? KAKUGAN_TEXTURE : TEXTURE;
    }

    @Override
    public Identifier getAnimationResource(BaseGhoulEntity entity) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(BaseGhoulEntity entity, long instanceId, AnimationState<BaseGhoulEntity> animationState) {
        super.setCustomAnimations(entity, instanceId, animationState);

        CoreGeoBone ukaku = getAnimationProcessor().getBone("kagune_ukaku");
        CoreGeoBone koukaku = getAnimationProcessor().getBone("kagune_koukaku");
        CoreGeoBone rinkaku = getAnimationProcessor().getBone("kagune_rinkaku");
        CoreGeoBone bikaku = getAnimationProcessor().getBone("kagune_bikaku");

        boolean hideAll = false;
        switch (entity.getKaguneType()) {
            case 0 -> { showBone(ukaku); hideBone(koukaku); hideBone(rinkaku); hideBone(bikaku); }
            case 1 -> { hideBone(ukaku); showBone(koukaku); hideBone(rinkaku); hideBone(bikaku); }
            case 2 -> { hideBone(ukaku); hideBone(koukaku); showBone(rinkaku); hideBone(bikaku); }
            case 3 -> { hideBone(ukaku); hideBone(koukaku); hideBone(rinkaku); showBone(bikaku); }
            default -> hideAll = true;
        }

        if (hideAll) {
            hideBone(ukaku);
            hideBone(koukaku);
            hideBone(rinkaku);
            hideBone(bikaku);
        }
    }

    private void hideBone(CoreGeoBone bone) {
        if (bone != null) bone.setHidden(true);
    }

    private void showBone(CoreGeoBone bone) {
        if (bone != null) bone.setHidden(false);
    }
}
