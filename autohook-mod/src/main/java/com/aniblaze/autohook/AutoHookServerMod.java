package com.aniblaze.autohook;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Главный класс-инициализатор мода AutoHook.
 * Работает исключительно на стороне DEDICATED_SERVER.
 * Регистрирует команды, обработчики событий и менеджер.
 *
 * Mod ID: autohook
 * Версия: 1.0.0
 * Окружение: SERVER ONLY
 */
public class AutoHookServerMod implements ModInitializer {

    /** Идентификатор мода */
    public static final String MOD_ID = "autohook";

    /** Логгер мода */
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoHook");

    /** Конфигурация мода */
    private Config config;

    /** Менеджер автоматического крюка */
    private AutoHookManager manager;

    /**
     * Метод инициализации мода.
     * Вызывается Fabric Loader при загрузке мода на сервере.
     *
     * Важно: Этот метод выполняется ДО старта сервера, поэтому
     * регистрация команд происходит через CommandRegistrationCallback.
     */
    @Override
    public void onInitialize() {
        LOGGER.info("[AutoHook] Инициализация мода AutoHook v1.0.0...");

        // Загружаем конфигурацию
        config = Config.loadDefault();

        // Проверяем, что мы на выделенном сервере (не на клиенте)
        if (FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT) {
            LOGGER.warn("[AutoHook] Мод загружен на клиенте! Этот мод предназначен только для серверов.");
            return;
        }

        // Регистрируем обработчик команд
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            manager = AutoHookManager.getInstance(config);
            AutoHookCommand.register(dispatcher, registryAccess, manager);
            LOGGER.info("[AutoHook] Команда /autohook зарегистрирована.");
        });

        // Регистрируем обработчик старта сервера
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);

        // Регистрируем обработчик остановки сервера
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        LOGGER.info("[AutoHook] Мод AutoHook успешно инициализирован.");
    }

    /**
     * Обработчик старта сервера.
     * Инициализирует менеджер и регистрирует обработчики тиков.
     *
     * @param server экземпляр MinecraftServer
     */
    private void onServerStarting(MinecraftServer server) {
        LOGGER.info("[AutoHook] Сервер запускается, инициализация менеджера...");

        if (manager == null) {
            manager = AutoHookManager.getInstance(config);
        }

        manager.initialize(server);

        LOGGER.info("[AutoHook] Сервер готов. Менеджер обрабатывает {} активных игроков.",
                manager.getActiveCount());
    }

    /**
     * Обработчик остановки сервера.
     * Очищает все данные менеджера.
     *
     * @param server экземпляр MinecraftServer
     */
    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("[AutoHook] Сервер останавливается, очистка данных...");

        if (manager != null) {
            manager.shutdown();
        }

        LOGGER.info("[AutoHook] Мод AutoHook остановлен.");
    }
}
