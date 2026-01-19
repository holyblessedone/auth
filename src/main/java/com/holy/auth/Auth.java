package com.holy.auth;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod(Auth.MODID)
public class Auth {

    public static final String MODID = "auth";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static AuthManager authManager;


    public Auth() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        authManager = new AuthManager(event.getServer());
        authManager.loadData();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (authManager != null) {
            authManager.saveData();
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String playerName = player.getName().getString();
        String ip = getPlayerIP(player);

        if (!authManager.isRegistered(playerName)) {
            player.sendSystemMessage(Component.literal("Вы не зарегистрированы! Используйте /register <пароль>").withStyle(ChatFormatting.RED));
            setPlayerAuthState(player, true);
            return;
        }

        String savedIp = authManager.getIP(playerName);

        if (savedIp != null && savedIp.equals(ip)) {
            player.sendSystemMessage(Component.literal("Вы авторизованы автоматически."));
            setPlayerAuthState(player, false);
            authManager.logLogin(playerName, ip);
        } else {
            player.sendSystemMessage(Component.literal("Введите /login <пароль> для авторизации"));
            setPlayerAuthState(player, true);
        }
    }

    // Запрет движения для неавторизованных
    private static final Set<UUID> notAuthenticated = new HashSet<>();

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.player.level().isClientSide || event.phase != TickEvent.Phase.START) return;

        ServerPlayer player = (ServerPlayer) event.player;
        if (notAuthenticated.contains(player.getUUID())) {
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();

            float yaw = 0f;   // фиксируем, например, первоначальный поворот
            float pitch = 0f;

            // Телепортируем игрока на то же место с фиксированным поворотом
            player.connection.teleport(x, y, z, yaw, pitch);

            // Дополнительно сбрасываем скорость движения
            player.setDeltaMovement(0, 0, 0);
        }
    }

    private void setPlayerAuthState(ServerPlayer player, boolean needAuth) {
        if (needAuth) {
            notAuthenticated.add(player.getUUID());
            player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        } else {
            notAuthenticated.remove(player.getUUID());
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        }
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        // /register <password>
        event.getDispatcher().register(literal("register")
                .then(argument("password", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String password = StringArgumentType.getString(ctx, "password");
                            String playerName = player.getName().getString();
                            String ip = getPlayerIP(player);

                            if (authManager.isRegistered(playerName)) {
                                ctx.getSource().sendFailure(Component.literal("Вы уже зарегистрированы!"));
                                return 0;
                            }

                            authManager.register(playerName, password, ip);
                            player.sendSystemMessage(Component.literal("Регистрация успешна! Вы авторизованы.\nНе забывайте ваш пароль!!"));
                            setPlayerAuthState(player, false);
                            authManager.logLogin(playerName, ip);
                            return 1;
                        })));

        // /login <password>
        event.getDispatcher().register(literal("login")
                .then(argument("password", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String password = StringArgumentType.getString(ctx, "password");
                            String playerName = player.getName().getString();
                            String ip = getPlayerIP(player);

                            if (!authManager.isRegistered(playerName)) {
                                ctx.getSource().sendFailure(Component.literal("Вы не зарегистрированы. Используйте /register <пароль>").withStyle(ChatFormatting.RED));
                                return 0;
                            }

                            if (authManager.checkPassword(playerName, password)) {
                                authManager.updateIP(playerName, ip);
                                player.sendSystemMessage(Component.literal("Вы успешно вошли!"));
                                setPlayerAuthState(player, false);
                                authManager.logLogin(playerName, ip);
                                return 1;
                            } else {
                                ctx.getSource().sendFailure(Component.literal("Неверный пароль!").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                        })));

        // /auth remove <nickname>
        event.getDispatcher().register(literal("auth")
                .then(literal("remove")
                        .then(argument("nickname", StringArgumentType.word())
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    String targetName = StringArgumentType.getString(ctx, "nickname");
                                    if (!source.hasPermission(3)) {
                                        source.sendFailure(Component.literal("Недостаточно прав!").withStyle(ChatFormatting.RED));
                                        return 0;
                                    }

                                    if (!authManager.isRegistered(targetName)) {
                                        source.sendFailure(Component.literal("Игрок не найден или не зарегистрирован.").withStyle(ChatFormatting.RED));
                                        return 0;
                                    }

                                    authManager.remove(targetName);
                                    source.sendSuccess(() -> Component.literal("Данные игрока " + targetName + " удалены."), true);
                                    return 1;
                                }))));
    }

    private static String getPlayerIP(ServerPlayer player) {
        try {
            SocketAddress addr = player.connection.getRemoteAddress();
            if (addr instanceof InetSocketAddress) {
                InetSocketAddress inet = (InetSocketAddress) addr;
                return inet.getAddress().getHostAddress();
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}

class AuthManager {
    private static final String DIR_NAME = "Auth";
    private static final String FILE_NAME = "Auth.json";
    private static final String LOG_FILE_NAME = "logs.txt";

    private final Path authDir;
    private final Path dataFile;
    private final Path logFile;

    private final MinecraftServer server;
    private Map<String, PlayerAuthData> playerData = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public AuthManager(MinecraftServer server) {
        this.server = server;
        this.authDir = server.getWorldPath(new LevelResource(DIR_NAME));
        this.dataFile = authDir.resolve(FILE_NAME);
        this.logFile = authDir.resolve(LOG_FILE_NAME);
    }

    public void loadData() {
        try {
            if (!Files.exists(authDir)) {
                Files.createDirectories(authDir);
            }
            if (!Files.exists(dataFile)) {
                Files.createFile(dataFile);
                saveData(); // пустой файл
            } else {
                Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8);
                JsonElement jsonElement = JsonParser.parseReader(reader);
                reader.close();

                if (jsonElement.isJsonArray()) {
                    JsonArray array = jsonElement.getAsJsonArray();
                    playerData.clear();
                    for (JsonElement el : array) {
                        PlayerAuthData data = gson.fromJson(el, PlayerAuthData.class);
                        playerData.put(data.player.toLowerCase(Locale.ROOT), data);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void saveData() {
        try {
            JsonArray array = new JsonArray();
            for (PlayerAuthData data : playerData.values()) {
                JsonElement el = gson.toJsonTree(data);
                array.add(el);
            }
            Writer writer = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            gson.toJson(array, writer);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isRegistered(String playerName) {
        return playerData.containsKey(playerName.toLowerCase(Locale.ROOT));
    }

    public String getIP(String playerName) {
        PlayerAuthData data = playerData.get(playerName.toLowerCase(Locale.ROOT));
        return data == null ? null : data.ip;
    }

    public boolean checkPassword(String playerName, String password) {
        PlayerAuthData data = playerData.get(playerName.toLowerCase(Locale.ROOT));
        if (data == null) return false;
        return data.password.equals(password);
    }

    public void register(String playerName, String password, String ip) {
        PlayerAuthData data = new PlayerAuthData(playerName, ip, password);
        playerData.put(playerName.toLowerCase(Locale.ROOT), data);
        saveData();
    }

    public void updateIP(String playerName, String newIp) {
        PlayerAuthData data = playerData.get(playerName.toLowerCase(Locale.ROOT));
        if (data != null) {
            data.ip = newIp;
            saveData();
        }
    }

    public void remove(String playerName) {
        playerData.remove(playerName.toLowerCase(Locale.ROOT));
        saveData();
    }

    public void logLogin(String playerName, String ip) {
        try {
            if (!Files.exists(authDir)) {
                Files.createDirectories(authDir);
            }
            String datetime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String logLine = datetime + " - " + playerName + " вошёл с IP: " + ip + System.lineSeparator();
            Files.write(logFile, logLine.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class PlayerAuthData {
        String player;
        String ip;
        String password;

        public PlayerAuthData(String player, String ip, String password) {
            this.player = player;
            this.ip = ip;
            this.password = password;
        }
    }
}
