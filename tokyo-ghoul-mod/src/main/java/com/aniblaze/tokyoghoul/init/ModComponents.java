package com.aniblaze.tokyoghoul.init;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import dev.onyxstudios.cca.api.v3.component.ComponentKey;
import dev.onyxstudios.cca.api.v3.component.ComponentRegistryV3;

public class ModComponents {
    public static final ComponentKey<GhoulComponent> GHOUL =
        ComponentRegistryV3.INSTANCE.getOrCreate(TokyoGhoulMod.id("ghoul"), GhoulComponent.class);
}
