package com.aniblaze.tokyoghoul.player;

import java.util.HashMap;
import java.util.Map;

public class RCMap {
    private static final Map<String, Integer> rcValues = new HashMap<>();

    static {
        rcValues.put("minecraft:chicken", 5);
        rcValues.put("minecraft:pig", 8);
        rcValues.put("minecraft:cow", 12);
        rcValues.put("minecraft:sheep", 10);
        rcValues.put("minecraft:horse", 15);
        rcValues.put("minecraft:donkey", 12);
        rcValues.put("minecraft:mule", 12);
        rcValues.put("minecraft:rabbit", 4);
        rcValues.put("minecraft:wolf", 15);
        rcValues.put("minecraft:cat", 8);
        rcValues.put("minecraft:parrot", 5);
        rcValues.put("minecraft:fox", 12);
        rcValues.put("minecraft:panda", 20);
        rcValues.put("minecraft:polar_bear", 25);
        rcValues.put("minecraft:llama", 15);
        rcValues.put("minecraft:bee", 3);
        rcValues.put("minecraft:strider", 18);
        rcValues.put("minecraft:goat", 15);
        rcValues.put("minecraft:frog", 5);
        rcValues.put("minecraft:turtle", 8);

        rcValues.put("minecraft:zombie", 20);
        rcValues.put("minecraft:skeleton", 15);
        rcValues.put("minecraft:creeper", 25);
        rcValues.put("minecraft:spider", 12);
        rcValues.put("minecraft:cave_spider", 10);
        rcValues.put("minecraft:ender_man", 40);
        rcValues.put("minecraft:enderman", 40);
        rcValues.put("minecraft:blaze", 30);
        rcValues.put("minecraft:ghast", 35);
        rcValues.put("minecraft:magma_cube", 20);
        rcValues.put("minecraft:slime", 10);
        rcValues.put("minecraft:witch", 35);
        rcValues.put("minecraft:pillager", 25);
        rcValues.put("minecraft:vindicator", 30);
        rcValues.put("minecraft:evoker", 50);
        rcValues.put("minecraft:ravager", 80);
        rcValues.put("minecraft:vex", 15);
        rcValues.put("minecraft:phantom", 20);
        rcValues.put("minecraft:drowned", 20);
        rcValues.put("minecraft:husk", 22);
        rcValues.put("minecraft:stray", 17);
        rcValues.put("minecraft:zombified_piglin", 25);
        rcValues.put("minecraft:hoglin", 40);
        rcValues.put("minecraft:piglin", 25);
        rcValues.put("minecraft:piglin_brute", 40);
        rcValues.put("minecraft:zoglin", 30);
        rcValues.put("minecraft:wither_skeleton", 35);
        rcValues.put("minecraft:guardian", 30);
        rcValues.put("minecraft:elder_guardian", 100);
        rcValues.put("minecraft:shulker", 30);

        rcValues.put("minecraft:iron_golem", 250);
        rcValues.put("minecraft:snow_golem", 10);

        rcValues.put("minecraft:villager", 150);
        rcValues.put("minecraft:wandering_trader", 120);

        rcValues.put("minecraft:ender_dragon", 2000);
        rcValues.put("minecraft:wither", 1500);

        rcValues.put("minecraft:warden", 1000);
        rcValues.put("minecraft:allay", 8);
        rcValues.put("minecraft:axolotl", 10);
        rcValues.put("minecraft:glow_squid", 8);
        rcValues.put("minecraft:squid", 8);
        rcValues.put("minecraft:dolphin", 15);
        rcValues.put("minecraft:tropical_fish", 3);
        rcValues.put("minecraft:cod", 3);
        rcValues.put("minecraft:salmon", 3);
        rcValues.put("minecraft:pufferfish", 5);
        rcValues.put("minecraft:mooshroom", 15);
        rcValues.put("minecraft:ocelot", 10);
        rcValues.put("minecraft:bat", 2);
        rcValues.put("minecraft:camel", 18);
        rcValues.put("minecraft:sniffer", 20);

        rcValues.put("minecraft:player", 100);
    }

    public static int getRcFor(String entityType) {
        Integer val = rcValues.get(entityType);
        if (val != null) return val;
        for (Map.Entry<String, Integer> entry : rcValues.entrySet()) {
            if (entityType.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return 10;
    }

    public static int getRcForPlayer() { return 100; }
    public static int getRcForVillager() { return 150; }
}
