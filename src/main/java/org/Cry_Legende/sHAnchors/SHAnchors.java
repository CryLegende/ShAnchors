package org.Cry_Legende.sHAnchors;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.NumberConversions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

public class SHAnchors extends JavaPlugin {
    private static SHAnchors instance;
    private FileConfiguration config;
    private final Map<UUID, TeleportAnchor> anchors = new HashMap<>();
    private final Map<UUID, UUID> linkedAnchors = new HashMap<>();
    private final Map<UUID, UUID> anchorSelectionMode = new HashMap<>();
    private final Map<UUID, Long> teleportCooldown = new HashMap<>();
    private final Map<ChunkKey, Set<UUID>> anchorsByChunk = new HashMap<>();
    // Entrées d'anchors.yml gardées telles quelles : monde pas encore chargé (PhantomWorlds) ou entrée illisible
    private final Map<String, Object> pendingAnchors = new LinkedHashMap<>();
    // Faux si anchors.yml existe mais n'a pas pu être lu : on ne le réécrit pas
    private boolean anchorsLoaded = true;

    private static final Particle.DustOptions PARTICLE_PUBLIC = new Particle.DustOptions(Color.PURPLE, 1.0f);
    private static final Particle.DustOptions PARTICLE_ISLAND_ONLY = new Particle.DustOptions(Color.RED, 1.0f);

    private boolean ssbHookEnabled = false;

    private static final String BYPASS_PERMISSION = "shanchors.bypass";

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        config = getConfig();

        if (Bukkit.getPluginManager().getPlugin("SuperiorSkyblock2") != null) {
            ssbHookEnabled = true;
            getLogger().info("SuperiorSkyblock2 détecté : hook activé.");
        } else {
            ssbHookEnabled = false;
            getLogger().info("SuperiorSkyblock2 non détecté : hook désactivé.");
        }

        getServer().getPluginManager().registerEvents(new AnchorListener(this), this);
        Objects.requireNonNull(getCommand("shanchors")).setExecutor(new AnchorCommand(this));

