package org.swim.dungeonTrials.service;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockVector;
import org.swim.dungeonTrials.config.ConfigManager;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DynamicLightService {

    private static final int FALLBACK_LIGHT_LEVEL = 13;

    /**
     * Ring offsets used when the player's head block is solid (CENTER cannot
     * place). Y is intentionally 0 (head level, not head+1) because the 8
     * neighbor positions are the visible gaps the player is looking INTO —
     * placing them at head+1 would sit one block above the solid head block
     * and be invisible to the player.
     */
    private static final int[][] RING_OFFSETS = {
            {-1, 0, -1}, {0, 0, -1}, {1, 0, -1},
            {-1, 0, 0}, {1, 0, 0},
            {-1, 0, 1}, {0, 0, 1}, {1, 0, 1}
    };

    private final JavaPlugin plugin;
    private final ConfigManager configManager;

    private final Map<UUID, LightPlacement> activeLights = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> holdingState = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pendingRefreshes = new ConcurrentHashMap<>();
    private volatile Light lightData;
    private volatile Light fallbackLightData;

    public DynamicLightService(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        refreshLightData();
    }

    public void shutdown() {
        cancelAndRemoveAll();
    }

    public void onConfigReload() {
        refreshLightData();
        if (!configManager.dynamicLightSourceEnabled()) {
            cancelAndRemoveAll();
            plugin.getLogger().info("Dynamic light source disabled by config reload");
            return;
        }
        for (UUID uuid : activeLights.keySet().toArray(new UUID[0])) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                forceRemove(uuid);
                continue;
            }
            placeOrUpdate(p, activeLights.get(uuid));
        }
    }

    private void cancelAndRemoveAll() {
        for (BukkitTask task : pendingRefreshes.values().toArray(new BukkitTask[0])) {
            task.cancel();
        }
        pendingRefreshes.clear();
        for (UUID uuid : activeLights.keySet().toArray(new UUID[0])) {
            removeLightFor(uuid);
        }
        activeLights.clear();
        holdingState.clear();
    }

    private void refreshLightData() {
        this.lightData = WorldService.createLightData(configManager.dynamicLightSourceLevel());
        this.fallbackLightData = WorldService.createLightData(FALLBACK_LIGHT_LEVEL);
    }

    public void scheduleRefresh(Player p) {
        if (p == null) return;
        if (!plugin.isEnabled()) return;
        UUID uuid = p.getUniqueId();
        if (pendingRefreshes.containsKey(uuid)) {
            return;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingRefreshes.remove(uuid);
            if (!p.isOnline()) {
                forceRemove(uuid);
                return;
            }
            refreshLight(p);
        }, 1L);
        pendingRefreshes.put(uuid, task);
    }

    public void refreshLight(Player p) {
        if (p == null || !p.isOnline() || p.isDead()) {
            forceRemove(p == null ? null : p.getUniqueId());
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR) {
            forceRemove(p.getUniqueId());
            return;
        }
        if (!configManager.dynamicLightSourceEnabled()) {
            forceRemove(p.getUniqueId());
            return;
        }

        UUID uuid = p.getUniqueId();
        boolean holding = isHoldingTorch(p);
        Boolean prev = holdingState.get(uuid);
        LightPlacement existing = activeLights.get(uuid);

        if (holding) {
            if (prev == null || !prev) {
                holdingState.put(uuid, Boolean.TRUE);
                placeOrUpdate(p, existing);
                return;
            }
            updateIfMoved(p, existing);
        } else {
            if (prev != null && prev) {
                holdingState.put(uuid, Boolean.FALSE);
                removeLightFor(uuid);
            }
        }
    }

    private void placeOrUpdate(Player p, LightPlacement existing) {
        UUID uuid = p.getUniqueId();
        if (existing != null) {
            removeLightFor(uuid);
        }
        World world = p.getWorld();
        Block head = p.getLocation().add(0, 1, 0).getBlock();
        BlockVector headPos = new BlockVector(head.getX(), head.getY(), head.getZ());

        if (head.getType() == Material.AIR) {
            head.setBlockData(lightData, false);
            Set<BlockVector> placed = new HashSet<>();
            placed.add(headPos);
            activeLights.put(uuid, new LightPlacement(world, LightPlacement.Mode.CENTER, placed));
        } else {
            Set<BlockVector> placed = new HashSet<>();
            for (int[] off : RING_OFFSETS) {
                int rx = head.getX() + off[0];
                int ry = head.getY() + off[1];
                int rz = head.getZ() + off[2];
                Block b = world.getBlockAt(rx, ry, rz);
                if (b.getType() == Material.AIR) {
                    b.setBlockData(fallbackLightData, false);
                    placed.add(new BlockVector(rx, ry, rz));
                }
            }
            activeLights.put(uuid, new LightPlacement(world, LightPlacement.Mode.RING, placed));
        }
    }

    private void updateIfMoved(Player p, LightPlacement existing) {
        if (existing == null) {
            placeOrUpdate(p, null);
            return;
        }
        World world = p.getWorld();
        if (existing.world() != world) {
            removeLightFor(p.getUniqueId());
            placeOrUpdate(p, null);
            return;
        }

        Block head = p.getLocation().add(0, 1, 0).getBlock();
        BlockVector headPos = new BlockVector(head.getX(), head.getY(), head.getZ());

        Set<BlockVector> currentExpected;
        LightPlacement.Mode currentMode;
        if (head.getType() == Material.AIR) {
            currentExpected = Set.of(headPos);
            currentMode = LightPlacement.Mode.CENTER;
        } else {
            currentExpected = ringPositions(head.getX(), head.getY(), head.getZ());
            currentMode = LightPlacement.Mode.RING;
        }

        if (existing.mode() == currentMode && existing.positions().equals(currentExpected)) {
            boolean allIntact = true;
            for (BlockVector pos : currentExpected) {
                if (!isBlockLightAt(world, pos)) {
                    allIntact = false;
                    break;
                }
            }
            if (allIntact) {
                return;
            }
        }

        Set<BlockVector> prevSet = existing.positions();
        for (BlockVector pos : prevSet) {
            if (currentExpected.contains(pos)) continue;
            Block b = world.getBlockAt(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
            if (b.getType() == Material.LIGHT) {
                b.setType(Material.AIR, false);
            }
        }

        Set<BlockVector> newPlaced = new HashSet<>();
        for (BlockVector pos : currentExpected) {
            if (prevSet.contains(pos)) {
                if (isBlockLightAt(world, pos)) {
                    newPlaced.add(pos);
                }
                continue;
            }
            Block b = world.getBlockAt(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
            if (b.getType() == Material.AIR) {
                b.setBlockData(currentMode == LightPlacement.Mode.CENTER ? lightData : fallbackLightData, false);
                newPlaced.add(pos);
            }
        }

        if (newPlaced.isEmpty()) {
            activeLights.remove(p.getUniqueId());
        } else {
            activeLights.put(p.getUniqueId(), new LightPlacement(world, currentMode, newPlaced));
        }
    }

    private boolean isBlockLightAt(World world, BlockVector pos) {
        return world.getBlockAt(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ()).getType() == Material.LIGHT;
    }

    private Set<BlockVector> ringPositions(int hx, int hy, int hz) {
        Set<BlockVector> out = new HashSet<>(8);
        for (int[] off : RING_OFFSETS) {
            out.add(new BlockVector(hx + off[0], hy + off[1], hz + off[2]));
        }
        return out;
    }

    public void forceRemove(UUID uuid) {
        if (uuid == null) return;
        BukkitTask pending = pendingRefreshes.remove(uuid);
        if (pending != null) {
            pending.cancel();
        }
        holdingState.remove(uuid);
        removeLightFor(uuid);
    }

    public void removeLightFor(UUID uuid) {
        if (uuid == null) return;
        LightPlacement lp = activeLights.remove(uuid);
        if (lp == null) return;
        if (lp.world() == null) return;
        for (BlockVector pos : lp.positions()) {
            Block block = lp.world().getBlockAt(pos.getBlockX(), pos.getBlockY(), pos.getBlockZ());
            if (block.getType() == Material.LIGHT) {
                block.setType(Material.AIR, false);
            }
        }
    }

    public void clearZombieLightAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        Block head = loc.clone().add(0, 1, 0).getBlock();
        if (head.getType() == Material.LIGHT) {
            head.setType(Material.AIR, false);
        }
        for (int[] off : RING_OFFSETS) {
            Block b = loc.getWorld().getBlockAt(
                    head.getX() + off[0], head.getY() + off[1], head.getZ() + off[2]);
            if (b.getType() == Material.LIGHT) {
                b.setType(Material.AIR, false);
            }
        }
    }

    private boolean isHoldingTorch(Player p) {
        boolean mainhand = configManager.dynamicLightSourceMainhand();
        boolean offhand = configManager.dynamicLightSourceOffhand();
        if (mainhand) {
            ItemStack main = p.getInventory().getItemInMainHand();
            if (main.getType() == Material.TORCH) return true;
        }
        if (offhand) {
            ItemStack off = p.getInventory().getItemInOffHand();
            return off.getType() == Material.TORCH;
        }
        return false;
    }

    private record LightPlacement(World world, Mode mode, Set<BlockVector> positions) {
        enum Mode {CENTER, RING}
    }
}
