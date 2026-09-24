package com.aniblaze.autohook;

import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Отслеживание {@link FishingBobberEntity} (поплавков) для каждого игрока.
 * Хранит Map<UUID, FishingBobberEntity> активных крючков.
 * Проверяет состояние зацепа (hookState) поплавка.
 * Потокобезопасен за счёт ConcurrentHashMap.
 */
public final class HookTracker {

    /** Активные поплавки: UUID игрока -> FishingBobberEntity */
    private final Map<UUID, FishingBobberEntity> activeHooks = new ConcurrentHashMap<>();

    /**
     * Регистрирует поплавок для указанного игрока.
     *
     * @param playerId UUID игрока
     * @param bobber   поплавок
     */
    public void registerHook(UUID playerId, FishingBobberEntity bobber) {
        activeHooks.put(playerId, bobber);
    }

    /**
     * Удаляет поплавок из отслеживания для указанного игрока.
     *
     * @param playerId UUID игрока
     */
    public void removeHook(UUID playerId) {
        activeHooks.remove(playerId);
    }

    /**
     * Получает текущий активный поплавок игрока.
     *
     * @param playerId UUID игрока
     * @return поплавок или null, если нет активного
     */
    public FishingBobberEntity getHook(UUID playerId) {
        return activeHooks.get(playerId);
    }

    /**
     * Проверяет, есть ли у игрока активный поплавок.
     *
     * @param playerId UUID игрока
     * @return true, если поплавок зарегистрирован
     */
    public boolean hasHook(UUID playerId) {
        return activeHooks.containsKey(playerId);
    }

    /**
     * Проверяет, зацепился ли поплавок (находится в состоянии FISHING или BOBBER).
     * Использует отражение для доступа к приватному полю hookState,
     * так как в 1.20.1 это поле не имеет публичного геттера.
     *
     * @param bobber поплавок для проверки
     * @return true, если поплавок зацепился
     */
    public boolean isHooked(FishingBobberEntity bobber) {
        if (bobber == null || bobber.isRemoved()) {
            return false;
        }
        try {
            java.lang.reflect.Field hookStateField = FishingBobberEntity.class.getDeclaredField("hookState");
            hookStateField.setAccessible(true);
            int hookState = hookStateField.getInt(bobber);

            // Состояния зацепа в FishingBobberEntity:
            // 0 = IN_HOOK (только что брошен)
            // 1 = FISHING (ловит рыбу / зацепился)
            // 2 = BOBBER (втянут / зацепился за блок)
            return hookState == 1 || hookState == 2;
        } catch (Exception e) {
            // Fallback: если рефлексия не сработала, считаем что зацепился
            // если поплавок существует и не удалён
            return !bobber.isRemoved();
        }
    }

    /**
     * Проверяет, удалён ли поплавок (был ли он уничтожен/снят).
     *
     * @param playerId UUID игрока
     * @return true, если поплавок был удалён или не существует
     */
    public boolean isHookRemoved(UUID playerId) {
        FishingBobberEntity bobber = activeHooks.get(playerId);
        return bobber == null || bobber.isRemoved();
    }

    /**
     * Очищает все данные отслеживания (при выключении сервера).
     */
    public void clearAll() {
        activeHooks.clear();
    }

    /**
     * Очищает данные конкретного игрока.
     *
     * @param playerId UUID игрока
     */
    public void clearPlayer(UUID playerId) {
        activeHooks.remove(playerId);
    }
}