        loadAnchors();
        cleanupOrphanedAnchorsLightweight();

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (TeleportAnchor anchor : new ArrayList<>(anchors.values())) {
                World world = anchor.getLocation().getWorld();
                if (world == null) continue;

                Location loc = anchor.getLocation();
                if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
                if (!linkedAnchors.containsKey(anchor.getId())) continue;
                if (loc.getBlock().getType() != Material.RESPAWN_ANCHOR) continue;

                if (!world.getNearbyPlayers(loc, 16, 16, 16).isEmpty()) {
                    Location particleLoc = loc.clone().add(0.5, 1.2, 0.5);
                    Particle.DustOptions dustOptions = anchor.isIslandOnly() ? PARTICLE_ISLAND_ONLY : PARTICLE_PUBLIC;
                    world.spawnParticle(Particle.DUST, particleLoc, 1, 0, 0, 0, 0, dustOptions);
                }
            }
        }, 0L, 20L);

        long cleanupIntervalTicks = 20L * 60 * 30;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            cleanupOrphanedAnchorsLightweight();
            // Sauvegarde régulière : un arrêt brutal du serveur ne perd plus les ancres posées depuis le démarrage
            saveAnchors();
        }, cleanupIntervalTicks, cleanupIntervalTicks);
    }

    @Override
    public void onDisable() {
        saveAnchors();
        // Sécurité pour Plugman : nettoyage explicite
        HandlerList.unregisterAll(this);
        Bukkit.getScheduler().cancelTasks(this);
        instance = null;
    }

    public static SHAnchors getInstance() {
        return instance;
    }

    public FileConfiguration getPluginConfig() {
        return config;
    }

    // --- ACCESSEURS POUR LES CLASSES EXTERNES ---
    public Map<UUID, TeleportAnchor> getAnchors() {
        return anchors;
    }

    public Map<UUID, UUID> getLinkedAnchors() {
        return linkedAnchors;
    }

    public Map<UUID, UUID> getAnchorSelectionMode() {
        return anchorSelectionMode;
    }

    public Map<UUID, Long> getTeleportCooldown() {
        return teleportCooldown;
    }

    public Map<ChunkKey, Set<UUID>> getAnchorsByChunk() {
        return anchorsByChunk;
    }

    public boolean isSsbHookEnabled() {
        return ssbHookEnabled;
    }

    public static String getBypassPermission() {
        return BYPASS_PERMISSION;
    }

    public void addAnchorToChunkMap(TeleportAnchor anchor) {
        anchorsByChunk.computeIfAbsent(ChunkKey.of(anchor.getLocation()), k -> new HashSet<>()).add(anchor.getId());
    }

    public void removeAnchorFromChunkMap(TeleportAnchor anchor) {
        ChunkKey chunk = ChunkKey.of(anchor.getLocation());
        Set<UUID> chunkAnchors = anchorsByChunk.get(chunk);
        if (chunkAnchors != null) {
            chunkAnchors.remove(anchor.getId());
            if (chunkAnchors.isEmpty()) {
                anchorsByChunk.remove(chunk);
            }
        }
    }

    public boolean isPendingAnchor(UUID id) {
        return pendingAnchors.containsKey(id.toString());
    }

    public ItemStack createAnchorItem(int amount) {
        ItemStack item = new ItemStack(Material.RESPAWN_ANCHOR, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', config.getString("anchor.name")));
            List<String> lore = config.getStringList("anchor.lore");
            lore = lore.stream().map(line -> ChatColor.translateAlternateColorCodes('&', line)).collect(Collectors.toList());
            meta.setLore(lore);
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isAnchorItem(ItemStack item) {
        if (item == null || item.getType() != Material.RESPAWN_ANCHOR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasEnchant(Enchantment.UNBREAKING) && meta.hasLore();
    }

    public void openAnchorGui(Player player, TeleportAnchor anchor) {
        String guiTitle = ChatColor.translateAlternateColorCodes('&', config.getString("gui.title", "&8Gestion de l'Ancre"));
        Inventory gui = Bukkit.createInventory(new AnchorInventoryHolder(anchor), 27, guiTitle);

        UUID linkedId = linkedAnchors.get(anchor.getId());

        String linkBtnKey = linkedId != null ? "gui.unlink-item" : "gui.link-item";
        Material linkBtnMaterial = Material.valueOf(config.getString(linkBtnKey + ".material").toUpperCase());
        ItemStack linkBtnItem = createGuiItem(linkBtnMaterial, config.getString(linkBtnKey + ".name"), config.getStringList(linkBtnKey + ".lore"));
        gui.setItem(config.getInt("gui.slots.link", 15), linkBtnItem);

        if (linkedId != null) {
            TeleportAnchor linkedAnchor = anchors.get(linkedId);
            if (linkedAnchor != null) {
                Location loc = linkedAnchor.getLocation();
                Material matLinked = Material.valueOf(config.getString("gui.linked-anchor-item.material").toUpperCase());
                ItemStack linkedItem = createGuiItem(matLinked,
                    config.getString("gui.linked-anchor-item.name"),
                    config.getStringList("gui.linked-anchor-item.lore"),
                    "%x%", String.valueOf(loc.getBlockX()),
                    "%y%", String.valueOf(loc.getBlockY()),
                    "%z%", String.valueOf(loc.getBlockZ())
                );
                gui.setItem(config.getInt("gui.slots.linked", 13), linkedItem);
            }
        }

        if (ssbHookEnabled) {
            String accessBtnKey = anchor.isIslandOnly() ? "gui.access-public-item" : "gui.access-island-item";
            Material accessBtnMaterial = Material.valueOf(config.getString(accessBtnKey + ".material").toUpperCase());
            ItemStack accessBtnItem = createGuiItem(accessBtnMaterial, config.getString(accessBtnKey + ".name"), config.getStringList(accessBtnKey + ".lore"));
            gui.setItem(config.getInt("gui.slots.access", 11), accessBtnItem);
        }

        String orientName = config.getString("gui.orientation-item.states." + anchor.getOrientation().name(), anchor.getOrientation().name());
        Material orientMat = Material.valueOf(config.getString("gui.orientation-item.material", "COMPASS").toUpperCase());
        ItemStack orientItem = createGuiItem(orientMat,
            config.getString("gui.orientation-item.name"),
            config.getStringList("gui.orientation-item.lore"),
            "%orientation%", orientName);
        gui.setItem(config.getInt("gui.slots.orientation", 17), orientItem);

        Material fillerMat = Material.valueOf(config.getString("gui.filler-item.material").toUpperCase());
        ItemStack fillerItem = createGuiItem(fillerMat, config.getString("gui.filler-item.name"), Collections.emptyList());
        for (int i = 0; i < gui.getSize(); i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, fillerItem);
            }
        }

        player.openInventory(gui);
    }

    private ItemStack createGuiItem(Material material, String name, List<String> lore, String... replacements) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            List<String> finalLore = new ArrayList<>();
            for (String line : lore) {
                String tempLine = line;
                for (int i = 0; i < replacements.length; i += 2) {
                    tempLine = tempLine.replace(replacements[i], replacements[i + 1]);
                }
                finalLore.add(ChatColor.translateAlternateColorCodes('&', tempLine));
            }
            meta.setLore(finalLore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    public TeleportAnchor getAnchorAt(Location location) {
        Set<UUID> chunkAnchors = anchorsByChunk.get(ChunkKey.of(location));
        if (chunkAnchors == null) return null;

        for (UUID anchorId : chunkAnchors) {
            TeleportAnchor anchor = anchors.get(anchorId);
            if (anchor != null && anchor.getLocation().equals(location)) {
                if (location.getBlock().getType() != Material.RESPAWN_ANCHOR) {
                    cleanupInvalidAnchor(anchor);
                    return null;
                }
                return anchor;
            }
        }
        return null;
    }

    private void cleanupInvalidAnchor(TeleportAnchor anchor) {
        anchors.remove(anchor.getId());
        removeAnchorFromChunkMap(anchor);
        UUID linkedId = linkedAnchors.remove(anchor.getId());
        if (linkedId != null) {
            linkedAnchors.remove(linkedId);
        }
    }

    private enum Resolution {OK, WORLD_MISSING, INVALID}

    /**
     * Lecture brute d'anchors.yml (sans désérialiser les Location) : un emplacement dont le monde n'est pas
     * encore chargé est gardé en attente et réécrit tel quel, au lieu d'être perdu à la sauvegarde suivante.
     */
    private void loadAnchors() {
        File file = new File(getDataFolder(), "anchors.yml");
        if (!file.exists()) return;
        Object parsed;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (IOException | RuntimeException e) {
            anchorsLoaded = false;
            getLogger().severe("anchors.yml illisible, les ancres ne seront pas sauvegardées pour ne pas l'écraser : " + e.getMessage());
            return;
        }
        if (!(parsed instanceof Map<?, ?> root)) return;
        for (Map.Entry<?, ?> entry : root.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Resolution result = resolveAnchor(key, entry.getValue());
            if (result == Resolution.OK) continue;
            pendingAnchors.put(key, entry.getValue());
            if (result == Resolution.INVALID) {
                getLogger().warning("Ancre " + key + " illisible : entrée conservée telle quelle dans anchors.yml.");
            }
        }
        if (!pendingAnchors.isEmpty()) {
            getLogger().info(pendingAnchors.size() + " ancre(s) en attente (monde pas encore chargé ou entrée illisible).");
        }
    }

    private static World worldOf(Map<?, ?> location) {
        Object worldKey = location.get("world_key");
        if (worldKey != null) {
            NamespacedKey namespaced = NamespacedKey.fromString(String.valueOf(worldKey));
            return namespaced == null ? null : Bukkit.getWorld(namespaced);
        }
        Object name = location.get("world");
        return name == null ? null : Bukkit.getWorld(String.valueOf(name));
    }

    private Resolution resolveAnchor(String key, Object rawEntry) {
        try {
            UUID id = UUID.fromString(key);
            if (!(rawEntry instanceof Map<?, ?> raw) || !(raw.get("location") instanceof Map<?, ?> loc)) {
                return Resolution.INVALID;
            }
            Object linkedRaw = raw.get("linked");
            UUID linkedId = linkedRaw == null ? null : UUID.fromString(String.valueOf(linkedRaw));
            World world = worldOf(loc);
            if (world == null) return Resolution.WORLD_MISSING;
            Location location = new Location(world, NumberConversions.toDouble(loc.get("x")),
                NumberConversions.toDouble(loc.get("y")), NumberConversions.toDouble(loc.get("z")),
                NumberConversions.toFloat(loc.get("yaw")), NumberConversions.toFloat(loc.get("pitch")));
            boolean islandOnly = raw.get("islandOnly") instanceof Boolean b && b;
            AnchorOrientation orientation;
            try {
                orientation = AnchorOrientation.valueOf(String.valueOf(raw.get("orientation") == null ? "DEFAULT" : raw.get("orientation")));
            } catch (IllegalArgumentException e) {
                orientation = AnchorOrientation.DEFAULT;
            }
            TeleportAnchor anchor = new TeleportAnchor(id, location, islandOnly, orientation);
            anchors.put(id, anchor);
            addAnchorToChunkMap(anchor);
            if (linkedId != null) {
                linkedAnchors.put(id, linkedId);
            }
            return Resolution.OK;
        } catch (RuntimeException e) {
            return Resolution.INVALID;
        }
    }

    /**
     * Appelé quand un monde se charge : les ancres en attente dans ce monde deviennent actives.
     */
    public void resolvePendingAnchors() {
        if (pendingAnchors.isEmpty()) return;
        int resolved = 0;
        for (Iterator<Map.Entry<String, Object>> it = pendingAnchors.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Object> entry = it.next();
            if (resolveAnchor(entry.getKey(), entry.getValue()) == Resolution.OK) {
                it.remove();
                resolved++;
            }
        }
        if (resolved > 0) {
            getLogger().info(resolved + " ancre(s) en attente activée(s) avec le chargement de leur monde.");
        }
    }

    private void saveAnchors() {
        if (!anchorsLoaded) return;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, TeleportAnchor> entry : anchors.entrySet()) {
            String key = entry.getKey().toString();
            TeleportAnchor anchor = entry.getValue();
            yaml.set(key + ".location", anchor.getLocation());
            yaml.set(key + ".islandOnly", anchor.isIslandOnly());
            yaml.set(key + ".orientation", anchor.getOrientation().name());
            UUID linkedId = linkedAnchors.get(anchor.getId());
            if (linkedId != null) {
                yaml.set(key + ".linked", linkedId.toString());
            }
        }
        pendingAnchors.forEach(yaml::set);
        // Fichier temporaire puis remplacement : un arrêt pendant l'écriture ne laisse pas un anchors.yml tronqué
        File target = new File(getDataFolder(), "anchors.yml");
        File temp = new File(getDataFolder(), "anchors.yml.tmp");
        try {
            yaml.save(temp);
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            getLogger().severe("Impossible de sauvegarder les ancres dans anchors.yml: " + e.getMessage());
        }
    }

    /**
     * Retire les ancres dont le bloc a disparu. Seuls les chunks chargés sont examinés : ne charge aucun chunk.
     */
    private void cleanupOrphanedAnchorsLightweight() {
        int cleanedCount = 0;
        for (TeleportAnchor anchor : new ArrayList<>(anchors.values())) {
            Location loc = anchor.getLocation();
            World world = loc.getWorld();
            if (world == null || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
            if (loc.getBlock().getType() != Material.RESPAWN_ANCHOR) {
                cleanupInvalidAnchor(anchor);
                cleanedCount++;
            }
        }
        if (cleanedCount > 0) {
            getLogger().info("Nettoyage léger terminé. " + cleanedCount + " ancre(s) orpheline(s) supprimée(s).");
        }
    }

    /**
     * Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup).
     */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    public void playSound(Player player, String configKey) {
        ConfigurationSection sec = config.getConfigurationSection(configKey);
        if (sec == null) return;
        try {
            Sound sound = sound(sec.getString("sound"));
            if (sound == null) throw new IllegalArgumentException(sec.getString("sound"));
            float volume = (float) sec.getDouble("volume", 1.0);
            float pitch = (float) sec.getDouble("pitch", 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception e) {
            getLogger().warning("Son invalide dans config.yml pour la clé: " + configKey);
        }
    }
}

// -----------------------------------------------------------
// CLASSES DÉPLACÉES EN DEHORS DE LA CLASSE PRINCIPALE
// POUR ÉVITER LES ERREURS LORS DU RELOAD PLUGMAN
// -----------------------------------------------------------

class AnchorCommand implements CommandExecutor {
    private final SHAnchors plugin;

    public AnchorCommand(SHAnchors plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        FileConfiguration config = plugin.getPluginConfig();
        if (args.length < 1) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.invalid-args")));
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            if (!(sender instanceof Player) && args.length < 2) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.specify-player")));
                return true;
            }
            if (sender instanceof Player && !sender.hasPermission("shanchors.give")) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.no-permission")));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.specify-player")));
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.player-not-found")));
                return true;
            }
            int amount = 1;
            if (args.length >= 3) {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.invalid-amount")));
                    return true;
                }
            }
            ItemStack anchorItem = plugin.createAnchorItem(amount);
            target.getInventory().addItem(anchorItem);
            String giveMessage = config.getString("messages.anchor-given", "&aVous avez donné &e%amount% &aancre(s) à &e%player%&a.")
                .replace("%player%", target.getName())
                .replace("%amount%", String.valueOf(amount));
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', giveMessage));
            return true;
        }
        return false;
    }
}

