package com.aniblaze.tokyoghoul;

import com.aniblaze.tokyoghoul.client.TokyoGhoulClientNetworking;
import com.aniblaze.tokyoghoul.client.effect.KakuganOverlay;
import com.aniblaze.tokyoghoul.client.effect.MadnessOverlay;
import com.aniblaze.tokyoghoul.client.hud.GhoulHUD;
import com.aniblaze.tokyoghoul.client.render.entity.ModEntityRenderers;
import com.aniblaze.tokyoghoul.common.component.GhoulComponent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class TokyoGhoulClient implements ClientModInitializer {
    public static KeyBinding kakuganKey;
    public static KeyBinding predatorInstinctKey;
    public static KeyBinding ability1Key;
    public static KeyBinding ability2Key;
    public static KeyBinding ability3Key;
    public static KeyBinding kakujaTransformKey;

    @Override
    public void onInitializeClient() {
        registerKeyBindings();
        TokyoGhoulClientNetworking.registerS2CPackets();
        ModEntityRenderers.register();

        GhoulHUD hud = new GhoulHUD();
        KakuganOverlay kakugan = new KakuganOverlay();
        MadnessOverlay madness = new MadnessOverlay();

        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            madness.render(drawContext);
            kakugan.render(drawContext);
            hud.render(drawContext, tickDelta);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            GhoulComponent data = GhoulComponent.get(client.player);

            while (kakuganKey.wasPressed()) {
                TokyoGhoulClientNetworking.sendKakuganToggle();
            }
            while (predatorInstinctKey.wasPressed()) {
                TokyoGhoulClientNetworking.sendPredatorInstinctToggle();
            }
            while (kakujaTransformKey.wasPressed()) {
                TokyoGhoulClientNetworking.sendKakujaTransform();
            }
            while (ability1Key.wasPressed()) {
                TokyoGhoulClientNetworking.sendAbilityUse(0);
            }
            while (ability2Key.wasPressed()) {
                TokyoGhoulClientNetworking.sendAbilityUse(1);
            }
            while (ability3Key.wasPressed()) {
                TokyoGhoulClientNetworking.sendAbilityUse(2);
            }
        });
    }

    private void registerKeyBindings() {
        kakuganKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.tokyoghoul.kakugan", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.category.tokyoghoul"
        ));
        predatorInstinctKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.tokyoghoul.predator_instinct", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_SHIFT, "key.category.tokyoghoul"
        ));
        ability1Key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.tokyoghoul.ability_1", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.category.tokyoghoul"
        ));
        ability2Key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.tokyoghoul.ability_2", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.category.tokyoghoul"
        ));
        ability3Key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.tokyoghoul.ability_3", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, "key.category.tokyoghoul"
        ));
        kakujaTransformKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.tokyoghoul.kakuja_transform", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.category.tokyoghoul"
        ));
    }
}
