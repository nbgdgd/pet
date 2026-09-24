package com.aniblaze.tokyoghoul.api;

import net.minecraft.util.Identifier;

public class KaguneType {
    private final Identifier id;
    private final int color;
    private final String translationKey;
    private final boolean isKakuja;

    public KaguneType(Identifier id, int color, String translationKey, boolean isKakuja) {
        this.id = id;
        this.color = color;
        this.translationKey = translationKey;
        this.isKakuja = isKakuja;
    }

    public Identifier getId() { return id; }
    public int getColor() { return color; }
    public String getTranslationKey() { return translationKey; }
    public boolean isKakuja() { return isKakuja; }
}
