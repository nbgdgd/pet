package com.aniblaze.autohook;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Утилита для расчёта и применения поворота камеры игрока.
 * Все операции выполняются через отправку серверных пакетов (S2C/C2S),
 * без использования клиентских классов.
 */
public final class RotationHelper {

    private RotationHelper() {
    }

    /**
     * Рассчитывает углы поворота (yaw, pitch) от позиции {@code from} к позиции {@code to}.
     *
     * @param from исходная позиция (глаза игрока)
     * @param to   целевая позиция
     * @return массив [yaw, pitch] в градусах
     */
    public static float[] calculateRotation(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) MathHelper.wrapDegrees(-Math.toDegrees(Math.atan2(dy, horizontalDist)));

        return new float[]{yaw, pitch};
    }

    /**
     * Плавно интерполирует текущий yaw к целевому yaw, учитывая maximumDelta.
     *
     * @param currentYaw  текущий yaw игрока
     * @param targetYaw   целевой yaw
     * @param maxDelta    максимальное изменение за один тик (в градусах)
     * @return новый yaw после интерполяции
     */
    public static float interpolateYaw(float currentYaw, float targetYaw, float maxDelta) {
        float diff = MathHelper.wrapDegrees(targetYaw - currentYaw);
        if (Math.abs(diff) <= maxDelta) {
            return targetYaw;
        }
        return currentYaw + Math.signum(diff) * maxDelta;
    }

    /**
     * Плавно интерполирует текущий pitch к целевому pitch, учитывая maximumDelta.
     *
     * @param currentPitch текущий pitch игрока
     * @param targetPitch  целевой pitch
     * @param maxDelta     максимальное изменение за один тик (в градусах)
     * @return новый pitch после интерполяции
     */
    public static float interpolatePitch(float currentPitch, float targetPitch, float maxDelta) {
        float diff = targetPitch - currentPitch;
        if (Math.abs(diff) <= maxDelta) {
            return targetPitch;
        }
        return currentPitch + Math.signum(diff) * maxDelta;
    }

    /**
     * Проверяет, достаточно ли текущий поворот близок к целевому.
     *
     * @param currentYaw   текущий yaw
     * @param currentPitch текущий pitch
     * @param targetYaw    целевой yaw
     * @param targetPitch  целевой pitch
     * @param tolerance    допустимая погрешность (в градусах)
     * @return true, если поворот в пределах допуска
     */
    public static boolean isRotationClose(float currentYaw, float currentPitch,
                                           float targetYaw, float targetPitch,
                                           float tolerance) {
        float yawDiff = Math.abs(MathHelper.wrapDegrees(targetYaw - currentYaw));
        float pitchDiff = Math.abs(targetPitch - currentPitch);
        return yawDiff <= tolerance && pitchDiff <= tolerance;
    }

    /**
     * Применяет поворот камеры игрока, отправляя пакет {@link PlayerMoveC2SPacket.LookAndOnGround}.
     * Это серверный способ установить направление взгляда игрока.
     *
     * @param player серверный игрок
     * @param yaw    целевой yaw
     * @param pitch  целевой pitch
     */
    public static void applyRotation(ServerPlayerEntity player, float yaw, float pitch) {
        PlayerMoveC2SPacket.LookAndOnGround packet = new PlayerMoveC2SPacket.LookAndOnGround(
                yaw, pitch, player.isOnGround()
        );
        player.networkHandler.onPlayerMovement(packet);
    }
}
