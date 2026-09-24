package com.aniblaze.tokyoghoul.init;

import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import dev.onyxstudios.cca.api.v3.entity.EntityComponentFactoryRegistry;
import dev.onyxstudios.cca.api.v3.entity.EntityComponentInitializer;
import dev.onyxstudios.cca.api.v3.entity.RespawnCopyStrategy;

/**
 * Привязывает компонент гуля ко всем игрокам (Cardinal Components).
 * Регистрируется через entrypoint "cardinal-components-entity" в fabric.mod.json.
 */
public class GhoulComponentInitializer implements EntityComponentInitializer {
    @Override
    public void registerEntityComponentFactories(EntityComponentFactoryRegistry registry) {
        registry.registerForPlayers(ModComponents.GHOUL, GhoulComponent::new, RespawnCopyStrategy.ALWAYS_COPY);
    }
}
