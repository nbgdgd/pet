package com.aniblaze.tokyoghoul.items;

import com.aniblaze.tokyoghoul.TokyoGhoulMod;
import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Hand;
import net.minecraft.util.Rarity;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.Random;

public class ModItems {
    public static final Item KAKUHOU = register("kakuhou", new Item(new FabricItemSettings().maxCount(1).rarity(Rarity.EPIC)) {
        @Override
        public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
            ItemStack stack = user.getStackInHand(hand);
            if (!world.isClient) {
                GhoulComponent data = GhoulComponent.get(user);
                if (!data.isGhoul()) {
                    Random random = new Random();
                    int typeIndex = random.nextInt(4);
                    String[] typeIds = {"tokyoghoul:rinkaku", "tokyoghoul:ukaku", "tokyoghoul:koukaku", "tokyoghoul:bikaku"};
                    data.setKaguneTypeId(typeIds[typeIndex]);
                    data.setGhoul(true);
                    data.setRcRaw(500);
                    data.setBloodRaw(100);
                    data.setHungerRaw(100);
                    data.sync();
                    com.aniblaze.tokyoghoul.util.AdvancementHelper.grant(
                        (net.minecraft.server.network.ServerPlayerEntity) user, "root", "become_ghoul");
                    user.sendMessage(
                        net.minecraft.text.Text.translatable("text.tokyoghoul.kakuhou_activate"), false
                    );
                    if (!user.isCreative()) stack.decrement(1);
                }
            }
            return TypedActionResult.success(stack);
        }
    });

    public static final Item RC_CELL = register("rc_cell", new Item(new FabricItemSettings().rarity(Rarity.UNCOMMON)));
    public static final Item BLOOD_VIAL = register("blood_vial", new Item(new FabricItemSettings().rarity(Rarity.UNCOMMON)) {
        @Override
        public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
            ItemStack stack = user.getStackInHand(hand);
            if (!world.isClient) {
                GhoulComponent data = GhoulComponent.get(user);
                if (data.isGhoul()) {
                    data.addBlood(50);
                    data.sync();
                    if (!user.isCreative()) stack.decrement(1);
                }
            }
            return TypedActionResult.success(stack);
        }
    });

    public static final Item KAGUNE_CRYSTAL_UKAKU = register("kagune_crystal_ukaku", new Item(new FabricItemSettings().rarity(Rarity.RARE)));
    public static final Item KAGUNE_CRYSTAL_KOUKAKU = register("kagune_crystal_koukaku", new Item(new FabricItemSettings().rarity(Rarity.RARE)));
    public static final Item KAGUNE_CRYSTAL_RINKAKU = register("kagune_crystal_rinkaku", new Item(new FabricItemSettings().rarity(Rarity.RARE)));
    public static final Item KAGUNE_CRYSTAL_BIKAKU = register("kagune_crystal_bikaku", new Item(new FabricItemSettings().rarity(Rarity.RARE)));

    public static void register() {
        ModItemGroup.register();
        TokyoGhoulMod.LOGGER.info("Registered Tokyo Ghoul items");
    }

    private static Item register(String id, Item item) {
        return Registry.register(Registries.ITEM, TokyoGhoulMod.id(id), item);
    }
}
