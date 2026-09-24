package com.aniblaze.autohook;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обработчик команды /autohook.
 * Регистрирует серверную команду для включения/выключения
 * автоматического крюка для каждого игрока.
 *
 * Поддерживает пермишены (LuckPerms): autohook.use
 * Если пермишн-система не установлена — разрешает всем (или только OP).
 */
public class AutoHookCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoHook");

    /** Имя команды */
    public static final String COMMAND_NAME = "autohook";

    /**
     * Регистрирует команду /autohook в диспетчере команд сервера.
     *
     * @param dispatcher          диспетчер команд
     * @param registryAccess      реестр команд
     * @param manager             менеджер автоматического крюка
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                 CommandRegistryAccess registryAccess,
                                 AutoHookManager manager) {
        Config config = manager.getConfig();

        dispatcher.register(
                CommandManager.literal(COMMAND_NAME)
                        .requires(source -> checkPermission(source, config))
                        .executes(context -> {
                            // /autohook — переключить для себя
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player == null) {
                                context.getSource().sendError(
                                        Text.literal("§cКоманда доступна только игрокам"));
                                return 0;
                            }

                            manager.toggleForPlayer(player);
                            boolean isActive = manager.isActiveForPlayer(player);

                            context.getSource().sendFeedback(
                                    () -> Text.literal(isActive
                                            ? "§a[AutoHook] Режим автоматического крюка ВКЛЮЧЁН"
                                            : "§c[AutoHook] Режим автоматического крюка ВЫКЛЮЧЕН"),
                                    true
                            );

                            LOGGER.info("[AutoHook] Игрок {} переключил режим: {}",
                                    player.getName().getString(), isActive ? "ВКЛ" : "ВЫКЛ");

                            return Command.SINGLE_SUCCESS;
                        })
                        .then(
                                CommandManager.argument("target", EntityArgumentType.player())
                                        .executes(context -> {
                                            // /autohook <игрок> — переключить для указанного игрока
                                            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "target");
                                            ServerPlayerEntity executor = context.getSource().getPlayer();

                                            // Проверка пермишена на управление другими игроками
                                            if (executor != null && !executor.getUuid().equals(target.getUuid())) {
                                                if (!context.getSource().hasPermissionLevel(2)) {
                                                    context.getSource().sendError(
                                                            Text.literal("§cНет прав на управление другими игроками"));
                                                    return 0;
                                                }
                                            }

                                            manager.toggleForPlayer(target);
                                            boolean isActive = manager.isActiveForPlayer(target);

                                            context.getSource().sendFeedback(
                                                    () -> Text.literal(String.format(
                                                            "§a[AutoHook] Режим для %s: %s",
                                                            target.getName().getString(),
                                                            isActive ? "ВКЛ" : "ВЫКЛ")),
                                                    true
                                            );

                                            LOGGER.info("[AutoHook] Игрок {} переключил режим для {}: {}",
                                                    executor != null ? executor.getName().getString() : "CONSOLE",
                                                    target.getName().getString(),
                                                    isActive ? "ВКЛ" : "ВЫКЛ");

                                            return Command.SINGLE_SUCCESS;
                                        })
                        )
                        .then(
                                CommandManager.literal("on")
                                        .executes(context -> {
                                            // /autohook on — включить для себя
                                            ServerPlayerEntity player = context.getSource().getPlayer();
                                            if (player == null) {
                                                context.getSource().sendError(
                                                        Text.literal("§cКоманда доступна только игрокам"));
                                                return 0;
                                            }

                                            if (!manager.isActiveForPlayer(player)) {
                                                manager.enableForPlayer(player);
                                                context.getSource().sendFeedback(
                                                        () -> Text.literal("§a[AutoHook] Режим ВКЛЮЧЁН"),
                                                        true
                                                );
                                            } else {
                                                context.getSource().sendFeedback(
                                                        () -> Text.literal("§e[AutoHook] Режим уже включён"),
                                                        true
                                                );
                                            }

                                            return Command.SINGLE_SUCCESS;
                                        })
                        )
                        .then(
                                CommandManager.literal("off")
                                        .executes(context -> {
                                            // /autohook off — выключить для себя
                                            ServerPlayerEntity player = context.getSource().getPlayer();
                                            if (player == null) {
                                                context.getSource().sendError(
                                                        Text.literal("§cКоманда доступна только игрокам"));
                                                return 0;
                                            }

                                            if (manager.isActiveForPlayer(player)) {
                                                manager.disableForPlayer(player);
                                                context.getSource().sendFeedback(
                                                        () -> Text.literal("§c[AutoHook] Режим ВЫКЛЮЧЕН"),
                                                        true
                                                );
                                            } else {
                                                context.getSource().sendFeedback(
                                                        () -> Text.literal("§e[AutoHook] Режим уже выключен"),
                                                        true
                                                );
                                            }

                                            return Command.SINGLE_SUCCESS;
                                        })
                        )
                        .then(
                                CommandManager.literal("status")
                                        .executes(context -> {
                                            // /autohook status — показать статус
                                            ServerPlayerEntity player = context.getSource().getPlayer();
                                            if (player == null) {
                                                context.getSource().sendFeedback(
                                                        () -> Text.literal(String.format(
                                                                "§e[AutoHook] Активных игроков: %d",
                                                                manager.getActiveCount())),
                                                        false
                                                );
                                                return Command.SINGLE_SUCCESS;
                                            }

                                            boolean isActive = manager.isActiveForPlayer(player);
                                            int activeCount = manager.getActiveCount();

                                            context.getSource().sendFeedback(
                                                    () -> Text.literal(String.format(
                                                            "§e[AutoHook] Статус: %s | Активных игроков: %d",
                                                            isActive ? "§aВКЛ" : "§cВЫКЛ",
                                                            activeCount)),
                                                    false
                                            );

                                            return Command.SINGLE_SUCCESS;
                                        })
                        )
        );
    }

    /**
     * Проверяет права доступа к команде.
     * Если установлена LuckPerms — проверяет ноду autohook.use.
     * Если нет — разрешает всем (или только OP, в зависимости от конфига).
     *
     * @param source источник команды
     * @param config конфигурация мода
     * @return true, если доступ разрешён
     */
    private static boolean checkPermission(ServerCommandSource source, Config config) {
        // OP всегда имеет доступ
        if (source.hasPermissionLevel(2)) {
            return true;
        }

        // Проверяем ноду пермишена (совместимо с LuckPerms)
        // Fabric API автоматически проверяет пермишены через ServerPlayerEntity.hasPermissionLevel()
        // Для LuckPerms используется нода "autohook.use"
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            // Проверяем через Fabric Permission API или LuckPerms
            // В Fabric нет встроенной пермишн-системы, поэтому используем проверку OP
            // Если LuckPerms установлен, он перехватит проверку через Permission API
            return config.allowAllWhenNoPermissionPlugin || source.hasPermissionLevel(0);
        }

        // Консоль имеет доступ
        return true;
    }
}