class AnchorListener implements Listener {
    private final SHAnchors plugin;

    public AnchorListener(SHAnchors plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        plugin.resolvePendingAnchors();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getTeleportCooldown().remove(event.getPlayer().getUniqueId());
        plugin.getAnchorSelectionMode().remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (!plugin.isAnchorItem(item)) return;

        Player player = event.getPlayer();
        Location loc = event.getBlockPlaced().getLocation();
        FileConfiguration config = plugin.getPluginConfig();

        if (plugin.isSsbHookEnabled() && !player.hasPermission(SHAnchors.getBypassPermission())) {
            Island island = SuperiorSkyblockAPI.getIslandAt(loc);
            SuperiorPlayer sPlayer = SuperiorSkyblockAPI.getPlayer(player);
            if (island == null || !island.isMember(sPlayer)) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.not-on-island")));
                event.setCancelled(true);
                return;
            }
        }
        if (event.isCancelled()) return;

        TeleportAnchor anchor = new TeleportAnchor(loc);
        plugin.getAnchors().put(anchor.getId(), anchor);
        plugin.addAnchorToChunkMap(anchor);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.anchor-placed")));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getPlayer().isSneaking()) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.RESPAWN_ANCHOR) return;
        TeleportAnchor anchor = plugin.getAnchorAt(block.getLocation());
        if (anchor == null) return;
        Player player = event.getPlayer();
        FileConfiguration config = plugin.getPluginConfig();

