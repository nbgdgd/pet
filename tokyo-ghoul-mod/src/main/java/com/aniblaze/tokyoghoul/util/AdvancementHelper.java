package com.aniblaze.tokyoghoul.util;

import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class AdvancementHelper {

    public static void grant(ServerPlayerEntity player, String path, String criterion) {
        if (player == null || player.getServer() == null) return;
        Advancement adv = player.getServer().getAdvancementLoader()
            .get(new Identifier("tokyoghoul", path));
        if (adv == null) return;
        PlayerAdvancementTracker tracker = player.getAdvancementTracker();
        AdvancementProgress progress = tracker.getProgress(adv);
        if (!progress.isDone()) {
            tracker.grantCriterion(adv, criterion);
        }
    }

    public static void checkProgress(ServerPlayerEntity player, GhoulComponent data) {
        if (data.getKaguneStage() >= 2) grant(player, "kagune_evolve", "evolve_kagune");
        if (data.getKaguneStage() >= 5) grant(player, "kakuja", "achieve_kakuja");
        if (data.getRank() >= 5) grant(player, "rank_sss", "rank_sss");
    }
}
