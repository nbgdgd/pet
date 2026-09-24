package com.aniblaze.tokyoghoul.client.hud;

import com.aniblaze.tokyoghoul.api.AbilityInstance;
import com.aniblaze.tokyoghoul.api.KaguneAbility;
import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import com.aniblaze.tokyoghoul.config.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * HUD гуля: карточка статуса слева сверху и панель способностей справа.
 * Бар способностей берётся напрямую из синхронизированного состояния игрока,
 * поэтому показывает реальные имена, стоимость RC и кулдаун.
 */
public class GhoulHUD {
    private final MinecraftClient client = MinecraftClient.getInstance();

    private static final String[] RANK_NAMES = {"C", "B", "A", "S", "SS", "SSS"};
    private static final String[] ABILITY_KEYS = {"Z", "X", "C"};

    // Палитра
    private static final int PANEL_BG  = 0xD20C0C10;
    private static final int PANEL_TOP = 0x33FFFFFF;
    private static final int LINE      = 0x40FFFFFF;
    private static final int TXT       = 0xFFF2F2F5;
    private static final int TXT_DIM   = 0xFF9A9AA6;

    public void render(DrawContext ctx, float tickDelta) {
        PlayerEntity player = client.player;
        if (player == null) return;
        GhoulComponent data = GhoulComponent.get(player);
        if (data == null || !data.isGhoul()) return;

        TextRenderer font = client.textRenderer;
        renderStatusCard(ctx, font, data);
        renderAbilityPanel(ctx, font, data);
    }

    // ───────────────────────── Карточка статуса ─────────────────────────
    private void renderStatusCard(DrawContext ctx, TextRenderer font, GhoulComponent data) {
        int x = 8, y = 8, w = 158, h = 64;
        panel(ctx, x, y, w, h);

        int accent = typeColor(data.getKaguneTypeId());
        ctx.fill(x, y, x + 3, y + h, accent);              // акцентная полоса слева

        int px = x + 9;
        int cy = y + 6;

        // Бейдж ранга
        int rankColor = rankColor(data.getRank());
        String rank = RANK_NAMES[Math.min(data.getRank(), RANK_NAMES.length - 1)];
        int bw = Math.max(16, font.getWidth(rank) + 8);
        ctx.fill(px, cy, px + bw, cy + 12, rankColor);
        ctx.fill(px, cy, px + bw, cy + 1, brighten(rankColor));
        ctx.drawText(font, rank, px + (bw - font.getWidth(rank)) / 2, cy + 2, 0xFF14000A, false);

        // Тип + стадия
        String type = typeName(data.getKaguneTypeId());
        String stage = stageName(data.getKaguneStage());
        ctx.drawTextWithShadow(font, type, px + bw + 6, cy + 2, TXT);
        ctx.drawTextWithShadow(font, stage, px + bw + 6 + font.getWidth(type) + 6, cy + 2, TXT_DIM);

        // Бары RC / Кровь
        int barX = px, barW = w - 18, barH = 7;
        int by = y + 23;
        bar(ctx, font, barX, by, barW, barH, data.getRc() / ModConfig.MAX_RC,
            0xFF7A28C8, 0xFFB14BFF, 0xFF241433, "RC",
            compact(data.getRc()) + "/" + compact(ModConfig.MAX_RC));

        by += barH + 7;
        bar(ctx, font, barX, by, barW, barH, data.getBlood() / data.getMaxBlood(),
            0xFFA51E1E, 0xFFE7413B, 0xFF331111, "BP",
            (int) data.getBlood() + "/" + (int) data.getMaxBlood());

        // Низ: уровень / SP / голод / безумие
        int fy = y + h - 11;
        ctx.drawTextWithShadow(font, "Lv " + data.getGhoulLevel(), barX, fy, 0xFF6FC8FF);
        ctx.drawTextWithShadow(font, data.getSkillPoints() + " SP", barX + 36, fy, 0xFF67E08A);
        int hungerColor = data.getHunger() > 30 ? 0xFFB9B9C2 : 0xFFFF7A4A;
        if (data.getMadness() > 0) {
            String mad = "M " + data.getMadness() + "%";
            int mc = data.getMadness() >= 70 ? 0xFFFF3A3A : 0xFFCBA0A0;
            ctx.drawTextWithShadow(font, mad, x + w - 9 - font.getWidth(mad), fy, mc);
        }
        ctx.drawTextWithShadow(font, "Hunger " + (int) data.getHunger() + "%", barX + 76, fy, hungerColor);

        // Чипы активных состояний под карточкой
        int chipX = x, chipY = y + h + 4;
        if (data.isKakuganActive())          chipX = chip(ctx, font, chipX, chipY, "Kakugan",  0xCC5A0E0E, 0xFFFF7A7A);
        if (data.isPredatorInstinctActive()) chipX = chip(ctx, font, chipX, chipY, "Instinct", 0xCC3A0E5A, 0xFFD79BFF);
        if (data.isKakujaActive())           chip(ctx, font, chipX, chipY, "Kakuja",   0xCC5A0000, 0xFFFF4444);
    }