        if (plugin.isSsbHookEnabled() && !player.hasPermission(SHAnchors.getBypassPermission())) {
            Island island = SuperiorSkyblockAPI.getIslandAt(anchor.getLocation());
            SuperiorPlayer sPlayer = SuperiorSkyblockAPI.getPlayer(player);
            if (island == null || !island.isMember(sPlayer)) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.isCancelled()) return;

        if (plugin.getAnchorSelectionMode().containsKey(player.getUniqueId())) {
            UUID firstAnchorId = plugin.getAnchorSelectionMode().get(player.getUniqueId());
            if (firstAnchorId.equals(anchor.getId())) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.cant-link-same")));
                return;
            }
            if (plugin.getLinkedAnchors().containsKey(firstAnchorId) || plugin.getLinkedAnchors().containsKey(anchor.getId())) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.already-linked")));
                plugin.getAnchorSelectionMode().remove(player.getUniqueId());
                return;
            }
            plugin.getLinkedAnchors().put(firstAnchorId, anchor.getId());
            plugin.getLinkedAnchors().put(anchor.getId(), firstAnchorId);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.anchor-linked")));
            plugin.playSound(player, "sounds.link-performed");
            plugin.getAnchorSelectionMode().remove(player.getUniqueId());
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        plugin.openAnchorGui(player, anchor);
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        TeleportAnchor originAnchor;
        Block blockAtFeet = loc.getBlock();
        Block blockBelow = loc.clone().subtract(0, 1, 0).getBlock();

        if (blockAtFeet.getType() == Material.RESPAWN_ANCHOR) {
            originAnchor = plugin.getAnchorAt(blockAtFeet.getLocation());
        } else if (blockBelow.getType() == Material.RESPAWN_ANCHOR) {
            originAnchor = plugin.getAnchorAt(blockBelow.getLocation());
        } else {
            return;
        }
        if (originAnchor == null) return;

        FileConfiguration config = plugin.getPluginConfig();

        if (plugin.isSsbHookEnabled() && originAnchor.isIslandOnly() && !player.hasPermission(SHAnchors.getBypassPermission())) {
            Island island = SuperiorSkyblockAPI.getIslandAt(originAnchor.getLocation());
            SuperiorPlayer sPlayer = SuperiorSkyblockAPI.getPlayer(player);
            if (island == null || !island.isMember(sPlayer)) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.cannot-use-restricted-anchor")));
                return;
            }
        }

        UUID linkedId = plugin.getLinkedAnchors().get(originAnchor.getId());
        if (linkedId == null) return;

        TeleportAnchor destAnchor = plugin.getAnchors().get(linkedId);
        if (destAnchor == null) {
            // Destination dans un monde pas encore chargé : on garde la liaison
            if (!plugin.isPendingAnchor(linkedId)) {
                plugin.getLinkedAnchors().remove(originAnchor.getId());
            }
            return;
        }

        if (originAnchor.getLocation().getBlock().getType() != Material.RESPAWN_ANCHOR ||
            destAnchor.getLocation().getBlock().getType() != Material.RESPAWN_ANCHOR) {
            plugin.getLinkedAnchors().remove(originAnchor.getId());
            return;
        }

        long now = System.currentTimeMillis();
        long delay = config.getLong("teleport.delay-ms", 500);
        if (plugin.getTeleportCooldown().containsKey(player.getUniqueId())) {
            long lastTp = plugin.getTeleportCooldown().get(player.getUniqueId());
            if (now - lastTp < delay) return;
        }
        plugin.getTeleportCooldown().put(player.getUniqueId(), now);

        Location destLoc = destAnchor.getLocation();
        World destWorld = destLoc.getWorld();
        if (destWorld == null) return;

        Location safeLoc = destLoc.clone().add(0.5, 1.05, 0.5);
        if (destAnchor.getOrientation() == AnchorOrientation.DEFAULT) {
            safeLoc.setYaw(player.getLocation().getYaw());
            safeLoc.setPitch(player.getLocation().getPitch());
        } else {
            safeLoc.setYaw(destAnchor.getOrientation().getYaw());
            safeLoc.setPitch(0f);
        }

        playTeleportEffects(player, player.getLocation());
        player.teleportAsync(safeLoc).thenAccept(success -> {
            if (success) {
                playTeleportEffects(player, player.getLocation());
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.teleported")));
            } else {
                player.sendMessage(ChatColor.RED + "La téléportation a échoué.");
                plugin.getTeleportCooldown().remove(player.getUniqueId());
            }
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        FileConfiguration config = plugin.getPluginConfig();
        if (!(event.getInventory().getHolder() instanceof AnchorInventoryHolder holder)) return;
        event.setCancelled(true);
        TeleportAnchor guiAnchor = holder.getAnchor();
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        Material linkMat = Material.valueOf(config.getString("gui.link-item.material", "END_CRYSTAL").toUpperCase());
        Material unlinkMat = Material.valueOf(config.getString("gui.unlink-item.material", "BARRIER").toUpperCase());
        Material accessPublicMat = Material.valueOf(config.getString("gui.access-public-item.material", "PLAYER_HEAD").toUpperCase());
        Material accessIslandMat = Material.valueOf(config.getString("gui.access-island-item.material", "IRON_DOOR").toUpperCase());
        Material orientationMat = Material.valueOf(config.getString("gui.orientation-item.material", "COMPASS").toUpperCase());

        if (clicked.getType() == linkMat) {
            plugin.getAnchorSelectionMode().put(player.getUniqueId(), guiAnchor.getId());
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.link-start")));
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (plugin.getAnchorSelectionMode().remove(player.getUniqueId()) != null) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7&l« &c&lSH &8&l» &7La tentative de liaison a expiré."));
                    plugin.playSound(player, "sounds.link-expired");
                }
            }, 200L);

        } else if (clicked.getType() == unlinkMat) {
            UUID linkedId = plugin.getLinkedAnchors().get(guiAnchor.getId());
            if (linkedId != null) {
                plugin.getLinkedAnchors().remove(linkedId);
                plugin.getLinkedAnchors().remove(guiAnchor.getId());
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.anchor-unlinked")));
                plugin.playSound(player, "sounds.link-deleted");
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7&l« &c&lSH &8&l» &7Cette ancre n'est pas liée."));
            }
            player.closeInventory();

        } else if (plugin.isSsbHookEnabled() && (clicked.getType() == accessPublicMat || clicked.getType() == accessIslandMat)) {
            guiAnchor.setIslandOnly(!guiAnchor.isIslandOnly());
            String status = guiAnchor.isIslandOnly() ?
                config.getString("messages.status-island") :
                config.getString("messages.status-public");
            String message = config.getString("messages.anchor-access-changed").replace("%status%", status);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            plugin.playSound(player, "sounds.toggle-access");
            plugin.openAnchorGui(player, guiAnchor);

        } else if (clicked.getType() == orientationMat) {
            guiAnchor.setOrientation(guiAnchor.getOrientation().next());
            plugin.playSound(player, "sounds.toggle-access");
            plugin.openAnchorGui(player, guiAnchor);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        // Glisser des objets sur le menu les y laisserait : ils disparaîtraient à la fermeture
        if (event.getInventory().getHolder() instanceof AnchorInventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        Block block = event.getBlock();
        if (block.getType() != Material.RESPAWN_ANCHOR) return;
        TeleportAnchor anchor = plugin.getAnchorAt(block.getLocation());
        if (anchor != null) {
            block.getWorld().dropItemNaturally(block.getLocation(), plugin.createAnchorItem(1));
            UUID linkedId = plugin.getLinkedAnchors().get(anchor.getId());
            if (linkedId != null) {
                plugin.getLinkedAnchors().remove(linkedId);
                plugin.getLinkedAnchors().remove(anchor.getId());
            }
            plugin.getAnchors().remove(anchor.getId());
            plugin.removeAnchorFromChunkMap(anchor);
            event.setDropItems(false);
            Player breaker = event.getPlayer();
            FileConfiguration config = plugin.getPluginConfig();
            if (breaker != null)
                breaker.sendMessage(ChatColor.translateAlternateColorCodes('&', config.getString("messages.anchor-broken")));
        }
    }

    private void playTeleportEffects(Player player, Location location) {
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.PORTAL, location.clone().add(0, 0.5, 0), 50, 0.5, 0.5, 0.5, 0.5);
            world.playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.2f);
        }
    }
}

