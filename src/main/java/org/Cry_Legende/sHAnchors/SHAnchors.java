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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class SHAnchors extends JavaPlugin {
    private static SHAnchors instance;
    private FileConfiguration config;
    private final Map<UUID, TeleportAnchor> anchors = new HashMap<>();
    private final Map<UUID, UUID> linkedAnchors = new HashMap<>();
    private final Map<UUID, UUID> anchorSelectionMode = new HashMap<>();
    private final Map<UUID, Long> teleportCooldown = new HashMap<>();
    private final Map<Chunk, Set<UUID>> anchorsByChunk = new HashMap<>();

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
        cleanupInvalidAnchors();

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (TeleportAnchor anchor : new ArrayList<>(anchors.values())) {
                World world = anchor.getLocation().getWorld();
                if (world == null) continue;

                Location loc = anchor.getLocation();
                if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
                if (!linkedAnchors.containsKey(anchor.getId())) continue;
                if (loc.getBlock().getType() != Material.RESPAWN_ANCHOR) continue;

                Collection<Player> nearbyPlayers = world.getNearbyEntities(loc, 16, 16, 16, entity -> entity instanceof Player)
                    .stream()
                    .map(entity -> (Player) entity)
                    .collect(Collectors.toList());

                if (!nearbyPlayers.isEmpty()) {
                    Location particleLoc = loc.clone().add(0.5, 1.2, 0.5);
                    Particle.DustOptions dustOptions = anchor.isIslandOnly() ? PARTICLE_ISLAND_ONLY : PARTICLE_PUBLIC;
                    world.spawnParticle(Particle.DUST, particleLoc, 1, 0, 0, 0, 0, dustOptions);
                }
            }
        }, 0L, 20L);

        long cleanupIntervalTicks = 20L * 60 * 30;
        Bukkit.getScheduler().runTaskTimer(this, this::cleanupOrphanedAnchorsLightweight, cleanupIntervalTicks, cleanupIntervalTicks);
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

    public Map<Chunk, Set<UUID>> getAnchorsByChunk() {
        return anchorsByChunk;
    }

    public boolean isSsbHookEnabled() {
        return ssbHookEnabled;
    }

    public static String getBypassPermission() {
        return BYPASS_PERMISSION;
    }

    public void addAnchorToChunkMap(TeleportAnchor anchor) {
        anchorsByChunk.computeIfAbsent(anchor.getLocation().getChunk(), k -> new HashSet<>()).add(anchor.getId());
    }

    public void removeAnchorFromChunkMap(TeleportAnchor anchor) {
        Set<UUID> chunkAnchors = anchorsByChunk.get(anchor.getLocation().getChunk());
        if (chunkAnchors != null) {
            chunkAnchors.remove(anchor.getId());
            if (chunkAnchors.isEmpty()) {
                anchorsByChunk.remove(anchor.getLocation().getChunk());
            }
        }
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
        Chunk chunk = location.getChunk();
        Set<UUID> chunkAnchors = anchorsByChunk.get(chunk);
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

    private void loadAnchors() {
        File file = new File(getDataFolder(), "anchors.yml");
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            try {
                UUID id = UUID.fromString(key);
                Location loc = section.getLocation("location");
                boolean islandOnly = section.getBoolean("islandOnly", false);
                String orientStr = section.getString("orientation", "DEFAULT");
                AnchorOrientation orientation = AnchorOrientation.valueOf(orientStr);
                if (loc != null) {
                    TeleportAnchor anchor = new TeleportAnchor(id, loc, islandOnly, orientation);
                    anchors.put(id, anchor);
                    addAnchorToChunkMap(anchor);
                    String linkedIdStr = section.getString("linked");
                    if (linkedIdStr != null) {
                        linkedAnchors.put(id, UUID.fromString(linkedIdStr));
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Erreur lors du chargement de l'ancre " + key + ": " + e.getMessage());
            }
        }
    }

    private void saveAnchors() {
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
        try {
            yaml.save(new File(getDataFolder(), "anchors.yml"));
        } catch (IOException e) {
            getLogger().severe("Impossible de sauvegarder les ancres dans anchors.yml: " + e.getMessage());
        }
    }

    private void cleanupInvalidAnchors() {
        new ArrayList<>(anchors.values()).forEach(anchor -> {
            if (anchor.getLocation().getBlock().getType() != Material.RESPAWN_ANCHOR) {
                cleanupInvalidAnchor(anchor);
            }
        });
    }

    private void cleanupOrphanedAnchorsLightweight() {
        int cleanedCount = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                Set<UUID> chunkAnchors = anchorsByChunk.get(chunk);
                if (chunkAnchors != null) {
                    for (UUID anchorId : new HashSet<>(chunkAnchors)) {
                        TeleportAnchor anchor = anchors.get(anchorId);
                        if (anchor != null && anchor.getLocation().getBlock().getType() != Material.RESPAWN_ANCHOR) {
                            cleanupInvalidAnchor(anchor);
                            cleanedCount++;
                        }
                    }
                }
            }
        }
        if (cleanedCount > 0) {
            getLogger().info("Nettoyage léger terminé. " + cleanedCount + " ancre(s) orpheline(s) supprimée(s).");
        }
    }

    public void playSound(Player player, String configKey) {
        ConfigurationSection sec = config.getConfigurationSection(configKey);
        if (sec == null) return;
        try {
            Sound sound = Sound.valueOf(sec.getString("sound").toUpperCase());
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
            plugin.getLinkedAnchors().remove(originAnchor.getId());
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
        String guiTitle = ChatColor.translateAlternateColorCodes('&', config.getString("gui.title", "&8Gestion de l'Ancre"));
        if (!event.getView().getTitle().equals(guiTitle)) return;
        event.setCancelled(true);
        if (!(event.getInventory().getHolder() instanceof AnchorInventoryHolder)) return;

        AnchorInventoryHolder holder = (AnchorInventoryHolder) event.getInventory().getHolder();
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