    // ───────────────────────── Панель способностей ─────────────────────────
    private void renderAbilityPanel(DrawContext ctx, TextRenderer font, GhoulComponent data) {
        KaguneAbility[] abilities = resolveAbilities(data);
        if (abilities.length == 0) return;

        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        int accent = typeColor(data.getKaguneTypeId());

        int slotW = 132, slotH = 28, gap = 4;
        int x = sw - slotW - 8;
        int startY = sh - (slotH + gap) * abilities.length - 8;

        List<AbilityInstance> insts = data.getAbilities();

        for (int i = 0; i < abilities.length; i++) {
            KaguneAbility ab = abilities[i];
            int cdNow = (i < insts.size()) ? insts.get(i).getCooldown() : 0;
            int cdMax = Math.max(1, ab.getCooldownTicks());
            boolean enoughBlood = data.getBlood() >= ab.getRcCost();
            boolean ready = cdNow <= 0 && enoughBlood;

            int y = startY + i * (slotH + gap);
            abilitySlot(ctx, font,
                x, y, slotW, slotH,
                i < ABILITY_KEYS.length ? ABILITY_KEYS[i] : "?",
                Text.translatable(ab.getTranslationKey()).getString(),
                ab.getRcCost(), enoughBlood, cdNow, cdMax, ready, accent);
        }
    }

    private void abilitySlot(DrawContext ctx, TextRenderer font, int x, int y, int w, int h,
                             String key, String name, int cost, boolean enoughBlood,
                             int cdNow, int cdMax, boolean ready, int accent) {
        ctx.fill(x, y, x + w, y + h, 0xD20C0C10);

        int kb = h; // квадрат клавиши
        ctx.fill(x, y, x + kb, y + h, ready ? accent : darken(accent));
        ctx.fill(x, y, x + kb, y + 1, brighten(accent));
        int kx = x + (kb - font.getWidth(key)) / 2;
        ctx.drawText(font, key, kx, y + (h - 8) / 2, 0xFF14000A, false);

        // название + стоимость
        int tx = x + kb + 6;
        ctx.drawText(font, trim(font, name, w - kb - 12), tx, y + 5, ready ? TXT : 0xFF8A8A92, false);
        int costColor = enoughBlood ? 0xFFE7708A : 0xFFFF4040;
        ctx.drawText(font, cost + " BP", tx, y + 16, costColor, false);

        // кулдаун: затемнение сверху-вниз + секунды
        if (cdNow > 0) {
            int fillH = (int) Math.ceil(h * (cdNow / (float) cdMax));
            ctx.fill(x + kb, y + h - fillH, x + w, y + h, 0x90000000);
            String s = (cdNow / 20 + 1) + "s";
            ctx.drawText(font, s, x + w - font.getWidth(s) - 5, y + (h - 8) / 2, 0xFFE6E6EC, false);
        } else if (ready) {
            ctx.drawText(font, "✓", x + w - font.getWidth("✓") - 6, y + (h - 8) / 2, 0xFF67E08A, false);
        }

        drawBorder(ctx, x, y, w, h, ready ? withAlpha(accent, 0x99) : 0x40000000);
    }

    /** Список способностей: из синхронизированного состояния, иначе по типу кагуне. */
    private KaguneAbility[] resolveAbilities(GhoulComponent data) {
        List<AbilityInstance> insts = data.getAbilities();
        if (insts != null && !insts.isEmpty()) {
            KaguneAbility[] out = new KaguneAbility[insts.size()];
            for (int i = 0; i < insts.size(); i++) out[i] = insts.get(i).getAbility();
            return out;
        }
        return com.aniblaze.tokyoghoul.init.ModAbilities.forType(data.getKaguneTypeId());
    }