record ChunkKey(UUID world, int x, int z) {
    static ChunkKey of(Location location) {
        return new ChunkKey(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
}

class TeleportAnchor {
    private final UUID id;
    private final Location location;
    private boolean islandOnly;
    private AnchorOrientation orientation;

    public TeleportAnchor(Location location) {
        this.id = UUID.randomUUID();
        this.location = location;
        this.islandOnly = false;
        this.orientation = AnchorOrientation.DEFAULT;
    }

    public TeleportAnchor(UUID id, Location location, boolean islandOnly, AnchorOrientation orientation) {
        this.id = id;
        this.location = location;
        this.islandOnly = islandOnly;
        this.orientation = orientation != null ? orientation : AnchorOrientation.DEFAULT;
    }

    public UUID getId() {
        return id;
    }

    public Location getLocation() {
        return location;
    }

    public boolean isIslandOnly() {
        return islandOnly;
    }

    public void setIslandOnly(boolean islandOnly) {
        this.islandOnly = islandOnly;
    }

    public AnchorOrientation getOrientation() {
        return orientation;
    }

    public void setOrientation(AnchorOrientation orientation) {
        this.orientation = orientation;
    }
}

class AnchorInventoryHolder implements InventoryHolder {
    private final TeleportAnchor anchor;

    public AnchorInventoryHolder(TeleportAnchor anchor) {
        this.anchor = anchor;
    }

    public TeleportAnchor getAnchor() {
        return anchor;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}

enum AnchorOrientation {
    DEFAULT, NORTH, SOUTH, EAST, WEST;

    public AnchorOrientation next() {
        int nextOrd = (this.ordinal() + 1) % values().length;
        return values()[nextOrd];
    }

    public float getYaw() {
        switch (this) {
            case NORTH:
                return 180f;
            case SOUTH:
                return 0f;
            case EAST:
                return -90f;
            case WEST:
                return 90f;
            default:
                return 0f;
        }
    }
}
