package com.aniblaze.tokyoghoul.client.render.entity;

import com.aniblaze.tokyoghoul.entity.ModEntities;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class ModEntityRenderers {
    public static void register() {
        EntityRendererRegistry.register(ModEntities.ROAMING_GHOUL, GeckoGhoulEntityRenderer::new);
        EntityRendererRegistry.register(ModEntities.ONE_EYED_GHOUL, GeckoGhoulEntityRenderer::new);
        EntityRendererRegistry.register(ModEntities.KAKUJA_MUTANT, GeckoKakujaMutantRenderer::new);
        EntityRendererRegistry.register(ModEntities.BLOOD_SPEAR, BloodSpearRenderer::new);
    }
}
