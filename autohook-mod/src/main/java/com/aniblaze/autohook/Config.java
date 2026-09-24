package com.aniblaze.autohook;

/**
 * Конфигурация мода AutoHook.
 * Хранит все настройки: дистанцию рейкаста, таймеры, скорость поворота, пермишены.
 * Настройки загружаются/сохраняются в JSON-файл в директории мира сервера.
 */
public final class Config {

    /** Максимальная дистанция рейкаста (в блоках) для поиска цели лучом */
    public double raycastDistance = 64.0;

    /** Скорость плавного поворота камеры (градусы за тик) */
    public double aimSpeedDegrees = 8.0;

    /** Допустимая погрешность поворота (в градусах), при которой поворот считается завершённым */
    public double aimToleranceDegrees = 2.0;

    /** Задержка между циклами (в тиках) после завершения притягивания */
    public int cooldownTicks = 10;

    /** Максимальное время ожидания зацепа поплавка (в тиках) */
    public int waitHookTimeoutTicks = 100;

    /** Задержка перед повторным кликом для притягивания (в тиках) после зацепа */
    public int pullDelayTicks = 3;

    /** Разрешение (нода) для использования команды /autohook (совместимо с LuckPerms) */
    public String permissionNode = "autohook.use";

    /** Разрешать ли команду всем, если пермишн-система не установлена */
    public boolean allowAllWhenNoPermissionPlugin = true;

    /** Включить ли подробное логирование (DEBUG уровень) */
    public boolean debugLogging = false;

    /** Минимальная дистанция от игрока до поплавка, при которой притягивание активируется */
    public double minPullDistance = 2.0;

    /**
     * Создаёт конфигурацию с значениями по умолчанию.
     */
    public Config() {
    }

    /**
     * Возвращает单例-экземпляр конфигурации.
     * В будущем можно заменить на загрузку из JSON-файла.
     *
     * @return экземпляр {@link Config}
     */
    public static Config loadDefault() {
        return new Config();
    }
}
