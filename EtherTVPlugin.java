package ru.ethertv;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.block.TileState;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.entity.Player;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.Location;
import ru.ethertv.television.TelevisionScreenService;
import ru.ethertv.network.BroadcastNetworkService;
import ru.ethertv.network.ClientBridgeService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.function.BiConsumer;
import org.bukkit.scheduler.BukkitTask;
import ru.ethertv.camera.RecordingSession;
import ru.ethertv.renderer.FrameEncoder;
import ru.ethertv.voice.EtherVoicePlugin;
import space.subkek.customdiscs.api.CustomDiscsAPI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EtherTVPlugin extends JavaPlugin implements Listener {
    private NamespacedKey tvItemKey, tvBlockKey, tvDisplayKey, cameraItemKey, cameraBlockKey, recordingIdKey;
    private NamespacedKey customDiscLocalKey, customDiscRemoteKey;
    private final Map<String, RecordingSession> recordings = new ConcurrentHashMap<>();
    private final Set<UUID> microphoneConsent = ConcurrentHashMap.newKeySet();
    private VoicechatApi voicechatApi;
    private TelevisionScreenService screenService;
    private ru.ethertv.television.TelevisionControlService televisionControl;
    private BroadcastNetworkService networkService;
    private ClientBridgeService clientBridge;
    private FrameEncoder youtubeFrameEncoder;
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        tvItemKey = new NamespacedKey(this, "tv_item");
        tvBlockKey = new NamespacedKey(this, "tv_block");
        tvDisplayKey = new NamespacedKey(this, "tv_display_uuid");
        cameraItemKey = new NamespacedKey(this, "camera_item");
        cameraBlockKey = new NamespacedKey(this, "camera_block");
        recordingIdKey = new NamespacedKey(this, "recording_id");
        customDiscLocalKey = new NamespacedKey("customdiscs", "local");
        customDiscRemoteKey = new NamespacedKey("customdiscs", "remote");
        loadConsent();
        screenService = new TelevisionScreenService(this);
        televisionControl = new ru.ethertv.television.TelevisionControlService(this);
        youtubeFrameEncoder = new FrameEncoder(this);
        networkService = new BroadcastNetworkService(this);
        clientBridge = new ClientBridgeService(this);
        Bukkit.getPluginManager().registerEvents(new ru.ethertv.television.CustomDiscTelevisionService(this, clientBridge), this);
        Bukkit.getPluginManager().registerEvents(this, this);
        registerPermission("ethertv.admin", PermissionDefault.OP);
        registerPermission("ethertv.use", PermissionDefault.TRUE);
        registerEtherCommand("ether", (source, args) -> handleCommand(source.getSender(), args));
        if (getConfig().getBoolean("television.recipe-enabled", true)) registerTelevisionRecipe();
        if (getConfig().getBoolean("camera.recipe-enabled", true)) registerCameraRecipe();
        BukkitVoicechatService voiceService = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (voiceService == null) {
            getLogger().severe("Simple Voice Chat API not found. Microphone recording will be unavailable.");
        } else {
            voiceService.registerPlugin(new EtherVoicePlugin(this));
        }
        getLogger().info("EtherTV 1.3.0-dev enabled. Fifteen persistent channels and direct 3D radio-range controls are available; validate on a test server.");
    }

    @Override
    public void onDisable() {
        recordings.values().forEach(s -> s.stop("server shutdown"));
        recordings.clear();
        if (screenService != null) screenService.close();
        if (youtubeFrameEncoder != null) youtubeFrameEncoder.close();
        if (clientBridge != null) clientBridge.close();
        saveConsent();
        Bukkit.removeRecipe(new NamespacedKey(this, "et1_recipe"));
        Bukkit.removeRecipe(new NamespacedKey(this, "ek1_recipe"));
    }

    public void setVoicechatApi(VoicechatApi api) { this.voicechatApi = api; }

    private ItemStack createTaggedItem(Material material, String name, NamespacedKey key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD));
        if (key.equals(cameraItemKey)) {
            meta.setItemModel(new NamespacedKey(this, "camera"));
        }
        if (key.equals(tvItemKey)) {
            meta.setItemModel(new NamespacedKey(this, "television"));
        }
        meta.lore(java.util.List.of(Component.text("Ether TV", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void registerTelevisionRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(this, "et1_recipe");
        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey,
                createTaggedItem(Material.JUKEBOX, getConfig().getString("television.display-name", "Телевизор ЭТ-1"), tvItemKey));
        // Corrected to the player's stated layout: top-centre jukebox; middle copper + two redstone;
        // bottom iron + lightning rod + iron.
        recipe.shape(" J ", "CRR", "ILI");
        recipe.setIngredient('J', Material.JUKEBOX);
        recipe.setIngredient('C', Material.COPPER_BLOCK);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('L', Material.LIGHTNING_ROD);
        Bukkit.addRecipe(recipe);
    }

    private void registerCameraRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(this, "ek1_recipe");
        Bukkit.removeRecipe(recipeKey);
        ShapedRecipe recipe = new ShapedRecipe(recipeKey,
                createTaggedItem(Material.DISPENSER, getConfig().getString("camera.display-name", "Видеокамера ЭК-1"), cameraItemKey));
        // Dispenser body, spyglass optics, redstone control and metal housing.
        recipe.shape(" I ", "RDS", " C ");
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('D', Material.DISPENSER);
        recipe.setIngredient('S', Material.SPYGLASS);
        recipe.setIngredient('C', Material.COPPER_INGOT);
        Bukkit.addRecipe(recipe);
    }

    /**
     * Registers the /ether command through Paper's lifecycle command API.
     * Paper plugins do not obtain commands from JavaPlugin#getCommand unless
     * they are declared in a legacy plugin.yml descriptor.
     */
    private void registerEtherCommand(String name, BiConsumer<CommandSourceStack, String[]> executor) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(name, new BasicCommand() {
                    @Override
                    public void execute(CommandSourceStack source, String[] args) {
                        executor.accept(source, args);
                    }
                })
        );
    }

    private void registerPermission(String name, PermissionDefault defaultValue) {
        if (Bukkit.getPluginManager().getPermission(name) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(name, defaultValue));
        }
    }

    private boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("consent")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Команда доступна игроку."); return true; }
            if (microphoneConsent.remove(player.getUniqueId())) {
                player.sendMessage(Component.text("Запись вашего микрофона отключена.", NamedTextColor.YELLOW));
            } else {
                microphoneConsent.add(player.getUniqueId());
                player.sendMessage(Component.text("Вы разрешили запись вашего микрофона камерами Ether TV. Отключить: /ether consent", NamedTextColor.GREEN));
            }
            saveConsent();
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("ethertv.admin")) { sender.sendMessage("Недостаточно прав."); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage("Команду выдачи выполняет игрок."); return true; }
            ItemStack item = switch (args[1].toLowerCase()) {
                case "tv" -> createTaggedItem(Material.JUKEBOX, getConfig().getString("television.display-name", "Телевизор ЭТ-1"), tvItemKey);
                case "camera" -> createTaggedItem(Material.DISPENSER, getConfig().getString("camera.display-name", "Видеокамера ЭК-1"), cameraItemKey);
                default -> null;
            };
            if (item == null) { sender.sendMessage("Использование: /ether give <tv|camera>"); return true; }
            player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            player.sendMessage(Component.text("Предмет выдан.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Команда доступна игроку."); return true; }
            Block target = player.getTargetBlockExact(6);
            if (target == null || !isTelevision(target)) { player.sendMessage("Посмотри на телевизор ЭТ-1."); return true; }
            int rods = televisionControl.radius(target) / 1000;
            player.sendMessage(Component.text("ЭТ-1 | канал " + televisionControl.channel(target) + "/15 | режим " + (televisionControl.isTransmitter(target) ? "ПЕРЕДАТЧИК" : "ПРИЁМНИК") + " | громоотводов непосредственно сверху: " + rods + " | радиус: " + televisionControl.radius(target) + " блоков", NamedTextColor.AQUA));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("mode")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Команда доступна игроку."); return true; }
            Block target = player.getTargetBlockExact(6);
            if (target == null || !isTelevision(target)) { player.sendMessage("Посмотри на телевизор ЭТ-1."); return true; }
            boolean transmitter = televisionControl.toggleMode(target);
            player.sendMessage(Component.text(transmitter ? "Режим: ПЕРЕДАТЧИК. Собственная передача активна только при наличии источника." : "Режим: ПРИЁМНИК. Собственная передача отключена.", transmitter ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            return true;
        }
        if ((args.length == 2 || args.length == 3) && args[0].equalsIgnoreCase("screen")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Команда доступна игроку."); return true; }
            if (!player.hasPermission("ethertv.use")) { player.sendMessage("Недостаточно прав."); return true; }
            if (args[1].equalsIgnoreCase("map")) {
                ItemStack map = screenService.createScreenMap(player.getWorld());
                player.getInventory().addItem(map).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                player.sendMessage(Component.text("Карта-экран создана. Размести карту в рамке; несколько рамок можно собрать в прямоугольник до 4×4.", NamedTextColor.GREEN));
                return true;
            }
            if (args[1].equalsIgnoreCase("bind")) {
                Block television = player.getTargetBlockExact(6);
                if (television == null || !isTelevision(television)) {
                    player.sendMessage(Component.text("Посмотри на установленный телевизор ЭТ-1.", NamedTextColor.RED));
                    return true;
                }
                if (args.length == 3) {
                    String channel = args[2].toLowerCase(java.util.Locale.ROOT);
                    Path selected = findLatestRecordingForChannel(channel);
                    if (selected == null) {
                        player.sendMessage(Component.text("Для канала нет сохранённой записи: " + channel, NamedTextColor.RED));
                        return true;
                    }
                    var meta = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(selected.resolve("broadcast.yml").toFile());
                    World sourceWorld;
                    try { sourceWorld = Bukkit.getWorld(UUID.fromString(meta.getString("world", ""))); }
                    catch (IllegalArgumentException invalidMetadata) { sourceWorld = null; }
                    if (sourceWorld == null || !sourceWorld.equals(television.getWorld())) {
                        player.sendMessage(Component.text("Источник канала находится в другом мире или мир не загружен.", NamedTextColor.RED));
                        return true;
                    }
                    Block source = sourceWorld.getBlockAt(meta.getInt("x"), meta.getInt("y"), meta.getInt("z"));
                    if (!networkService.isConnected(source, television, channel)) {
                        player.sendMessage(Component.text("Нет прямого сигнала. Установи громоотводы непосредственно над телевизором-передатчиком; канал: " + channel, NamedTextColor.RED));
                        return true;
                    }
                    boolean bound = screenService.bindNearbyFrame(television, selected.resolve("frames"),
                            getConfig().getLong("television.playback-frame-interval-ms", 1000L), true);
                    player.sendMessage(Component.text(bound ? "Телевизор настроен на архивный канал " + channel + "." : "Не найдена рамка с картой-экраном.", bound ? NamedTextColor.GREEN : NamedTextColor.RED));
                    return true;
                }
                RecordingSession nearest = recordings.values().stream()
                        .filter(session -> session.getCamera().getWorld().equals(television.getWorld()))
                        .filter(session -> session.getCamera().getLocation().distanceSquared(television.getLocation()) <= 64.0 * 64.0)
                        .min(java.util.Comparator.comparingDouble(session -> session.getCamera().getLocation().distanceSquared(television.getLocation())))
                        .orElse(null);
                if (nearest == null) {
                    player.sendMessage(Component.text("Нет активной записи камеры ЭК-1 поблизости.", NamedTextColor.RED));
                    return true;
                }
                boolean bound = screenService.bindNearbyFrame(television, nearest.getDirectory().resolve("frames"),
                        getConfig().getLong("television.playback-frame-interval-ms", 1000L));
                player.sendMessage(Component.text(bound ? "Экран ЭТ-1 подключён к ближайшей активной камере." : "Не найдена цельная прямоугольная группа карт 1×1–4×4 рядом с телевизором.", bound ? NamedTextColor.GREEN : NamedTextColor.RED));
                return true;
            }
            if (args[1].equalsIgnoreCase("play") && args.length == 3) {
                Block television = player.getTargetBlockExact(6);
                if (television == null || !isTelevision(television)) { player.sendMessage("Посмотри на телевизор ЭТ-1."); return true; }
                try {
                    UUID id = UUID.fromString(args[2]);
                    Path frames = getDataFolder().toPath().resolve("recordings").resolve(id.toString()).resolve("frames");
                    boolean bound = Files.isDirectory(frames) && screenService.bindNearbyFrame(television, frames,
                            getConfig().getLong("television.playback-frame-interval-ms", 1000L), true);
                    player.sendMessage(Component.text(bound ? "Архивная запись запущена с первого кадра." : "Запись не найдена или рядом нет цельной группы карт 1×1–4×4.", bound ? NamedTextColor.GREEN : NamedTextColor.RED));
                } catch (IllegalArgumentException ex) { player.sendMessage("Неверный UUID записи."); }
                return true;
            }
            player.sendMessage("Использование: /ether screen <map|bind [канал]|play <UUID>>");
            return true;
        }
        if (args.length == 1 && (args[0].equalsIgnoreCase("play") || args[0].equalsIgnoreCase("pause"))) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Команда доступна игроку."); return true; }
            if (!player.hasPermission("ethertv.use")) { sender.sendMessage("Недостаточно прав."); return true; }
            Block television = player.getTargetBlockExact(6);
            if (television == null || !isTelevision(television)) {
                player.sendMessage(Component.text("Посмотри на установленный телевизор ЭТ-1.", NamedTextColor.RED));
                return true;
            }
            String action = args[0].toLowerCase(java.util.Locale.ROOT);
            int sent = clientBridge.controlYoutube(television, action, 0.0);
            player.sendMessage(Component.text("Команда " + action + " отправлена клиентам EtherTV: " + sent, NamedTextColor.GREEN));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("network")) {
            if (!sender.hasPermission("ethertv.admin")) { sender.sendMessage("Недостаточно прав."); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage("Команда доступна игроку."); return true; }
            if (args[1].equalsIgnoreCase("list")) {
                var nodes = networkService.describeNodes();
                player.sendMessage(Component.text(nodes.isEmpty() ? "Сетевые узлы не зарегистрированы." : "Узлы EtherTV:\n" + String.join("\n", nodes), NamedTextColor.YELLOW));
                return true;
            }
            Block target = player.getTargetBlockExact(6);
            if (target == null || target.getType() != Material.LIGHTNING_ROD) { player.sendMessage("Посмотри на громоотвод в пределах 6 блоков."); return true; }
            if (args[1].equalsIgnoreCase("add") && args.length == 3) {
                boolean ok = networkService.register(target, args[2]);
                player.sendMessage(Component.text(ok ? "Громоотвод зарегистрирован в канале " + args[2] + "." : "Имя канала: латиница, цифры, _ или -, до 24 символов.", ok ? NamedTextColor.GREEN : NamedTextColor.RED));
                return true;
            }
            if (args[1].equalsIgnoreCase("remove")) {
                player.sendMessage(networkService.unregister(target) ? "Узел удалён из сети EtherTV." : "Этот громоотвод не был зарегистрирован.");
                return true;
            }
            player.sendMessage("Использование: /ether network <add <канал>|remove|list>");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("recordings")) {
            Path root = getDataFolder().toPath().resolve("recordings");
            try (var dirs = Files.list(root)) {
                var ids = dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
                playerMessage(sender, ids.isEmpty() ? "Архив записей пуст." : "Архив EtherTV:\n" + String.join("\n", ids));
            } catch (IOException e) { playerMessage(sender, "Архив пока пуст."); }
            return true;
        }
        if ((args.length == 2 || args.length == 3) && args[0].equalsIgnoreCase("record")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Команда доступна игроку."); return true; }
            Block target = player.getTargetBlockExact(6);
            if (target == null || !isCamera(target)) { player.sendMessage(Component.text("Посмотри на установленную камеру ЭК-1.", NamedTextColor.RED)); return true; }
            if (args[1].equalsIgnoreCase("start")) startRecording(player, target, args.length == 3 ? args[2] : "local");
            else if (args[1].equalsIgnoreCase("stop")) stopRecording(player, target, "manual stop");
            else player.sendMessage("Использование: /ether record <start [канал]|stop>");
            return true;
        }
        sender.sendMessage(Component.text("Команды: /ether give <tv|camera>, /ether record <start [канал]|stop>, /ether screen <map|bind [канал]|play <UUID>>, /ether recordings, /ether network <add|remove|list>, /ether youtube <ссылка|play|pause|seek <секунды>|stop>, /ether consent, /ether mode, /ether status", NamedTextColor.YELLOW));
        return true;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!item.hasItemMeta()) return;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        Block block = event.getBlockPlaced();
        if (pdc.has(tvItemKey, PersistentDataType.BYTE) && block.getState() instanceof TileState tile) {
            tile.getPersistentDataContainer().set(tvBlockKey, PersistentDataType.BYTE, (byte) 1);
            tile.update(true, false);
            televisionControl.initialize(block);
            createTelevisionDisplay(block, event.getPlayer());
            event.getPlayer().sendMessage(Component.text("Телевизор ЭТ-1 установлен. Для отдельной модели нужен ресурспак EtherTV.", NamedTextColor.GREEN));
        } else if (pdc.has(cameraItemKey, PersistentDataType.BYTE) && block.getState() instanceof TileState tile) {
            tile.getPersistentDataContainer().set(cameraBlockKey, PersistentDataType.BYTE, (byte) 1);
            tile.update(true, false);
            event.getPlayer().sendMessage(Component.text("Камера ЭК-1 установлена. Вставь Custom Disc в слот камеры.", NamedTextColor.GREEN));
        }
    }

    private void createTelevisionDisplay(Block block, Player placer) {
        // The jukebox remains the real block for CustomDiscs. The visible cabinet is
        // a separate display entity, so the resource pack never overrides vanilla jukeboxes.
        Location location = block.getLocation().add(0.5, 0.5, 0.5);
        BlockFace front = placer.getFacing().getOppositeFace();
        float yaw = switch (front) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case NORTH -> 180.0f;
            case EAST -> 270.0f;
            default -> 180.0f;
        };
        location.setYaw(yaw);
        ItemDisplay display = block.getWorld().spawn(location, ItemDisplay.class, entity -> {
            ItemStack model = new ItemStack(Material.PAPER);
            ItemMeta meta = model.getItemMeta();
            meta.setItemModel(new NamespacedKey(this, "television"));
            model.setItemMeta(meta);
            entity.setItemStack(model);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setPersistent(true);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setRotation(yaw, 0.0f);
            entity.addScoreboardTag("ethertv_et1_display");
        });
        if (block.getState() instanceof TileState tile) {
            tile.getPersistentDataContainer().set(tvDisplayKey, PersistentDataType.STRING, display.getUniqueId().toString());
            tile.update(true, false);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof TileState tile)) return;
        String displayId = tile.getPersistentDataContainer().get(tvDisplayKey, PersistentDataType.STRING);
        boolean isTv = tile.getPersistentDataContainer().has(tvBlockKey, PersistentDataType.BYTE);
        boolean isEk1Camera = tile.getPersistentDataContainer().has(cameraBlockKey, PersistentDataType.BYTE);
        if (!isTv && !isEk1Camera) return;
        if (displayId != null) {
            try {
                org.bukkit.entity.Entity display = block.getWorld().getEntity(UUID.fromString(displayId));
                if (display != null) display.remove();
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Invalid ET-1 display UUID at " + block.getLocation());
            }
        }
        if (event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) {
            event.setDropItems(false);
            if (isTv) {
                block.getWorld().dropItemNaturally(block.getLocation(), createTaggedItem(Material.JUKEBOX,
                        getConfig().getString("television.display-name", "Телевизор ЭТ-1"), tvItemKey));
            } else {
                event.setExpToDrop(0);
                block.getWorld().dropItemNaturally(block.getLocation(), createTaggedItem(Material.DISPENSER,
                        getConfig().getString("camera.display-name", "Видеокамера ЭК-1"), cameraItemKey));
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (isTelevision(block) && event.getPlayer().isSneaking()) {
            event.setCancelled(true);
            int next = televisionControl.cycleChannel(block);
            event.getPlayer().sendMessage(Component.text("Канал ЭТ-1: " + next + "/15", NamedTextColor.AQUA));
            return;
        }
        if (isCamera(block)) {
            event.setCancelled(true);
            if (block.getState() instanceof Dispenser dispenser) event.getPlayer().openInventory(dispenser.getInventory());
            return;
        }
        // Do not cancel interaction with the jukebox: CustomDiscs must be able to
        // insert/eject a disc and play its audio through the TV block.
    }

    private boolean isCamera(Block block) {
        return block.getState() instanceof TileState tile && tile.getPersistentDataContainer().has(cameraBlockKey, PersistentDataType.BYTE);
    }
    public boolean isTelevision(Block block) {
        return block.getState() instanceof TileState tile && tile.getPersistentDataContainer().has(tvBlockKey, PersistentDataType.BYTE);
    }

    private void startRecording(Player player, Block camera, String channel) {
        if (channel == null || !channel.toLowerCase(java.util.Locale.ROOT).matches("[a-z0-9_-]{1,24}")) { player.sendMessage("Имя канала: латиница, цифры, _ или -, до 24 символов."); return; }
        if (!player.hasPermission("ethertv.use")) { player.sendMessage("Недостаточно прав."); return; }
        String key = blockKey(camera);
        if (recordings.containsKey(key)) { player.sendMessage("Камера уже записывает."); return; }
        boolean microphoneEnabled = getConfig().getBoolean("camera.microphone-enabled", true);
        if (microphoneEnabled && !microphoneConsent.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("Для записи микрофона сначала включи согласие: /ether consent. Если нужен только видеоряд, отключи microphone-enabled в config.yml.", NamedTextColor.RED));
            return;
        }
        if (!(camera.getState() instanceof Dispenser dispenser)) return;
        ItemStack disc = dispenser.getInventory().getItem(0);
        CustomDiscsAPI cdApi = CustomDiscsAPI.get();
        if (disc == null || cdApi == null || !cdApi.isCustomDisc(disc)) {
            player.sendMessage(Component.text("В слот 1 камеры нужно вставить пластинку Custom Discs.", NamedTextColor.RED));
            return;
        }
        if (microphoneEnabled && voicechatApi == null) { player.sendMessage(Component.text("Simple Voice Chat API не обнаружен. Для видеозаписи без микрофона отключи camera.microphone-enabled в config.yml.", NamedTextColor.RED)); return; }
        FrameEncoder frameEncoder = new FrameEncoder(this);
        RecordingSession createdSession = null;
        try {
            UUID id = UUID.randomUUID();
            Path directory = getDataFolder().toPath().resolve("recordings").resolve(id.toString());
            OpusDecoder decoder = microphoneEnabled ? voicechatApi.createDecoder() : null;
            createdSession = new RecordingSession(this, camera, id, player.getUniqueId(), directory, finished -> {
                recordings.remove(key);
                if (clientBridge != null) clientBridge.stopCapture(finished.getOperator(), finished.getId());
                attachRecordingToDisc(camera, finished);
            }, frameEncoder);
            // The client capture service currently supports one active recording per player.
            // If this operator already records another camera, use the server fallback for this one.
            boolean operatorAlreadyUsesClientCapture = recordings.values().stream()
                    .anyMatch(active -> active.getOperator().equals(player.getUniqueId()) && !active.isClosed());
            boolean clientCapture = getConfig().getBoolean("client-capture.prefer-client-framebuffer", true)
                    && !operatorAlreadyUsesClientCapture
                    && clientBridge != null && clientBridge.hasClient(player);
            createdSession.start(decoder,
                    getConfig().getInt("camera.frame-interval-ticks", 20),
                    Math.min(1800, getConfig().getInt("camera.max-recording-seconds", 1800)),
                    getConfig().getInt("camera.frame-width", 48),
                    getConfig().getInt("camera.frame-height", 27),
                    getConfig().getDouble("camera.view-distance", 24.0),
                    !clientCapture);
            writeBroadcastMetadata(directory, camera, channel);
            recordings.put(key, createdSession);
            if (clientCapture) clientBridge.startCapture(player, id, camera);
            announce(camera, microphoneEnabled
                    ? "Камера ЭК-1 начала запись. Микрофон оператора записывается с его согласия."
                    : "Камера ЭК-1 начала видеозапись без микрофона.");
            player.sendMessage(Component.text("Запись началась. Максимум 30 минут.", NamedTextColor.GREEN));
        } catch (Exception e) {
            if (createdSession != null) createdSession.stop("recording startup failed");
            else frameEncoder.close();
            player.sendMessage(Component.text("Не удалось начать запись: " + e.getMessage(), NamedTextColor.RED));
            getLogger().warning("Recording start failed: " + e.getMessage());
        }
    }

    private void stopRecording(Player player, Block camera, String reason) {
        RecordingSession session = recordings.remove(blockKey(camera));
        if (session == null) { player.sendMessage("Камера не ведёт запись."); return; }
        if (clientBridge != null) clientBridge.stopCapture(session.getOperator(), session.getId());
        session.stop(reason);
        player.sendMessage(Component.text(session.getPcmBytes() > 0
                ? "Запись остановлена. Видеокадры и звук микрофона сохранены. Забери пластинку из камеры и вставь её в телевизор для воспроизведения звука."
                : "Запись остановлена. Видеокадры сохранены; микрофонная дорожка пуста. Забери пластинку из камеры и вставь её в телевизор.", NamedTextColor.GREEN));
        announce(camera, "Камера ЭК-1 завершила запись.");
    }

    private void writeBroadcastMetadata(Path directory, Block camera, String channel) throws IOException {
        String normalized = channel.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,24}")) throw new IllegalArgumentException("Недопустимое имя канала");
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("channel", normalized);
        yaml.set("world", camera.getWorld().getUID().toString());
        yaml.set("x", camera.getX()); yaml.set("y", camera.getY()); yaml.set("z", camera.getZ());
        yaml.set("created", System.currentTimeMillis());
        yaml.save(directory.resolve("broadcast.yml").toFile());
    }

    private Path findLatestRecordingForChannel(String channel) {
        Path root = getDataFolder().toPath().resolve("recordings");
        if (!Files.isDirectory(root)) return null;
        try (var dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).filter(dir -> {
                var meta = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dir.resolve("broadcast.yml").toFile());
                return channel.equalsIgnoreCase(meta.getString("channel", "")) && Files.isDirectory(dir.resolve("frames"));
            }).max(java.util.Comparator.comparingLong(dir -> {
                var meta = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dir.resolve("broadcast.yml").toFile());
                return meta.getLong("created", 0L);
            })).orElse(null);
        } catch (IOException e) { return null; }
    }

    private void playerMessage(CommandSender sender, String message) { sender.sendMessage(Component.text(message, NamedTextColor.YELLOW)); }

    public Path youtubeFramesDirectory(World world, int x, int y, int z) {
        return getDataFolder().toPath().resolve("youtube-live").resolve(world.getUID().toString())
                .resolve(x + "_" + y + "_" + z).resolve("frames");
    }

    public void startYoutubeScreen(Block television) {
        Path frames = youtubeFramesDirectory(television.getWorld(), television.getX(), television.getY(), television.getZ());
        try {
            Files.createDirectories(frames);
            try (var stream = Files.list(frames)) {
                for (Path old : stream.toList()) Files.deleteIfExists(old);
            }
            boolean bound = screenService.bindNearbyFrame(television, frames, 500L, true);
            if (!bound) getLogger().warning("YouTube задан, но рядом с ЭТ-1 не найдена карта-экран в рамке.");
        } catch (IOException error) {
            getLogger().warning("Не удалось подготовить YouTube-экран: " + error.getMessage());
        }
    }

    public void stopYoutubeScreen(Block television) {
        Path frames = youtubeFramesDirectory(television.getWorld(), television.getX(), television.getY(), television.getZ());
        try {
            Files.createDirectories(frames);
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(java.awt.Color.BLACK); g.fillRect(0, 0, 128, 128);
            g.setColor(java.awt.Color.WHITE); g.drawString("ETHER TV", 39, 58); g.drawString("NO SIGNAL", 35, 75); g.dispose();
            Path output = frames.resolve("frame-999999999.png");
            youtubeFrameEncoder.submit(image, output);
        } catch (IOException error) {
            getLogger().warning("Не удалось остановить YouTube-экран: " + error.getMessage());
        }
    }

    public void acceptYoutubeFrame(World world, int x, int y, int z, long sequence, byte[] jpeg) {
        if (world == null || jpeg == null || jpeg.length < 4 || jpeg.length > 180_000 || youtubeFrameEncoder == null) return;
        if (sequence > 0) {
            Path frames = youtubeFramesDirectory(world, x, y, z);
            // Keep a short rolling window so the live directory does not grow indefinitely.
            try (var files = Files.list(frames)) {
                for (Path old : files.toList()) {
                    String name = old.getFileName().toString();
                    if (!name.startsWith("frame-") || !name.endsWith(".jpg")) continue;
                    try {
                        long n = Long.parseLong(name.substring(6, name.length() - 4));
                        if (n < sequence - 8) Files.deleteIfExists(old);
                    } catch (NumberFormatException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        Path output = youtubeFramesDirectory(world, x, y, z).resolve(String.format(java.util.Locale.ROOT, "frame-%09d.jpg", sequence));
        youtubeFrameEncoder.submitEncodedJpeg(jpeg, output);
    }

    public void acceptClientFrame(Player sender, UUID recordingId, int sequence, byte[] jpeg) {
        int maxBytes = Math.max(1, Math.min(400_000, getConfig().getInt("client-capture.max-frame-bytes", 400_000)));
        if (sender == null || recordingId == null || jpeg == null || jpeg.length > maxBytes) return;
        for (RecordingSession session : recordings.values()) {
            if (!session.getId().equals(recordingId)) continue;
            if (!session.getOperator().equals(sender.getUniqueId())) return;
            if (!sender.hasPermission("ethertv.use") || session.isClosed()) return;
            session.acceptClientJpeg(sequence, jpeg);
            return;
        }
    }

    public void acceptClientGameAudio(Player sender, UUID recordingId, int sequence, byte[] pcm) {
        int maxBytes = Math.max(1, Math.min(48_000, getConfig().getInt("client-capture.max-game-audio-bytes", 48_000)));
        if (sender == null || recordingId == null || pcm == null || pcm.length > maxBytes) return;
        for (RecordingSession session : recordings.values()) {
            if (!session.getId().equals(recordingId)) continue;
            if (!session.getOperator().equals(sender.getUniqueId())) return;
            if (!sender.hasPermission("ethertv.use") || session.isClosed()) return;
            session.acceptGameAudioPcm(sequence, pcm);
            return;
        }
    }

    public void acceptMicrophonePacket(UUID speaker, byte[] opus, de.maxhenkel.voicechat.api.VoicechatServerApi api) {
        if (!getConfig().getBoolean("camera.microphone-enabled", true) || !microphoneConsent.contains(speaker)) return;
        for (RecordingSession session : recordings.values()) {
            if (session.getOperator().equals(speaker) && !session.isClosed()) {
                session.acceptOpus(opus);
                return;
            }
        }
    }

    private void attachRecordingToDisc(Block camera, RecordingSession session) {
        if (!(camera.getState() instanceof Dispenser dispenser)) return;
        ItemStack disc = dispenser.getInventory().getItem(0);
        if (disc == null || !disc.hasItemMeta()) return;
        try {
            var customDiscsPlugin = Bukkit.getPluginManager().getPlugin("CustomDiscs");
            if (customDiscsPlugin == null) throw new IllegalStateException("CustomDiscs is not loaded");
            Path customDiscsFolder = customDiscsPlugin.getDataFolder().toPath();
            Path musicData = customDiscsFolder.resolve("musicdata");
            Path targetDir = musicData.resolve("ether-recordings");
            Files.createDirectories(targetDir);
            String relative = "ether-recordings/" + session.getId() + ".wav";
            Path target = musicData.resolve(relative);
            if (Files.exists(session.getWavPath()) && session.getPcmBytes() > 0) {
                Files.copy(session.getWavPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                ItemMeta meta = disc.getItemMeta();
                var pdc = meta.getPersistentDataContainer();
                pdc.remove(customDiscRemoteKey);
                pdc.set(customDiscLocalKey, PersistentDataType.STRING, relative);
                pdc.set(recordingIdKey, PersistentDataType.STRING, session.getId().toString());
                java.util.List<Component> lore = meta.lore() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(meta.lore());
                lore.add(Component.text("Запись ЭК-1: " + session.getId().toString().substring(0, 8), NamedTextColor.GRAY));
                meta.lore(lore);
                disc.setItemMeta(meta);
                dispenser.getInventory().setItem(0, disc);
                dispenser.update(true, false);
                getLogger().info("Camera recording audio attached to CustomDiscs item " + session.getId());
            } else {
                getLogger().warning("No microphone audio was captured; disc was not converted to a local audio disc.");
            }
        } catch (Exception e) {
            getLogger().warning("Could not attach audio to CustomDiscs disc: " + e.getMessage());
        }
    }

    private void announce(Block camera, String message) {
        double radius = getConfig().getDouble("camera.announce-radius", 16.0);
        for (Player nearby : camera.getWorld().getNearbyPlayers(camera.getLocation(), radius)) {
            nearby.sendMessage(Component.text("[EtherTV] " + message, NamedTextColor.YELLOW));
        }
    }

    private String blockKey(Block b) { return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    private void loadConsent() {
        Path file = getDataFolder().toPath().resolve("consent.yml");
        if (!Files.exists(file)) return;
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        for (String value : yaml.getStringList("microphone-opt-in")) {
            try { microphoneConsent.add(UUID.fromString(value)); } catch (IllegalArgumentException ignored) {}
        }
    }
    private void saveConsent() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            var yaml = new org.bukkit.configuration.file.YamlConfiguration();
            yaml.set("microphone-opt-in", microphoneConsent.stream().map(UUID::toString).toList());
            yaml.save(getDataFolder().toPath().resolve("consent.yml").toFile());
        } catch (IOException e) { getLogger().warning("Could not save microphone consent: " + e.getMessage()); }
    }
}