    // ───────────────────────── Утилиты ─────────────────────────
    private void panel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, PANEL_BG);
        ctx.fill(x, y, x + w, y + 1, PANEL_TOP);
        drawBorder(ctx, x, y, w, h, LINE);
    }

    private void bar(DrawContext ctx, TextRenderer font, int x, int y, int w, int h,
                     double pct, int low, int high, int track, String label, String value) {
        pct = Math.max(0, Math.min(1, pct));
        ctx.fill(x, y, x + w, y + h, track);
        int fw = (int) (w * pct);
        if (fw > 0) {
            ctx.fill(x, y, x + fw, y + h, low);
            ctx.fill(x, y, x + fw, y + h - h / 2, high);
        }
        ctx.fill(x, y, x + w, y + 1, 0x22FFFFFF);
        drawBorder(ctx, x, y, w, h, 0x55000000);
        ctx.drawText(font, label, x + 3, y - 1 + (h - 8) / 2, 0xFFFFFFFF, false);
        ctx.drawText(font, value, x + w - font.getWidth(value) - 3, y - 1 + (h - 8) / 2, 0xFFEDEDED, false);
    }

    private int chip(DrawContext ctx, TextRenderer font, int x, int y, String text, int bg, int fg) {
        int w = font.getWidth(text) + 10;
        ctx.fill(x, y, x + w, y + 12, bg);
        drawBorder(ctx, x, y, w, 12, withAlpha(fg, 0x55));
        ctx.drawText(font, text, x + 5, y + 2, fg, false);
        return x + w + 4;
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int c) {
        ctx.fill(x, y, x + w, y + 1, c);
        ctx.fill(x, y + h - 1, x + w, y + h, c);
        ctx.fill(x, y, x + 1, y + h, c);
        ctx.fill(x + w - 1, y, x + w, y + h, c);
    }

    private String trim(TextRenderer font, String s, int maxW) {
        if (font.getWidth(s) <= maxW) return s;
        while (s.length() > 1 && font.getWidth(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private String compact(double v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000);
        if (v >= 1_000) return String.format("%.1fk", v / 1_000);
        return String.valueOf((int) v);
    }

    private String typeName(String id) {
        if (id == null) return "—";
        return switch (id) {
            case "tokyoghoul:rinkaku" -> "Rinkaku";
            case "tokyoghoul:ukaku"   -> "Ukaku";
            case "tokyoghoul:koukaku" -> "Koukaku";
            case "tokyoghoul:bikaku"  -> "Bikaku";
            case "tokyoghoul:kakuja"  -> "Kakuja";
            default -> "—";
        };
    }

    private int typeColor(String id) {
        if (id == null) return 0xFFB14BFF;
        return switch (id) {
            case "tokyoghoul:rinkaku" -> 0xFFE7413B;
            case "tokyoghoul:ukaku"   -> 0xFF4D8DFF;
            case "tokyoghoul:koukaku" -> 0xFFC04BFF;
            case "tokyoghoul:bikaku"  -> 0xFF49D16A;
            case "tokyoghoul:kakuja"  -> 0xFF9A2BD6;
            default -> 0xFFB14BFF;
        };
    }

    private String stageName(int stage) {
        return switch (stage) {
            case 1 -> "Stage I";
            case 2 -> "Stage II";
            case 3 -> "Stage III";
            case 4 -> "Stage IV";
            case 5 -> "Kakuja";
            default -> "Stage I";
        };
    }

    private int rankColor(int rank) {
        return switch (rank) {
            case 0 -> 0xFFB0B0B8;
            case 1 -> 0xFF5FE07A;
            case 2 -> 0xFF55AAFF;
            case 3 -> 0xFFFFB23E;
            case 4 -> 0xFFFF5A5A;
            case 5 -> 0xFFC04BFF;
            default -> 0xFFFFFFFF;
        };
    }

    private int withAlpha(int color, int alpha) { return (alpha << 24) | (color & 0xFFFFFF); }

    private int brighten(int c) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + 60);
        int g = Math.min(255, ((c >> 8) & 0xFF) + 60);
        int b = Math.min(255, (c & 0xFF) + 60);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int darken(int c) {
        int r = (int) (((c >> 16) & 0xFF) * 0.45);
        int g = (int) (((c >> 8) & 0xFF) * 0.45);
        int b = (int) ((c & 0xFF) * 0.45);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
