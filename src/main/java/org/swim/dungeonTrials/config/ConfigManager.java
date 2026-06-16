package org.swim.dungeonTrials.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import org.swim.dungeonTrials.model.MobSpawnRule;
import org.swim.dungeonTrials.model.RewardConfig;
import org.swim.dungeonTrials.model.RewardEntry;
import org.swim.dungeonTrials.model.SpawnProfile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ConfigManager {

    public static final String DEFAULT_STRUCTURE = "trial_chambers";
    public static final String DEFAULT_WORLD_STRATEGY = "shared";
    public static final String DEFAULT_SHARED_WORLD_NAME = "dungeon_shared";
    public static final int DEFAULT_SHARED_GRID_SIZE = 1000;
    public static final int DEFAULT_MAX_VISITORS = 3;
    public static final String FALLBACK_STRUCTURE_ID = "trial_chambers";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final Set<String> DEPRECATED_KEYS = Set.of(
            "settings.distance-threshold",
            "settings.max-spawn-radius",
            "settings.bossbar-style",
            "settings.shell"
    );
    private static final java.util.regex.Pattern LOCALE_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final List<String> BUNDLED_MESSAGE_RESOURCES = List.of(
            "messages-en.yml",
            "messages-zh-tw.yml"
    );
    private final JavaPlugin plugin;
    private final Map<String, Map<Integer, LevelConfig>> perStructureLevels = new HashMap<>();
    private final Map<Integer, LevelConfig> levelConfigs = new HashMap<>();
    private String mainWorld = "overworld";
    private String locale = "default";
    private String defaultStructure = DEFAULT_STRUCTURE;
    private String worldStrategyMode = DEFAULT_WORLD_STRATEGY;
    private int sharedGridSize = DEFAULT_SHARED_GRID_SIZE;
    private String sharedWorldName = DEFAULT_SHARED_WORLD_NAME;
    private int maxVisitors = DEFAULT_MAX_VISITORS;
    private boolean dynamicLightEnabled = true;
    private int dynamicLightLevel = 14;
    private boolean dynamicLightMainhand = true;
    private boolean dynamicLightOffhand = true;
    private long topCacheSeconds = 120;
    private RewardConfig rewardConfig = RewardConfig.EMPTY;
    private volatile ConfigurationSection messages;
    private volatile ConfigurationSection defaultMessages;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private static LevelConfig highestLevelAtOrBelow(Map<Integer, LevelConfig> source, int level) {
        if (source == null || source.isEmpty()) return null;
        LevelConfig best = null;
        int bestKey = Integer.MIN_VALUE;
        for (Map.Entry<Integer, LevelConfig> e : source.entrySet()) {
            if (e.getKey() <= level && e.getKey() > bestKey) {
                best = e.getValue();
                bestKey = e.getKey();
            }
        }
        return best;
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 1.0;
    }

    public void load() {
        plugin.saveDefaultConfig();
        mergeConfigDefaults();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        mainWorld = config.getString("settings.main-world", "overworld");
        locale = config.getString("settings.locale", "default");
        locale = locale.trim();
        if (locale.isEmpty()) locale = "default";

        defaultStructure = config.getString("settings.default-structure", DEFAULT_STRUCTURE);
        if (defaultStructure.isBlank()) {
            defaultStructure = DEFAULT_STRUCTURE;
        }

        ConfigurationSection strategySection = config.getConfigurationSection("settings.world-strategy");
        if (strategySection != null) {
            String mode = strategySection.getString("mode", DEFAULT_WORLD_STRATEGY);
            if (mode.isBlank()) {
                mode = DEFAULT_WORLD_STRATEGY;
            }
            worldStrategyMode = mode.trim().toLowerCase();
            ConfigurationSection sharedSection = strategySection.getConfigurationSection("shared");
            if (sharedSection != null) {
                int rawGridSize = sharedSection.getInt("grid-size", DEFAULT_SHARED_GRID_SIZE);
                if (rawGridSize < 64) {
                    plugin.getLogger().warning("Invalid settings.world-strategy.shared.grid-size "
                            + rawGridSize + ", clamping to 64");
                }
                sharedGridSize = Math.max(64, rawGridSize);
                sharedWorldName = sharedSection.getString("world-name", DEFAULT_SHARED_WORLD_NAME);
                if (sharedWorldName.isBlank()) {
                    sharedWorldName = DEFAULT_SHARED_WORLD_NAME;
                }
            }
        }

        int rawMaxVisitors = config.getInt("settings.max-visitors", DEFAULT_MAX_VISITORS);
        if (rawMaxVisitors < 1) {
            plugin.getLogger().warning("Invalid settings.max-visitors " + rawMaxVisitors
                    + ", clamping to 1");
        }
        maxVisitors = Math.max(1, rawMaxVisitors);

        levelConfigs.clear();
        perStructureLevels.clear();
        ConfigurationSection levelsSection = config.getConfigurationSection("levels");
        if (levelsSection != null) {
            for (String key : levelsSection.getKeys(false)) {
                ConfigurationSection topSection = levelsSection.getConfigurationSection(key);
                if (topSection == null) continue;

                if (looksLikeStructureSection(key, topSection)) {
                    parseStructureLevels(key, topSection);
                } else {
                    int level;
                    try {
                        level = Integer.parseInt(key);
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Skipping level entry with non-numeric key: " + key);
                        continue;
                    }
                    LevelConfig cfg = parseLevelSection(level, topSection);
                    levelConfigs.put(level, cfg);
                }
            }
        }

        if (levelConfigs.isEmpty() && perStructureLevels.isEmpty()) {
            plugin.getLogger().warning("No valid levels loaded from config.yml");
        }

        ConfigurationSection dynLightSection = config.getConfigurationSection("settings.dynamic-light-source");
        if (dynLightSection != null) {
            dynamicLightEnabled = dynLightSection.getBoolean("enabled", true);
            int rawLevel = dynLightSection.getInt("light-level", 14);
            if (rawLevel < 0 || rawLevel > 15) {
                plugin.getLogger().warning("Invalid dynamic-light-source.light-level " + rawLevel
                        + ", clamping to [0, 15]");
            }
            dynamicLightLevel = Math.clamp(rawLevel, 0, 15);
            dynamicLightMainhand = dynLightSection.getBoolean("mainhand", true);
            dynamicLightOffhand = dynLightSection.getBoolean("offhand", true);
        }

        long rawCache = config.getLong("settings.top-cache-seconds", 120L);
        if (rawCache < 1) {
            plugin.getLogger().warning("Invalid settings.top-cache-seconds " + rawCache
                    + ", clamping to 1");
        }
        topCacheSeconds = Math.max(1L, rawCache);

        rewardConfig = parseRewardConfig(config);

        loadMessages();
    }

    public void reload() {
        load();
    }

    private void mergeConfigDefaults() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) return;

        YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(configFile);

        YamlConfiguration defaultConfig;
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) return;
            defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load bundled config.yml: " + e.getMessage());
            return;
        }

        boolean changed = false;
        for (String key : defaultConfig.getKeys(true)) {
            if (defaultConfig.isConfigurationSection(key)) continue;
            if (!userConfig.contains(key)) {
                userConfig.set(key, defaultConfig.get(key));
                changed = true;
            }
        }

        for (String key : DEPRECATED_KEYS) {
            if (userConfig.contains(key)) {
                userConfig.set(key, null);
                plugin.getLogger().info("Removed deprecated config key: " + key);
                changed = true;
            }
        }

        if (changed) {
            try {
                userConfig.save(configFile);
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to save merged config.yml: " + e.getMessage());
            }
        }
    }

    private void loadMessages() {
        String resourceName = resolveMessagesResource(locale);
        File messagesFile = new File(plugin.getDataFolder(), resourceName);

        try (InputStream defaultStream = plugin.getResource("messages-en.yml")) {
            if (defaultStream != null) {
                defaultMessages = unwrap(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaultStream, StandardCharsets.UTF_8)));
            } else {
                defaultMessages = null;
            }
        } catch (IOException e) {
            defaultMessages = null;
        }

        ensureBundledMessageResources();

        YamlConfiguration current = new YamlConfiguration();

        boolean createdFromBundled = false;
        if (!messagesFile.exists()) {
            if (plugin.getResource(resourceName) != null) {
                try {
                    plugin.saveResource(resourceName, false);
                    createdFromBundled = true;
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Failed to save bundled " + resourceName
                            + ": " + ex.getMessage() + " — falling back to messages-en.yml");
                }
            }
            if (!createdFromBundled && plugin.getResource("messages-en.yml") != null) {
                File fallbackFile = new File(plugin.getDataFolder(), "messages-en.yml");
                if (!fallbackFile.exists()) {
                    plugin.saveResource("messages-en.yml", false);
                }
                createdFromBundled = true;
            }
        }

        if (messagesFile.exists()) {
            try {
                current = YamlConfiguration.loadConfiguration(messagesFile);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load " + resourceName
                        + ": " + e.getMessage() + " — falling back to bundled defaults");
            }
        }

        if (defaultMessages != null) {
            ConfigurationSection target = unwrap(current);
            boolean changed = false;
            for (String key : defaultMessages.getKeys(true)) {
                if (!target.contains(key)) {
                    target.set(key, defaultMessages.get(key));
                    changed = true;
                }
            }
            if (changed && !createdFromBundled) {
                try {
                    current.save(messagesFile);
                    current = YamlConfiguration.loadConfiguration(messagesFile);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to save merged " + resourceName
                            + ": " + e.getMessage() + " — using in-memory defaults only");
                }
            }
        }

        messages = unwrap(current);
    }

    private String resolveMessagesResource(String localeTag) {
        if (localeTag == null) return "messages-en.yml";
        String trimmed = localeTag.trim();
        if (trimmed.isEmpty()
                || trimmed.equalsIgnoreCase("default")
                || trimmed.equalsIgnoreCase("en")
                || trimmed.equalsIgnoreCase("english")) {
            return "messages-en.yml";
        }
        if (!LOCALE_PATTERN.matcher(trimmed).matches()) {
            plugin.getLogger().warning("Invalid settings.locale '"
                    + localeTag + "' — must match [A-Za-z0-9_-]+, falling back to messages-en.yml");
            return "messages-en.yml";
        }
        return "messages-" + trimmed + ".yml";
    }

    private void ensureBundledMessageResources() {
        File dataFolder = plugin.getDataFolder();
        for (String resource : BUNDLED_MESSAGE_RESOURCES) {
            File target = new File(dataFolder, resource);
            if (target.exists()) continue;
            if (plugin.getResource(resource) == null) {
                plugin.getLogger().warning("Bundled message resource missing from jar: " + resource);
                continue;
            }
            try {
                plugin.saveResource(resource, false);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Failed to save bundled " + resource
                        + ": " + ex.getMessage());
            }
        }
    }

    private ConfigurationSection unwrap(FileConfiguration config) {
        if (config.isConfigurationSection("messages")) {
            return config.getConfigurationSection("messages");
        }
        return config;
    }

    public Component getMessage(String key, String... placeholders) {
        String raw = messages.getString(key);
        if (raw == null && defaultMessages != null) {
            raw = defaultMessages.getString(key);
        }
        if (raw == null) {
            raw = "<red>Missing message: {key}</red>";
            String[] expanded = new String[placeholders.length + 2];
            expanded[0] = "key";
            expanded[1] = key;
            System.arraycopy(placeholders, 0, expanded, 2, placeholders.length);
            placeholders = expanded;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1];
            if (value == null) continue;
            raw = raw.replace("{" + placeholders[i] + "}", value);
        }
        return MINI.deserialize(raw);
    }

    public LevelConfig getEffectiveLevelConfig(int level, String structureId) {
        String effectiveId = (structureId != null) ? structureId : FALLBACK_STRUCTURE_ID;
        Map<Integer, LevelConfig> perLevel = perStructureLevels.get(effectiveId);
        if (perLevel != null) {
            LevelConfig config = perLevel.get(level);
            if (config != null) return config;
            return highestLevelAtOrBelow(perLevel, level);
        }
        return highestLevelAtOrBelow(levelConfigs, level);
    }

    private boolean looksLikeStructureSection(String outerKey, ConfigurationSection section) {
        if (outerKey.matches("-?\\d+")) return false;
        if (section.getKeys(false).isEmpty()) return false;
        for (String childKey : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(childKey);
            if (child == null) {
                return false;
            }
        }
        return true;
    }

    private void parseStructureLevels(String structureId, ConfigurationSection section) {
        Map<Integer, LevelConfig> byLevel = new HashMap<>();
        for (String childKey : section.getKeys(false)) {
            int level;
            try {
                level = Integer.parseInt(childKey);
            } catch (NumberFormatException e) {
                continue;
            }
            ConfigurationSection child = section.getConfigurationSection(childKey);
            if (child == null) continue;
            LevelConfig cfg = parseLevelSection(level, child);
            byLevel.put(level, cfg);
        }
        if (byLevel.isEmpty()) {
            plugin.getLogger().warning("Structure '" + structureId + "' has no valid levels, skipping");
            return;
        }
        perStructureLevels.put(structureId, byLevel);
    }

    private LevelConfig parseLevelSection(int level, ConfigurationSection section) {
        long clearRequirementRaw = section.getLong("clear-requirement", 0L);
        int clearRequirement = (clearRequirementRaw > Integer.MAX_VALUE)
                ? Integer.MAX_VALUE
                : (int) clearRequirementRaw;

        List<MobSpawnRule> mobs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<?, ?> entry : section.getMapList("mobs")) {
            Object typeObj = entry.get("type");
            Object profileObj = entry.get("profile");
            if (typeObj == null || profileObj == null) {
                plugin.getLogger().warning("Level " + level + " has invalid mob entry (missing type/profile), skipping");
                continue;
            }
            EntityType type;
            try {
                type = EntityType.valueOf(typeObj.toString());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Level " + level + " has unknown mob type: " + typeObj);
                continue;
            }
            SpawnProfile profile;
            try {
                profile = SpawnProfile.valueOf(profileObj.toString());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Level " + level + " has unknown spawn profile: " + profileObj);
                continue;
            }
            String dedupKey = type.name() + ":" + profile.name();
            if (!seen.add(dedupKey)) {
                plugin.getLogger().warning("Level " + level + " has duplicate mob entry: " + dedupKey + ", skipping");
                continue;
            }
            mobs.add(new MobSpawnRule(type, profile));
        }

        if (mobs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Level " + level + " has no valid mob entries — every level must declare at least one valid mob in config.yml");
        }

        return new LevelConfig(level, clearRequirement, mobs);
    }

    public String mainWorld() {
        return mainWorld;
    }

    public String defaultStructure() {
        return defaultStructure;
    }

    public int maxVisitors() {
        return maxVisitors;
    }

    public String worldStrategyMode() {
        return worldStrategyMode;
    }

    public int sharedGridSize() {
        return sharedGridSize;
    }

    public String sharedWorldName() {
        return sharedWorldName;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean dynamicLightSourceEnabled() {
        return dynamicLightEnabled;
    }

    public int dynamicLightSourceLevel() {
        return dynamicLightLevel;
    }

    public boolean dynamicLightSourceMainhand() {
        return dynamicLightMainhand;
    }

    public boolean dynamicLightSourceOffhand() {
        return dynamicLightOffhand;
    }

    public long topCacheSeconds() {
        return topCacheSeconds;
    }

    public RewardConfig rewardConfig() {
        return rewardConfig;
    }

    private RewardConfig parseRewardConfig(FileConfiguration config) {
        ConfigurationSection rewards = config.getConfigurationSection("rewards");
        if (rewards == null) return RewardConfig.EMPTY;
        boolean enabled = rewards.getBoolean("enabled", false);
        if (!enabled) return RewardConfig.EMPTY;

        double xpMultiplier = 1.0;
        java.util.Map<org.bukkit.entity.EntityType, java.util.List<RewardEntry>> perKillDrops = new java.util.HashMap<>();
        java.util.Map<Integer, java.util.List<RewardEntry>> levelClearBonus = new java.util.HashMap<>();
        boolean infiniteEnabled = false;
        java.util.List<RewardEntry> infiniteBonus = new java.util.ArrayList<>();

        ConfigurationSection perKill = rewards.getConfigurationSection("per-kill");
        if (perKill != null) {
            xpMultiplier = Math.max(0.0, perKill.getDouble("xp-multiplier", 1.0));
            ConfigurationSection drops = perKill.getConfigurationSection("custom-drops");
            if (drops != null) {
                for (String mobName : drops.getKeys(false)) {
                    org.bukkit.entity.EntityType type;
                    try {
                        type = org.bukkit.entity.EntityType.valueOf(mobName);
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Unknown mob type in rewards.per-kill.custom-drops: " + mobName);
                        continue;
                    }
                    String path = "rewards.per-kill.custom-drops." + mobName;
                    java.util.List<RewardEntry> entries = parseEntryList(drops.getMapList(mobName), path);
                    if (!entries.isEmpty()) {
                        perKillDrops.put(type, entries);
                    }
                }
            }
        }

        ConfigurationSection clearBonus = rewards.getConfigurationSection("level-clear-bonus");
        if (clearBonus != null) {
            for (String key : clearBonus.getKeys(false)) {
                String normalized = key.toUpperCase().startsWith("L") ? key.substring(1) : key;
                int level;
                try {
                    level = Integer.parseInt(normalized);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid level key in rewards.level-clear-bonus: " + key);
                    continue;
                }
                String path = "rewards.level-clear-bonus." + key;
                java.util.List<RewardEntry> entries = parseEntryList(clearBonus.getMapList(key), path);
                if (!entries.isEmpty()) {
                    levelClearBonus.put(level, entries);
                }
            }
        }

        ConfigurationSection infinite = rewards.getConfigurationSection("infinite-bonus");
        if (infinite != null) {
            infiniteEnabled = infinite.getBoolean("enabled", false);
            java.util.List<RewardEntry> entries = parseEntryList(infinite.getMapList("entries"),
                    "rewards.infinite-bonus.entries");
            infiniteBonus.addAll(entries);
        }

        return new RewardConfig(true, xpMultiplier, perKillDrops, levelClearBonus, infiniteEnabled, infiniteBonus);
    }

    private java.util.List<RewardEntry> parseEntryList(java.util.List<? extends Map<?, ?>> raw, String path) {
        java.util.List<RewardEntry> out = new java.util.ArrayList<>();
        if (raw == null) return out;
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> map = raw.get(i);
            if (map == null) continue;
            Object matRaw = map.get("material");
            if (matRaw == null) {
                plugin.getLogger().warning("Missing 'material' in " + path + "[" + i + "]");
                continue;
            }
            org.bukkit.Material mat = org.bukkit.Material.matchMaterial(String.valueOf(matRaw));
            if (mat == null) {
                plugin.getLogger().warning("Unknown material in " + path + "[" + i + "]: " + matRaw);
                continue;
            }
            int min = toInt(map.get("min"), 1);
            int max = toInt(map.get("max"), min);
            double chance = toDouble(map.get("chance"));
            out.add(new RewardEntry(mat, min, max, chance));
        }
        return out;
    }

    public String getMessageLegacy(String key, String... placeholders) {
        return LegacyComponentSerializer.legacySection().serialize(getMessage(key, placeholders));
    }
}
