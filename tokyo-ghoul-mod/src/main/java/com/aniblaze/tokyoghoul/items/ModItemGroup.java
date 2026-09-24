package com.aniblaze.tokyoghoul.items;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;

public class ModItemGroup {
    public static final ItemGroup TOKYO_GHOUL_GROUP = FabricItemGroup.builder()
        .displayName(Text.literal("Tokyo Ghoul"))
        .icon(() -> new ItemStack(ModItems.KAKUHOU))
        .entries((displayContext, entries) -> {
            entries.add(ModItems.KAKUHOU);
            entries.add(ModItems.KAGUNE_CRYSTAL_UKAKU);
            entries.add(ModItems.KAGUNE_CRYSTAL_KOUKAKU);
            entries.add(ModItems.KAGUNE_CRYSTAL_RINKAKU);
            entries.add(ModItems.KAGUNE_CRYSTAL_BIKAKU);
            entries.add(ModItems.RC_CELL);
            entries.add(ModItems.BLOOD_VIAL);
        })
        .build();

    public static void register() {
        Registry.register(Registries.ITEM_GROUP, TokyoGhoulMod.id("tokyo_ghoul"), TOKYO_GHOUL_GROUP);
    }
}
