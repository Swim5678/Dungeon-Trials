package org.swim.dungeonTrials.service;

import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.WorldData;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.swim.dungeonTrials.config.ConfigManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class AdvancementService {

    public static final String NAMESPACE = "dungeontrials";
    public static final String DATAPACK_NAME = "dungeontrials";
    public static final String DATAPACK_SOURCE_DIR = "datapacks/" + DATAPACK_NAME;
    public static final String RESOURCE_PACK_SOURCE_DIR = "dungeontrials-resourcepack";
    public static final String RESOURCE_PACK_OUTPUT_NAME = "dungeontrials-resourcepack.zip";

    private static final String[] DATAPACK_RESOURCES = {
            "pack.mcmeta",
            "data/dungeontrials/advancement/root.json",
            "data/dungeontrials/advancement/first_clear_l1.json",
            "data/dungeontrials/advancement/first_clear_l2.json",
            "data/dungeontrials/advancement/first_clear_l3.json",
            "data/dungeontrials/advancement/first_clear_l4.json",
            "data/dungeontrials/advancement/first_clear_l5.json",
            "data/dungeontrials/advancement/first_clear_infinite.json"
    };

    private static final String[] RESOURCE_PACK_RESOURCES = {
            "pack.mcmeta",
            "assets/dungeontrials/lang/en_us.json",
            "assets/dungeontrials/lang/zh_tw.json"
    };

    private static final long RELOAD_TIMEOUT_SECONDS = 15L;

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private volatile boolean ready;

    public AdvancementService(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void install() {
        if (this.ready) return;

        String worldName = resolveDefaultWorldName();
        if (worldName == null) {
            plugin.getLogger().warning("[Advancement] No loaded world; advancement system disabled.");
            return;
        }
        plugin.getLogger().info("[Advancement] Installing datapack for world: " + worldName);

        try {
            copyDatapack(worldName);
        } catch (IOException e) {
            plugin.getLogger().warning("Advancement datapack copy failed: " + e.getMessage());
            return;
        }
        enableDatapack();
        reloadServer();

        NamespacedKey rootKey = new NamespacedKey(NAMESPACE, "root");
        if (Bukkit.getAdvancement(rootKey) != null) {
            plugin.getLogger().info("[Advancement] Datapack loaded successfully.");
            this.ready = true;
        } else {
            plugin.getLogger().warning("[Advancement] Root advancement is NOT registered after reload. Check server log for JSON parse errors.");
        }

        extractResourcePack();
        logResourcePackInstructions();
    }

    public void awardLevelClear(Player player, int oldLevel) {
        if (!this.ready || player == null) return;
        if (oldLevel < 1 || oldLevel > 5) return;
        awardCriteria(player, "first_clear_l" + oldLevel, "clear_l" + oldLevel);
    }

    public void awardInfinite(Player player) {
        if (!this.ready || player == null) return;
        awardCriteria(player, "first_clear_infinite", "clear_infinite");
    }

    private void awardCriteria(Player player, String advancementId, String criterion) {
        if (!player.isOnline()) return;
        NamespacedKey key = new NamespacedKey(NAMESPACE, advancementId);
        Advancement adv = Bukkit.getAdvancement(key);
        if (adv == null) {
            plugin.getLogger().warning("Advancement not registered: " + key);
            return;
        }
        AdvancementProgress progress = player.getAdvancementProgress(adv);
        if (progress.isDone()) return;
        progress.awardCriteria(criterion);
    }

    private String resolveDefaultWorldName() {
        var worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return null;
        World w = worlds.get(0);
        return w.getName();
    }

    private Path resolveResourcePackOutput() {
        return plugin.getDataFolder().toPath()
                .resolve("resourcepack")
                .resolve(RESOURCE_PACK_OUTPUT_NAME);
    }

    private void copyDatapack(String worldName) throws IOException {
        Path target = new File(Bukkit.getWorldContainer(), worldName + "/datapacks/" + DATAPACK_NAME).toPath();
        Files.createDirectories(target);
        for (String rel : DATAPACK_RESOURCES) {
            String resourcePath = DATAPACK_SOURCE_DIR + "/" + rel;
            Path dest = target.resolve(rel);
            Files.createDirectories(dest.getParent());
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in == null) {
                    plugin.getLogger().warning("Datapack resource missing in jar: " + resourcePath);
                    continue;
                }
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void enableDatapack() {
        try {
            String cmd = "datapack enable \"file/" + DATAPACK_NAME + "\"";
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to enable datapack: " + e.getMessage());
        }
    }

    private void reloadServer() {
        try {
            CraftServer craft = (CraftServer) Bukkit.getServer();
            MinecraftServer nms = craft.getServer();
            PackRepository packRepository = nms.getPackRepository();
            WorldData worldData = nms.getWorldData();

            Collection<String> selectedIds = packRepository.getSelectedIds();
            Collection<String> disabled = worldData.getDataConfiguration().dataPacks().getDisabled();

            packRepository.reload(true);

            Collection<String> newSelected = new ArrayList<>(selectedIds);
            for (String packId : packRepository.getAvailableIds()) {
                if (!disabled.contains(packId) && !newSelected.contains(packId)) {
                    newSelected.add(packId);
                }
            }

            nms.reloadResources(newSelected, ServerResourcesReloadedEvent.Cause.PLUGIN)
                    .get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to reload server resources: " + e.getMessage());
        }
    }

    private void extractResourcePack() {
        Path target = resolveResourcePackOutput();
        try {
            Files.createDirectories(target.getParent());
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                for (String rel : RESOURCE_PACK_RESOURCES) {
                    String resourcePath = RESOURCE_PACK_SOURCE_DIR + "/" + rel;
                    addResourceToZip(zip, resourcePath, rel);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to extract resource pack: " + e.getMessage());
        }
    }

    private void addResourceToZip(ZipOutputStream zip, String resourcePath, String entryName) throws IOException {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                plugin.getLogger().warning("Resource pack resource missing in jar: " + resourcePath);
                return;
            }
            zip.putNextEntry(new ZipEntry(entryName));
            in.transferTo(zip);
            zip.closeEntry();
        }
    }

    private void logResourcePackInstructions() {
        Path zip = resolveResourcePackOutput();
        plugin.getLogger().info("[Advancement] Resource pack extracted to " + zip);
        plugin.getLogger().info("[Advancement] To enable client translations, set in server.properties:");
        plugin.getLogger().info("  resource-pack=plugins/DungeonTrials/resourcepack/" + RESOURCE_PACK_OUTPUT_NAME);
        plugin.getLogger().info("  require-resource-pack=false");
    }
}
