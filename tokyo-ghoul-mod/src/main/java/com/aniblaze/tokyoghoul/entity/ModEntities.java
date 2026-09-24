package com.aniblaze.tokyoghoul.entity;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.entity.boss.KakujaMutantEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import com.aniblaze.tokyoghoul.entity.boss.OneEyedGhoulEntity;
import com.aniblaze.tokyoghoul.entity.boss.RoamingGhoulEntity;
import com.aniblaze.tokyoghoul.entity.abilities.BloodSpearEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public class ModEntities {
    public static final EntityType<RoamingGhoulEntity> ROAMING_GHOUL = Registry.register(
        Registries.ENTITY_TYPE, TokyoGhoulMod.id("roaming_ghoul"),
        FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, RoamingGhoulEntity::new)
            .dimensions(EntityDimensions.fixed(0.9f, 2.5f))
            .trackRangeBlocks(64)
            .trackedUpdateRate(3)
            .build()
    );

    public static final EntityType<OneEyedGhoulEntity> ONE_EYED_GHOUL = Registry.register(
        Registries.ENTITY_TYPE, TokyoGhoulMod.id("one_eyed_ghoul"),
        FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, OneEyedGhoulEntity::new)
            .dimensions(EntityDimensions.fixed(0.9f, 2.4f))
            .trackRangeBlocks(64)
            .trackedUpdateRate(3)
            .build()
    );

    public static final EntityType<KakujaMutantEntity> KAKUJA_MUTANT = Registry.register(
        Registries.ENTITY_TYPE, TokyoGhoulMod.id("kakuja_mutant"),
        FabricEntityTypeBuilder.create(SpawnGroup.MONSTER, KakujaMutantEntity::new)
            .dimensions(EntityDimensions.fixed(1.5f, 3.5f))
            .trackRangeBlocks(64)
            .trackedUpdateRate(2)
            .build()
    );

    public static final EntityType<BloodSpearEntity> BLOOD_SPEAR = Registry.register(
        Registries.ENTITY_TYPE, TokyoGhoulMod.id("blood_spear"),
        FabricEntityTypeBuilder.<BloodSpearEntity>create(SpawnGroup.MISC, BloodSpearEntity::new)
            .dimensions(EntityDimensions.fixed(0.5f, 0.5f))
            .trackRangeBlocks(64)
            .trackedUpdateRate(1)
            .build()
    );

    public static void register() {
        TokyoGhoulMod.LOGGER.info("Registering entity attributes...");

        FabricDefaultAttributeRegistry.register(ROAMING_GHOUL, RoamingGhoulEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(ONE_EYED_GHOUL, OneEyedGhoulEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(KAKUJA_MUTANT, KakujaMutantEntity.createAttributes());

        TokyoGhoulMod.LOGGER.info("Registered Tokyo Ghoul entities");
    }
}
