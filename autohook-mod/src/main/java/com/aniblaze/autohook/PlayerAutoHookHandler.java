package com.aniblaze.autohook;

import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обработчик автоматического крюка для конкретного игрока.
 * Реализует конечный автомат (StateMachine) с состояниями:
 * IDLE -> SEARCH -> AIM -> CAST -> WAIT_HOOK -> PULL -> COOLDOWN -> IDLE
 *
 * Все операции выполняются в серверном потоке, блокировки отсутствуют.
 */
public class PlayerAutoHookHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoHook");

    /** Состояния конечного автомата */
    public enum State {
        /** Ожидание активации (режим выключен или кулдаун) */
        IDLE,
        /** Поиск цели лучом (raycast) от глаз игрока */
        SEARCH,
        /** Плавный поворот камеры к найденной цели */
        AIM,
        /** Бросок удочки (имитация правого клика) */
        CAST,
        /** Ожидание зацепа поплавка */
        WAIT_HOOK,
        /** Притягивание — повторный клик для рывка */
        PULL,
        /** Задержка перед следующим циклом */
        COOLDOWN
    }

    /** Текущее состояние конечного автомата */
    private State currentState = State.IDLE;

    /** UUID игрока */
    private final ServerPlayerEntity player;

    /** Конфигурация мода */
    private final Config config;

    /** Трейкер поплавков */
    private final HookTracker hookTracker;

    /** Целевая позиция для притягивания (точка зацепа) */
    private Vec3d targetPosition;

    /** Целевые углы поворота (yaw, pitch) */
    private float targetYaw;
    private float targetPitch;

    /** Счётчик тиков для таймеров */
    private int tickCounter = 0;

    /** Флаг: был ли уже брошен поплавок в текущем цикле */
    private boolean hookCast = false;

    /** Флаг: ручной режим удочки (игрок уже использует удочку вручную) */
    private boolean manualFishingDetected = false;

    /**
     * Создаёт обработчик для указанного игрока.
     *
     * @param player     серверный игрок
     * @param config     конфигурация мода
     * @param hookTracker трейкер поплавков
     */
    public PlayerAutoHookHandler(ServerPlayerEntity player, Config config, HookTracker hookTracker) {
        this.player = player;
        this.config = config;
        this.hookTracker = hookTracker;
    }

    /**
     * Получает текущее состояние конечного автомата.
     *
     * @return текущее состояние
     */
    public State getCurrentState() {
        return currentState;
    }

    /**
     * Активирует режим автоматического крюка.
     * Переходит в состояние SEARCH.
     */
    public void activate() {
        if (currentState == State.IDLE) {
            currentState = State.SEARCH;
            tickCounter = 0;
            hookCast = false;
            manualFishingDetected = false;
            LOGGER.debug("[AutoHook] Игрок {} активировал автоматический крюк", player.getName().getString());
        }
    }

    /**
     * Деактивирует режим автоматического крюка.
     * Возвращает в состояние IDLE и очищает данные.
     */
    public void deactivate() {
        currentState = State.IDLE;
        targetPosition = null;
        tickCounter = 0;
        hookCast = false;
        manualFishingDetected = false;
        hookTracker.clearPlayer(player.getUuid());
        LOGGER.debug("[AutoHook] Игрок {} деактивировал автоматический крюк", player.getName().getString());
    }

    /**
     * Главный метод обработки тика. Вызывается каждый серверный тик для игрока.
     * Обновляет конечный автомат в зависимости от текущего состояния.
     */
    public void tick() {
        if (currentState == State.IDLE) {
            return;
        }

        // Проверяем, держит ли игрок удочку в основной руке
        ItemStack mainHand = player.getMainHandStack();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof FishingRodItem)) {
            if (config.debugLogging) {
                LOGGER.debug("[AutoHook] Игрок {} не держит удочку — пропуск", player.getName().getString());
            }
            return;
        }

        // Проверяем, не использует ли игрок удочку вручную (есть активный поплавок, но не наш)
        if (!hookCast && hookTracker.hasHook(player.getUuid())) {
            manualFishingDetected = true;
            if (config.debugLogging) {
                LOGGER.debug("[AutoHook] Игрок {} уже использует удочку вручную — пропуск", player.getName().getString());
            }
            return;
        }

        tickCounter++;

        switch (currentState) {
            case SEARCH -> handleSearch();
            case AIM -> handleAim();
            case CAST -> handleCast();
            case WAIT_HOOK -> handleWaitHook();
            case PULL -> handlePull();
            case COOLDOWN -> handleCooldown();
            default -> {}
        }
    }

    /**
     * Обработка состояния SEARCH: поиск цели лучом (raycast) от глаз игрока.
     */
    private void handleSearch() {
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);

        BlockHitResult hitResult = player.getWorld().raycast(
                new RaycastContext(
                        eyePos,
                        eyePos.add(lookVec.multiply(config.raycastDistance)),
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE,
                        player
                )
        );

        if (hitResult.getType() == HitResult.Type.MISS) {
            // Нет цели — возвращаемся в IDLE
            if (config.debugLogging) {
                LOGGER.debug("[AutoHook] Игрок {}: цель не найдена, остановка", player.getName().getString());
            }
            currentState = State.IDLE;
            return;
        }

        targetPosition = hitResult.getPos();
        Vec3d rotation = RotationHelper.calculateRotation(eyePos, targetPosition);
        targetYaw = rotation[0];
        targetPitch = rotation[1];

        // Проверяем, нужно ли поворачиваться
        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();

        if (RotationHelper.isRotationClose(currentYaw, currentPitch, targetYaw, targetPitch,
                (float) config.aimToleranceDegrees)) {
            // Уже смотрим на цель — переходим к броску
            currentState = State.CAST;
            tickCounter = 0;
        } else {
            // Нужен поворот
            currentState = State.AIM;
            tickCounter = 0;
        }

        if (config.debugLogging) {
            LOGGER.debug("[AutoHook] Игрок {}: цель найдена в ({}, {}, {}), yaw={}, pitch={}",
                    player.getName().getString(),
                    targetPosition.x, targetPosition.y, targetPosition.z,
                    targetYaw, targetPitch);
        }
    }

    /**
     * Обработка состояния AIM: плавный поворот камеры к найденной цели.
     */
    private void handleAim() {
        if (targetPosition == null) {
            currentState = State.SEARCH;
            tickCounter = 0;
            return;
        }

        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();

        float newYaw = RotationHelper.interpolateYaw(currentYaw, targetYaw, (float) config.aimSpeedDegrees);
        float newPitch = RotationHelper.interpolatePitch(currentPitch, targetPitch, (float) config.aimSpeedDegrees);

        RotationHelper.applyRotation(player, newYaw, newPitch);

        if (RotationHelper.isRotationClose(newYaw, newPitch, targetYaw, targetPitch,
                (float) config.aimToleranceDegrees)) {
            currentState = State.CAST;
            tickCounter = 0;
            if (config.debugLogging) {
                LOGGER.debug("[AutoHook] Игрок {}: поворот завершён, переход к броску", player.getName().getString());
            }
        }
    }

    /**
     * Обработка состояния CAST: бросок удочки (имитация использования предмета).
     */
    private void handleCast() {
        if (!hookCast) {
            // Имитируем использование удочки (правый клик)
            simulateUseItem();
            hookCast = true;
            tickCounter = 0;

            // Регистрируем поплавок через небольшую задержку (1 тик)
            // так как поплавок создаётся асинхронно
            currentState = State.WAIT_HOOK;
            tickCounter = 0;

            if (config.debugLogging) {
                LOGGER.debug("[AutoHook] Игрок {}: удочка брошена", player.getName().getString());
            }
        }
    }

    /**
     * Обработка состояния WAIT_HOOK: ожидание зацепа поплавка.
     */
    private void handleWaitHook() {
        // Проверяем таймаут
        if (tickCounter > config.waitHookTimeoutTicks) {
            if (config.debugLogging) {
                LOGGER.debug("[AutoHook] Игрок {}: таймаут ожидания зацепа", player.getName().getString());
            }
            // Возвращаемся в SEARCH для нового цикла
            hookCast = false;
            hookTracker.clearPlayer(player.getUuid());
            currentState = State.SEARCH;
            tickCounter = 0;
            return;
        }

        // Пытаемся найти поплавок, если он ещё не зарегистрирован
        FishingBobberEntity bobber = hookTracker.getHook(player.getUuid());
        if (bobber == null || bobber.isRemoved()) {
            // Ищем поплавок рядом с игроком
            findAndRegisterBobber();
            return;
        }

        // Проверяем, зацепился ли поплавок
        if (hookTracker.isHooked(bobber)) {
            currentState = State.PULL;
            tickCounter = 0;
            if (config.debugLogging) {
                LOGGER.debug("[AutoHook] Игрок {}: поплавок зацепился, начинаю притягивание",
                        player.getName().getString());
            }
        }
    }

    /**
     * Обработка состояния PULL: притягивание игрока к точке зацепа.
     * Повторно использует удочку для активации рывка.
     */
    private void handlePull() {
        if (tickCounter >= config.pullDelayTicks) {
            // Повторно используем удочку для притягивания
            simulateUseItem();

            // После притягивания переходим в кулдаун
            currentState = State.COOLDOWN;
            tickCounter = 0;
            hookCast = false;
            hookTracker.clearPlayer(player.getUuid());

            if (config.debugLogging) {
                LOGGER.debug("[AutoHook] Игрок {}: притягивание выполнено, кулдаун",
                        player.getName().getString());
            }
        }
    }

    /**
     * Обработка состояния COOLDOWN: задержка перед следующим циклом.
     */
    private void handleCooldown() {
        if (tickCounter >= config.cooldownTicks) {
            currentState = State.SEARCH;
            tickCounter = 0;
            if (config.debugLogging) {
                LOGGER.debug("[AutoHook] Игрок {}: кулдаун завершён, новый поиск",
                        player.getName().getString());
            }
        }
    }

    /**
     * Имитирует использование предмета в основной руке (правый клик).
     * Вызывает interactItem через серверную обработку.
     */
    private void simulateUseItem() {
        player.interact(Hand.MAIN_HAND);
    }

    /**
     * Ищет поплавок {@link FishingBobberEntity} рядом с игроком и регистрирует его.
     */
    private void findAndRegisterBobber() {
        player.getWorld().getEntitiesByClass(
                FishingBobberEntity.class,
                player.getBoundingBox().expand(16.0),
                bobber -> bobber.getOwner() == player
        ).stream().findFirst().ifPresent(bobber -> {
            hookTracker.registerHook(player.getUuid(), bobber);
            if (config.debugLogging) {
                LOGGER.debug("[AutoHook] Игрок {}: поплавок найден и зарегистрирован",
                        player.getName().getString());
            }
        });
    }

    /**
     * Проверяет, активен ли обработчик (не в состоянии IDLE).
     *
     * @return true, если обработчик активен
     */
    public boolean isActive() {
        return currentState != State.IDLE;
    }

    /**
     * Проверяет, был ли обнаружен ручной режим удочки.
     *
     * @return true, если игрок использует удочку вручную
     */
    public boolean isManualFishingDetected() {
        return manualFishingDetected;
    }
}
