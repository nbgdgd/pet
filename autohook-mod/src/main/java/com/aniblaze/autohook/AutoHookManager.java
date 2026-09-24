package com.aniblaze.autohook;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер автоматического крюка.
 * Управляет списком активных игроков, обрабатывает глобальный серверный тик.
 * Регистрирует обработчики событий Fabric: ServerTickEvents и ServerPlayConnectionEvents.
 * Потокобезопасен за счёт ConcurrentHashMap.
 */
public class AutoHookManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoHook");

    /** Singleton экземпляр менеджера */
    private static AutoHookManager instance;

    /** Активные обработчики: UUID игрока -> PlayerAutoHookHandler */
    private final Map<UUID, PlayerAutoHookHandler> activeHandlers = new ConcurrentHashMap<>();

    /** Трейкер поплавков */
    private final HookTracker hookTracker;

    /** Конфигурация мода */
    private final Config config;

    /** Флаг инициализации */
    private boolean initialized = false;

    /**
     * Приватный конструктор менеджера.
     *
     * @param config конфигурация мода
     */
    private AutoHookManager(Config config) {
        this.config = config;
        this.hookTracker = new HookTracker();
    }

    /**
     * Получает singleton экземпляр менеджера.
     *
     * @param config конфигурация (используется только при первом вызове)
     * @return экземпляр {@link AutoHookManager}
     */
    public static AutoHookManager getInstance(Config config) {
        if (instance == null) {
            instance = new AutoHookManager(config);
        }
        return instance;
    }

    /**
     * Получает singleton экземпляр менеджера (без параметров).
     *
     * @return экземпляр {@link AutoHookManager} или null, если не инициализирован
     */
    public static AutoHookManager getInstance() {
        return instance;
    }

    /**
     * Инициализирует менеджер: регистрирует обработчики событий Fabric.
     * Вызывается один раз при старте сервера.
     *
     * @param server экземпляр MinecraftServer
     */
    public void initialize(MinecraftServer server) {
        if (initialized) {
            return;
        }
        initialized = true;

        // Регистрируем обработчик тика сервера
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        // Регистрируем обработчик отключения игроков
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server1) -> {
            onPlayerDisconnect(handler.getPlayer().getUuid());
        });

        LOGGER.info("[AutoHook] Менеджер инициализирован. Сервер готов к работе.");
    }

    /**
     * Обработчик глобального серверного тика (END_SERVER_TICK).
     * Вызывается после обработки всех тиков в серверном цикле.
     * Обновляет состояние каждого активного игрока.
     *
     * @param server экземпляр MinecraftServer
     */
    private void onServerTick(MinecraftServer server) {
        if (activeHandlers.isEmpty()) {
            return;
        }

        // Обновляем каждого активного игрока
        for (Map.Entry<UUID, PlayerAutoHookHandler> entry : activeHandlers.entrySet()) {
            UUID playerId = entry.getKey();
            PlayerAutoHookHandler handler = entry.getValue();

            // Проверяем, онлайн ли игрок
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                // Игрок оффлайн — удаляем обработчик
                activeHandlers.remove(playerId);
                hookTracker.clearPlayer(playerId);
                if (config.debugLogging) {
                    LOGGER.debug("[AutoHook] Игрок {} оффлайн, обработчик удалён", playerId);
                }
                continue;
            }

            // Обновляем тик обработчика
            try {
                handler.tick();
            } catch (Exception e) {
                LOGGER.error("[AutoHook] Ошибка при обработке тика для игрока {}: {}",
                        player.getName().getString(), e.getMessage());
            }
        }
    }

    /**
     * Обработчик отключения игрока.
     * Очищает данные игрока из менеджера, чтобы избежать утечек памяти.
     *
     * @param playerId UUID отключившегося игрока
     */
    private void onPlayerDisconnect(UUID playerId) {
        PlayerAutoHookHandler handler = activeHandlers.remove(playerId);
        hookTracker.clearPlayer(playerId);

        if (handler != null) {
            LOGGER.info("[AutoHook] Игрок {} отключился, данные очищены.", playerId);
        }
    }

    /**
     * Включает режим автоматического крюка для игрока.
     *
     * @param player серверный игрок
     */
    public void enableForPlayer(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();

        if (activeHandlers.containsKey(playerId)) {
            LOGGER.info("[AutoHook] Режим уже включён для игрока {}", player.getName().getString());
            return;
        }

        PlayerAutoHookHandler handler = new PlayerAutoHookHandler(player, config, hookTracker);
        handler.activate();
        activeHandlers.put(playerId, handler);

        LOGGER.info("[AutoHook] Режим включён для игрока {}", player.getName().getString());
    }

    /**
     * Выключает режим автоматического крюка для игрока.
     *
     * @param player серверный игрок
     */
    public void disableForPlayer(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        PlayerAutoHookHandler handler = activeHandlers.remove(playerId);

        if (handler != null) {
            handler.deactivate();
            hookTracker.clearPlayer(playerId);
            LOGGER.info("[AutoHook] Режим выключен для игрока {}", player.getName().getString());
        } else {
            LOGGER.info("[AutoHook] Режим не был включён для игрока {}", player.getName().getString());
        }
    }

    /**
     * Переключает режим автоматического крюка для игрока.
     * Если включен — выключает, если выключен — включает.
     *
     * @param player серверный игрок
     */
    public void toggleForPlayer(ServerPlayerEntity player) {
        if (isActiveForPlayer(player)) {
            disableForPlayer(player);
        } else {
            enableForPlayer(player);
        }
    }

    /**
     * Проверяет, активен ли режим автоматического крюка для игрока.
     *
     * @param player серверный игрок
     * @return true, если режим активен
     */
    public boolean isActiveForPlayer(ServerPlayerEntity player) {
        return activeHandlers.containsKey(player.getUuid());
    }

    /**
     * Получает конфигурацию мода.
     *
     * @return экземпляр {@link Config}
     */
    public Config getConfig() {
        return config;
    }

    /**
     * Получает трейкер поплавков.
     *
     * @return экземпляр {@link HookTracker}
     */
    public HookTracker getHookTracker() {
        return hookTracker;
    }

    /**
     * Получает количество активных игроков.
     *
     * @return количество активных обработчиков
     */
    public int getActiveCount() {
        return activeHandlers.size();
    }

    /**
     * Очищает все данные (при выключении сервера).
     */
    public void shutdown() {
        activeHandlers.clear();
        hookTracker.clearAll();
        LOGGER.info("[AutoHook] Менеджер остановлен, все данные очищены.");
    }
}
