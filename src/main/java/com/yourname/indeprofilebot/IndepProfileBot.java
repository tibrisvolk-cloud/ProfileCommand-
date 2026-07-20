package com.yourname.indeprofilebot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.clip.placeholderapi.PlaceholderAPI;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.awt.Color;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IndepProfileBot extends JavaPlugin implements Listener {

    private JDA jda;
    private List<Map<?, ?>> profileFields;
    private Map<String, TopCategory> topCategories;
    private int topPageSize;
    private final Map<String, TopState> topStates = new ConcurrentHashMap<>();

    // 2FA
    private boolean twoFactorEnabled;
    private int sessionDurationMinutes;
    private int verificationTimeoutMinutes;
    private String linkKickMessage;
    private String verifyKickMessage;
    private final Map<String, String> linkCodes = new ConcurrentHashMap<>();
    private final Map<UUID, String> linkedAccounts = new ConcurrentHashMap<>();
    private final Map<UUID, PendingVerification> pendingVerifications = new ConcurrentHashMap<>();
    private final Map<UUID, SessionInfo> sessions = new ConcurrentHashMap<>();
    private File linkedFile;
    private FileConfiguration linkedConfig;

    // Синхронизация
    private String guildId;
    private String playerRoleId;
    private boolean syncNick;
    private boolean syncRole;
    private List<String> excludedRoles;

    // Достижения
    private boolean achievementsEnabled;
    private List<Achievement> achievements;
    private File achievementsFile;
    private FileConfiguration achievementsConfig;

    // Рекорды
    private String recordsChannelId;
    private String recordsImageUrl;
    private int recordsUpdateInterval;
    private String recordsMessageId;

    // Система уровней
    private boolean levelsEnabled;
    private LevelConfig levelConfig;
    private String levelUpChannelId;
    private final Map<String, LevelData> levelCache = new ConcurrentHashMap<>();
    private File levelsFile;
    private FileConfiguration levelsConfig;

    // Кулдаун для Minecraft чата
    private final Map<UUID, Long> mcChatCooldown = new ConcurrentHashMap<>();

    // Счётчики голосовой активности
    private final Map<String, Integer> voiceUnmutedSamples = new ConcurrentHashMap<>();
    private final Map<String, Integer> voiceTotalSamples = new ConcurrentHashMap<>();

    // ---------- СИСТЕМА КВЕСТОВ ----------
    private File questsFile;
    private FileConfiguration questsConfig;
    private double rareChance;
    private double legendaryChance;
    private ZoneId questTimezone;

    private final Map<String, QuestTemplate> normalPool = new LinkedHashMap<>();
    private final Map<String, QuestTemplate> rarePool = new LinkedHashMap<>();
    private final Map<String, QuestTemplate> legendaryPool = new LinkedHashMap<>();

    private final Map<String, PlayerQuestData> playerQuestDataMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> statSnapshots = new ConcurrentHashMap<>();

    private File questProgressFile;
    private FileConfiguration questProgressConfig;
    // --------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        // Загрузка основного конфига
        profileFields = getConfig().getMapList("profile-fields");
        topCategories = new LinkedHashMap<>();
        for (String key : getConfig().getConfigurationSection("top-categories").getKeys(false)) {
            String label = getConfig().getString("top-categories." + key + ".label");
            String placeholder = getConfig().getString("top-categories." + key + ".placeholder");
            topCategories.put(key, new TopCategory(label, placeholder));
        }
        topPageSize = getConfig().getInt("top-page-size", 5);

        twoFactorEnabled = getConfig().getBoolean("two-factor-auth.enabled", false);
        sessionDurationMinutes = getConfig().getInt("two-factor-auth.session-duration-minutes", 1440);
        verificationTimeoutMinutes = getConfig().getInt("two-factor-auth.verification-timeout-minutes", 5);
        linkKickMessage = getConfig().getString("two-factor-auth.link-kick-message", "");
        verifyKickMessage = getConfig().getString("two-factor-auth.verify-kick-message", "");
        linkKickMessage = ChatColor.translateAlternateColorCodes('&', linkKickMessage);
        verifyKickMessage = ChatColor.translateAlternateColorCodes('&', verifyKickMessage);

        guildId = getConfig().getString("guild-id", "");
        playerRoleId = getConfig().getString("player-role-id", "");
        syncNick = getConfig().getBoolean("sync-on-join.nick", false);
        syncRole = getConfig().getBoolean("sync-on-join.role", false);
        excludedRoles = getConfig().getStringList("excluded-roles");

        linkedFile = new File(getDataFolder(), "linked.yml");
        if (!linkedFile.exists()) {
            try { linkedFile.createNewFile(); } catch (IOException ignored) {}
        }
        linkedConfig = YamlConfiguration.loadConfiguration(linkedFile);
        for (String uuidStr : linkedConfig.getKeys(false)) {
            UUID uuid = UUID.fromString(uuidStr);
            String discordId = linkedConfig.getString(uuidStr);
            linkedAccounts.put(uuid, discordId);
        }

        achievementsEnabled = getConfig().getBoolean("achievements.check-on-join", false);
        achievements = new ArrayList<>();
        if (achievementsEnabled) {
            ConfigurationSection achList = getConfig().getConfigurationSection("achievements.list");
            if (achList != null) {
                for (String key : achList.getKeys(false)) {
                    String label = achList.getString(key + ".label");
                    String placeholder = achList.getString(key + ".placeholder");
                    long threshold = achList.getLong(key + ".threshold");
                    String roleId = achList.getString(key + ".role-id");
                    achievements.add(new Achievement(key, label, placeholder, threshold, roleId));
                }
            }
            achievementsFile = new File(getDataFolder(), "achievements.yml");
            if (!achievementsFile.exists()) {
                try { achievementsFile.createNewFile(); } catch (IOException ignored) {}
            }
            achievementsConfig = YamlConfiguration.loadConfiguration(achievementsFile);
        }

        recordsChannelId = getConfig().getString("records.channel-id", "");
        recordsImageUrl = getConfig().getString("records.image-url", "");
        recordsUpdateInterval = getConfig().getInt("records.update-interval-minutes", 10);
        loadRecordsState();

        // Система уровней
        levelsEnabled = getConfig().getBoolean("levels.enabled", false);
        if (levelsEnabled) {
            ConfigurationSection levelSec = getConfig().getConfigurationSection("levels");
            levelConfig = new LevelConfig();
            levelConfig.textXpEnabled = levelSec.getBoolean("text-xp.enabled", false);
            levelConfig.textChannels = levelSec.getStringList("text-xp.channels");
            levelConfig.textXpPerMessage = levelSec.getInt("text-xp.xp-per-message", 10);
            levelConfig.textCooldownSeconds = levelSec.getInt("text-xp.cooldown-seconds", 60);
            levelConfig.textMinLength = levelSec.getInt("text-xp.min-length", 8);
            levelConfig.voiceXpEnabled = levelSec.getBoolean("voice-xp.enabled", false);
            levelConfig.voiceCheckInterval = levelSec.getInt("voice-xp.check-interval-minutes", 5);
            levelConfig.voiceExcludeAfk = levelSec.getBoolean("voice-xp.exclude-afk", true);
            levelConfig.voiceMinMembers = levelSec.getInt("voice-xp.min-members", 2);
            levelConfig.voiceStreamMultiplier = levelSec.getDouble("voice-xp.multiplier-for-streaming", 1.5);
            levelConfig.voiceSampleIntervalSeconds = levelSec.getInt("voice-xp.sample-interval-seconds", 15);
            levelConfig.voiceSpeakingXpPerMinute = levelSec.getInt("voice-xp.speaking-xp-per-minute", 5);
            levelConfig.voiceMutedXpPerMinute = levelSec.getInt("voice-xp.muted-xp-per-minute", 1);
            levelConfig.mcChatXpEnabled = levelSec.getBoolean("minecraft-chat-xp.enabled", false);
            levelConfig.mcChatXpPerMessage = levelSec.getInt("minecraft-chat-xp.xp-per-message", 5);
            levelConfig.mcChatCooldownSeconds = levelSec.getInt("minecraft-chat-xp.cooldown-seconds", 60);
            levelConfig.mcChatMinLength = levelSec.getInt("minecraft-chat-xp.min-length", 4);
            levelConfig.minecraftChatPrefixEnabled = levelSec.getBoolean("minecraft-chat-prefix-enabled", false);
            levelConfig.minecraftChatPrefixFormat = levelSec.getString("minecraft-chat-prefix-format", "&d[LVL {level}] &r");
            levelConfig.base = levelSec.getInt("level-formula.base", 100);
            levelConfig.exponent = levelSec.getDouble("level-formula.exponent", 2.0);
            levelConfig.levelRoles = new HashMap<>();
            ConfigurationSection rolesSec = levelSec.getConfigurationSection("level-roles");
            if (rolesSec != null) {
                for (String levelStr : rolesSec.getKeys(false)) {
                    levelConfig.levelRoles.put(Integer.parseInt(levelStr), rolesSec.getString(levelStr));
                }
            }
            // Награды за уровни
            levelConfig.levelRewards = new HashMap<>();
            ConfigurationSection rewardsSec = levelSec.getConfigurationSection("level-rewards");
            if (rewardsSec != null) {
                for (String levelStr : rewardsSec.getKeys(false)) {
                    int lvl = Integer.parseInt(levelStr);
                    levelConfig.levelRewards.put(lvl, rewardsSec.getStringList(levelStr));
                }
            }
            // Канал для поздравлений с уровнями
            levelUpChannelId = levelSec.getString("level-up-channel", "");

            levelsFile = new File(getDataFolder(), "levels.yml");
            if (!levelsFile.exists()) {
                try { levelsFile.createNewFile(); } catch (IOException ignored) {}
            }
            levelsConfig = YamlConfiguration.loadConfiguration(levelsFile);
        }

        String token = getConfig().getString("bot-token");
        if (token == null || token.isEmpty() || token.equals("ВСТАВЬТЕ_ВАШ_ТОКЕН_БОТА")) {
            getLogger().severe("Не указан токен бота в config.yml! Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_VOICE_STATES)
                    .addEventListeners(new CommandListener(this), new ButtonListener(this), new LevelListener(this), new SlashCommandListener(this))
                    .build();
            jda.awaitReady();

            jda.updateCommands().addCommands(
                Commands.slash("profile", "Показать профиль игрока")
                    .addOption(OptionType.STRING, "ник", "Никнейм игрока", true),
                Commands.slash("top", "Показать топ игроков"),
                Commands.slash("me", "Показать мой профиль"),
                Commands.slash("link", "Привязать Minecraft аккаунт")
                    .addOption(OptionType.STRING, "код", "Код из кик-сообщения", true),
                Commands.slash("unlink", "Отвязать Minecraft аккаунт"),
                Commands.slash("rank", "Показать уровень и опыт")
            ).queue();

            getLogger().info("Discord бот запущен. Все модули активны.");
        } catch (Exception e) {
            getLogger().severe("Ошибка запуска Discord бота: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        if (!recordsChannelId.isEmpty()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    updateRecordsMessage();
                }
            }.runTaskTimer(this, 100L, 20L * 60 * recordsUpdateInterval);
        }

        if (levelsEnabled && levelConfig.voiceXpEnabled) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    sampleVoiceActivity();
                }
            }.runTaskTimer(this, 20L * 15, 20L * levelConfig.voiceSampleIntervalSeconds);

            new BukkitRunnable() {
                @Override
                public void run() {
                    awardVoiceXp();
                }
            }.runTaskTimer(this, 20L * 30, 20L * 60 * levelConfig.voiceCheckInterval);
        }

        // ---------- ИНИЦИАЛИЗАЦИЯ КВЕСТОВ ----------
        loadQuestConfig();
        startQuestResetTask();
        startMinecraftStatTracker();
    }

    // ---------- МЕТОДЫ ЗАГРУЗКИ КВЕСТОВ ----------
    private void loadQuestConfig() {
        questsFile = new File(getDataFolder(), "quests.yml");
        if (!questsFile.exists()) {
            saveResource("quests.yml", false);
        }
        questsConfig = YamlConfiguration.loadConfiguration(questsFile);
        ConfigurationSection settings = questsConfig.getConfigurationSection("settings");
        rareChance = settings.getDouble("rare_chance", 0.3);
        legendaryChance = settings.getDouble("legendary_chance", 0.07);
        questTimezone = ZoneId.of(settings.getString("timezone", "Europe/Moscow"));

        normalPool.clear();
        rarePool.clear();
        legendaryPool.clear();
        loadPool(questsConfig.getConfigurationSection("pools.normal"), normalPool);
        loadPool(questsConfig.getConfigurationSection("pools.rare"), rarePool);
        loadPool(questsConfig.getConfigurationSection("pools.legendary"), legendaryPool);

        questProgressFile = new File(getDataFolder(), "quests_progress.yml");
        questProgressConfig = YamlConfiguration.loadConfiguration(questProgressFile);
        loadQuestProgress();
    }

    private void loadPool(ConfigurationSection section, Map<String, QuestTemplate> pool) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection q = section.getConfigurationSection(key);
            QuestTemplate t = new QuestTemplate();
            t.id = key;
            t.label = q.getString("label");
            t.description = q.getString("description");
            t.type = q.getString("type");
            t.stat = q.getString("stat");
            t.event = q.getString("event");
            t.target = q.getInt("target");
            t.reward = q.getInt("reward");
            pool.put(key, t);
        }
    }

    private void loadQuestProgress() {
        ConfigurationSection dataSec = questProgressConfig.getConfigurationSection("data");
        if (dataSec == null) return;
        for (String discordId : dataSec.getKeys(false)) {
            PlayerQuestData data = new PlayerQuestData();
            data.date = dataSec.getString(discordId + ".date");
            data.slots = new ArrayList<>();
            ConfigurationSection slotsSec = dataSec.getConfigurationSection(discordId + ".slots");
            if (slotsSec != null) {
                for (String idx : slotsSec.getKeys(false)) {
                    ConfigurationSection s = slotsSec.getConfigurationSection(idx);
                    QuestSlot slot = new QuestSlot();
                    slot.questId = s.getString("questId");
                    slot.poolType = s.getString("poolType");
                    slot.progress = s.getInt("progress");
                    slot.completed = s.getBoolean("completed");
                    slot.template = getQuestTemplate(slot.poolType, slot.questId);
                    data.slots.add(slot);
                }
            }
            playerQuestDataMap.put(discordId, data);
        }
    }

    private void saveQuestProgress() {
        questProgressConfig.set("data", null);
        for (Map.Entry<String, PlayerQuestData> entry : playerQuestDataMap.entrySet()) {
            String discordId = entry.getKey();
            PlayerQuestData data = entry.getValue();
            questProgressConfig.set("data." + discordId + ".date", data.date);
            for (int i = 0; i < data.slots.size(); i++) {
                QuestSlot slot = data.slots.get(i);
                String path = "data." + discordId + ".slots." + i;
                questProgressConfig.set(path + ".questId", slot.questId);
                questProgressConfig.set(path + ".poolType", slot.poolType);
                questProgressConfig.set(path + ".progress", slot.progress);
                questProgressConfig.set(path + ".completed", slot.completed);
            }
        }
        try {
            questProgressConfig.save(questProgressFile);
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить прогресс квестов");
        }
    }

    private QuestTemplate getQuestTemplate(String poolType, String questId) {
        Map<String, QuestTemplate> pool = switch (poolType) {
            case "normal" -> normalPool;
            case "rare" -> rarePool;
            case "legendary" -> legendaryPool;
            default -> null;
        };
        return pool != null ? pool.get(questId) : null;
    }

    private UUID getUuidFromDiscord(String discordId) {
        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            if (entry.getValue().equals(discordId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // ---------- ГЕНЕРАЦИЯ КВЕСТОВ ----------
    public void generateDailyQuests(String discordId) {
        PlayerQuestData data = playerQuestDataMap.computeIfAbsent(discordId, k -> new PlayerQuestData());
        data.date = getToday();
        List<QuestSlot> slots = new ArrayList<>(3);

        List<String> normalIds = new ArrayList<>(normalPool.keySet());
        Collections.shuffle(normalIds);
        for (int i = 0; i < 3 && i < normalIds.size(); i++) {
            QuestSlot slot = new QuestSlot();
            slot.questId = normalIds.get(i);
            slot.poolType = "normal";
            slot.template = normalPool.get(slot.questId);
            slot.progress = 0;
            slot.completed = false;
            slots.add(slot);
        }

        boolean rareApplied = false;
        if (ThreadLocalRandom.current().nextDouble() < rareChance) {
            List<String> rareIds = new ArrayList<>(rarePool.keySet());
            Collections.shuffle(rareIds);
            if (!rareIds.isEmpty()) {
                int idx = ThreadLocalRandom.current().nextInt(3);
                String rareId = rareIds.get(0);
                QuestSlot rareSlot = new QuestSlot();
                rareSlot.questId = rareId;
                rareSlot.poolType = "rare";
                rareSlot.template = rarePool.get(rareId);
                rareSlot.progress = 0;
                rareSlot.completed = false;
                slots.set(idx, rareSlot);
                rareApplied = true;
            }
        }

        if (!rareApplied && ThreadLocalRandom.current().nextDouble() < legendaryChance) {
            List<String> legendaryIds = new ArrayList<>(legendaryPool.keySet());
            Collections.shuffle(legendaryIds);
            if (!legendaryIds.isEmpty()) {
                int idx = ThreadLocalRandom.current().nextInt(3);
                String legId = legendaryIds.get(0);
                QuestSlot legSlot = new QuestSlot();
                legSlot.questId = legId;
                legSlot.poolType = "legendary";
                legSlot.template = legendaryPool.get(legId);
                legSlot.progress = 0;
                legSlot.completed = false;
                slots.set(idx, legSlot);
            }
        }

        data.slots = slots;
        UUID uuid = getUuidFromDiscord(discordId);
        if (uuid != null) {
            statSnapshots.remove(uuid.toString());
        }
        saveQuestProgress();
    }

    private String getToday() {
        return LocalDate.now(questTimezone).toString();
    }

    public boolean needNewQuests(String discordId) {
        PlayerQuestData data = playerQuestDataMap.get(discordId);
        if (data == null || !getToday().equals(data.date) || data.slots.isEmpty()) {
            return true;
        }
        for (QuestSlot slot : data.slots) {
            if (slot.template == null) {
                slot.template = getQuestTemplate(slot.poolType, slot.questId);
                if (slot.template == null) return true;
            }
        }
        return false;
    }

    // ---------- ПРОГРЕСС КВЕСТОВ ----------
    public void addQuestProgress(String discordId, String questId, int amount) {
        PlayerQuestData data = playerQuestDataMap.get(discordId);
        if (data == null) return;
        for (QuestSlot slot : data.slots) {
            if (slot.completed || slot.template == null) continue;
            if (slot.questId.equals(questId)) {
                slot.progress += amount;
                if (slot.progress >= slot.template.target) {
                    slot.progress = slot.template.target;
                    slot.completed = true;
                    addXp(discordId, slot.template.reward);
                    notifyQuestCompleted(discordId, slot);
                }
                saveQuestProgress();
                break;
            }
        }
    }

    public void addQuestProgressByEvent(String discordId, String eventName, int amount) {
        PlayerQuestData data = playerQuestDataMap.get(discordId);
        if (data == null) return;
        for (QuestSlot slot : data.slots) {
            if (slot.completed || slot.template == null) continue;
            if (slot.template.event != null && slot.template.event.equals(eventName)) {
                slot.progress += amount;
                if (slot.progress >= slot.template.target) {
                    slot.progress = slot.template.target;
                    slot.completed = true;
                    addXp(discordId, slot.template.reward);
                    notifyQuestCompleted(discordId, slot);
                }
                saveQuestProgress();
                break;
            }
        }
    }

    private void notifyQuestCompleted(String discordId, QuestSlot slot) {
        if (jda != null) {
            jda.retrieveUserById(discordId).queue(user -> {
                user.openPrivateChannel().queue(channel -> {
                    channel.sendMessage("✅ Квест **" + slot.template.label + "** выполнен! +" + slot.template.reward + " XP").queue();
                });
            });
        }
        UUID uuid = getUuidFromDiscord(discordId);
        if (uuid != null) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.sendMessage("§a✅ Квест \"" + slot.template.label + "\" выполнен! +" + slot.template.reward + " XP");
            }
        }
    }

    public String makeProgressBar(int current, int max) {
        int bars = 10;
        int filled = (int) ((double) current / max * bars);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < bars; i++) {
            bar.append(i < filled ? "🟦" : "⬜");
        }
        return bar.toString();
    }

    // ---------- ТАЙМЕРЫ КВЕСТОВ ----------
    private void startQuestResetTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                String today = getToday();
                for (Map.Entry<String, PlayerQuestData> entry : playerQuestDataMap.entrySet()) {
                    if (!today.equals(entry.getValue().date)) {
                        generateDailyQuests(entry.getKey());
                    }
                }
            }
        }.runTaskTimerAsynchronously(this, 1200L, 72000L);
    }

    private void startMinecraftStatTracker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    String discordId = linkedAccounts.get(uuid);
                    if (discordId == null) continue;
                    PlayerQuestData data = playerQuestDataMap.get(discordId);
                    if (data == null || data.slots.isEmpty()) continue;

                    for (QuestSlot slot : data.slots) {
                        if (slot.completed || slot.template == null) continue;
                        if (slot.template.type.equals("mc_stat_delta") && slot.template.stat != null) {
                            long delta = getStatDelta(player, slot.template.stat);
                            if (delta > 0) {
                                addQuestProgress(discordId, slot.questId, (int) delta);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(this, 1200L, 1200L);
    }

    private long getStatDelta(OfflinePlayer player, String statPlaceholder) {
        String uuidStr = player.getUniqueId().toString();
        Map<String, Long> snap = statSnapshots.computeIfAbsent(uuidStr, k -> new ConcurrentHashMap<>());
        String placeholder = "%" + statPlaceholder + "%";
        String valueStr = PlaceholderAPI.setPlaceholders(player, placeholder);
        long current;
        try {
            current = Long.parseLong(valueStr.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
        String statName = statPlaceholder.startsWith("statistic_") ? statPlaceholder.substring("statistic_".length()) : statPlaceholder;
        Long prev = snap.get(statName);
        if (prev == null) {
            snap.put(statName, current);
            return 0;
        } else {
            long delta = current - prev;
            if (delta > 0) snap.put(statName, current);
            return Math.max(0, delta);
        }
    }

    // ---------- КВЕСТОВЫЕ СОБЫТИЯ BUKKIT ----------
    @EventHandler
    public void onQuestItemBreak(PlayerItemBreakEvent event) {
        handleQuestEvent(event.getPlayer(), "player_item_break", 1);
    }

    @EventHandler
    public void onQuestBucketFill(PlayerBucketFillEvent event) {
        handleQuestEvent(event.getPlayer(), "player_bucket_fill", 1);
    }

    @EventHandler
    public void onQuestBlockBreak(BlockBreakEvent event) {
        if (isOre(event.getBlock().getType())) {
            handleQuestEvent(event.getPlayer(), "block_break_ore", 1);
        }
    }

    @EventHandler
    public void onQuestTame(EntityTameEvent event) {
        if (event.getOwner() instanceof Player player) {
            if (event.getEntity().getType() == EntityType.WOLF || event.getEntity().getType() == EntityType.CAT) {
                handleQuestEvent(player, "player_tame", 1);
            }
        }
    }

    @EventHandler
    public void onQuestAdvancement(PlayerAdvancementDoneEvent event) {
        handleQuestEvent(event.getPlayer(), "player_advancement", 1);
    }

    @EventHandler
    public void onQuestDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (player.getLastDamageCause() != null &&
                player.getLastDamageCause().getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
                handleQuestEvent(player, "player_death_by_creeper", 1);
            }
        }
    }

    @EventHandler
    public void onQuestSleep(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.OK) {
            handleQuestEvent(event.getPlayer(), "player_sleep", 1);
        }
    }

    @EventHandler
    public void onQuestDrink(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item.getType() == Material.POTION || item.getType() == Material.LINGERING_POTION || item.getType() == Material.SPLASH_POTION) {
            PotionMeta meta = (PotionMeta) item.getItemMeta();
            if (meta != null && (meta.hasCustomEffects() || meta.getBasePotionType() != PotionType.WATER)) {
                handleQuestEvent(event.getPlayer(), "player_drink_potion_with_effect", 1);
            }
        }
    }

    @EventHandler
    public void onQuestMinecartRide(PlayerMoveEvent event) {
        if (event.getPlayer().isInsideVehicle() && event.getPlayer().getVehicle() instanceof Minecart) {
            double moved = event.getFrom().distanceSquared(event.getTo());
            if (moved > 0.0001) {
                handleQuestEvent(event.getPlayer(), "player_minecart_distance", 1);
            }
        }
    }

    private void handleQuestEvent(Player player, String eventName, int amount) {
        UUID uuid = player.getUniqueId();
        String discordId = linkedAccounts.get(uuid);
        if (discordId != null) {
            addQuestProgressByEvent(discordId, eventName, amount);
        }
    }

    private boolean isOre(Material material) {
        return switch (material) {
            case COAL_ORE, IRON_ORE, GOLD_ORE, DIAMOND_ORE, EMERALD_ORE,
                 REDSTONE_ORE, LAPIS_ORE, COPPER_ORE, NETHER_QUARTZ_ORE,
                 NETHER_GOLD_ORE, DEEPSLATE_COAL_ORE, DEEPSLATE_IRON_ORE,
                 DEEPSLATE_GOLD_ORE, DEEPSLATE_DIAMOND_ORE, DEEPSLATE_EMERALD_ORE,
                 DEEPSLATE_REDSTONE_ORE, DEEPSLATE_LAPIS_ORE, DEEPSLATE_COPPER_ORE -> true;
            default -> false;
        };
    }

    // ---------- ОСТАЛЬНЫЕ МЕТОДЫ ----------
    @Override
    public void onDisable() {
        if (jda != null) jda.shutdown();
    }

    private boolean canModifyMember(Member member) {
        if (member == null) return false;
        Guild guild = member.getGuild();
        Member self = guild.getSelfMember();
        if (!self.canInteract(member)) return false;
        for (String roleId : excludedRoles) {
            if (member.getRoles().stream().anyMatch(r -> r.getId().equals(roleId))) {
                return false;
            }
        }
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String discordId = linkedAccounts.get(uuid);

        if (discordId != null) {
            // Генерация квестов при входе
            if (needNewQuests(discordId)) {
                generateDailyQuests(discordId);
            }

            if (!guildId.isEmpty() && (syncNick || syncRole)) {
                Guild guild = jda.getGuildById(guildId);
                if (guild != null) {
                    guild.retrieveMemberById(discordId).queue(member -> {
                        if (!canModifyMember(member)) return;
                        if (syncRole && !playerRoleId.isEmpty()) {
                            Role role = guild.getRoleById(playerRoleId);
                            if (role != null && !member.getRoles().contains(role)) {
                                guild.addRoleToMember(member, role).queue();
                            }
                        }
                        if (syncNick) {
                            String nick = player.getName();
                            if (!nick.equals(member.getNickname())) {
                                guild.modifyNickname(member, nick).queue();
                            }
                        }
                    });
                }
            }

            if (achievementsEnabled) {
                checkAchievements(player);
            }

            // Выдача неполученных наград за уровни
            if (levelsEnabled && levelConfig.levelRewards != null && !levelConfig.levelRewards.isEmpty()) {
                grantRewards(player, discordId);
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // Префикс уровня в чате
        if (levelsEnabled && levelConfig.minecraftChatPrefixEnabled) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            String discordId = linkedAccounts.get(uuid);
            if (discordId != null) {
                LevelData data = getLevelData(discordId);
                String format = levelConfig.minecraftChatPrefixFormat
                        .replace("{level}", String.valueOf(data.level));
                format = ChatColor.translateAlternateColorCodes('&', format);
                event.setFormat(format + event.getFormat());
            }
        }

        // Начисление опыта за Minecraft чат
        if (!levelsEnabled || !levelConfig.mcChatXpEnabled) return;
        Player player = event.getPlayer();
        String message = event.getMessage();
        if (message.length() < levelConfig.mcChatMinLength) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = mcChatCooldown.get(uuid);
        if (last != null && (now - last) < TimeUnit.SECONDS.toMillis(levelConfig.mcChatCooldownSeconds)) return;

        mcChatCooldown.put(uuid, now);
        String discordId = linkedAccounts.get(uuid);
        if (discordId != null) {
            addXp(discordId, levelConfig.mcChatXpPerMessage);
            // Прогресс квеста на сообщения в Minecraft
            addQuestProgress(discordId, "mc_chat", 1);
        }
    }

    private void checkAchievements(Player player) {
        UUID uuid = player.getUniqueId();
        List<String> earned = achievementsConfig.getStringList(uuid.toString());
        boolean changed = false;
        for (Achievement ach : achievements) {
            if (earned.contains(ach.id)) continue;
            String valueStr = getFieldValue(player, ach.placeholder);
            long value = parseStatistic(valueStr);
            if (value >= ach.threshold) {
                earned.add(ach.id);
                changed = true;
                if (!guildId.isEmpty() && !ach.roleId.isEmpty()) {
                    String discordId = linkedAccounts.get(uuid);
                    if (discordId != null) {
                        Guild guild = jda.getGuildById(guildId);
                        if (guild != null) {
                            guild.retrieveMemberById(discordId).queue(member -> {
                                if (!canModifyMember(member)) return;
                                Role role = guild.getRoleById(ach.roleId);
                                if (role != null && !member.getRoles().contains(role)) {
                                    guild.addRoleToMember(member, role).queue();
                                }
                            });
                        }
                    }
                }
            }
        }
        if (changed) {
            achievementsConfig.set(uuid.toString(), earned);
            try { achievementsConfig.save(achievementsFile); } catch (IOException e) {
                getLogger().warning("Не удалось сохранить achievements.yml");
            }
        }
    }

    private void updateRecordsMessage() {
        if (recordsChannelId.isEmpty()) return;
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, recordsChannelId);
        if (channel == null) {
            getLogger().warning("Канал рекордов не найден: " + recordsChannelId);
            return;
        }

        EmbedBuilder embed = new EmbedBuilder();
        embed.setColor(new Color(0xCD72DF));
        embed.setTitle("🏆 Рекорды сервера за всё время");
        if (!recordsImageUrl.isEmpty()) {
            embed.setImage(recordsImageUrl);
        }

        StringBuilder desc = new StringBuilder();
        for (Map.Entry<String, TopCategory> entry : topCategories.entrySet()) {
            String catKey = entry.getKey();
            TopCategory cat = entry.getValue();
            List<Map.Entry<String, String>> top = getTopForCategoryFormatted(cat.placeholder, catKey);
            if (!top.isEmpty()) {
                Map.Entry<String, String> first = top.get(0);
                desc.append(cat.label).append(": **").append(first.getKey()).append("** — ").append(first.getValue()).append("\n");
            } else {
                desc.append(cat.label).append(": *нет данных*\n");
            }
        }
        embed.setDescription(desc.toString());
        embed.setFooter("Обновляется каждые " + recordsUpdateInterval + " мин.");

        if (recordsMessageId != null && !recordsMessageId.isEmpty()) {
            channel.editMessageEmbedsById(recordsMessageId, embed.build()).queue(
                success -> {},
                failure -> channel.sendMessageEmbeds(embed.build()).queue(msg -> {
                    recordsMessageId = msg.getId();
                    saveRecordsState();
                })
            );
        } else {
            channel.sendMessageEmbeds(embed.build()).queue(msg -> {
                recordsMessageId = msg.getId();
                saveRecordsState();
            });
        }
    }

    private void saveRecordsState() {
        File stateFile = new File(getDataFolder(), "records-state.yml");
        FileConfiguration stateConfig = YamlConfiguration.loadConfiguration(stateFile);
        stateConfig.set("message-id", recordsMessageId);
        try { stateConfig.save(stateFile); } catch (IOException e) {
            getLogger().warning("Не удалось сохранить records-state.yml");
        }
    }

    private void loadRecordsState() {
        File stateFile = new File(getDataFolder(), "records-state.yml");
        if (stateFile.exists()) {
            FileConfiguration stateConfig = YamlConfiguration.loadConfiguration(stateFile);
            recordsMessageId = stateConfig.getString("message-id", "");
        }
    }

    @EventHandler
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!twoFactorEnabled) return;

        UUID uuid = event.getUniqueId();
        String ip = event.getAddress().getHostAddress();

        String discordId = linkedAccounts.get(uuid);
        if (discordId == null) {
            String code = String.format("%06d", new Random().nextInt(999999));
            linkCodes.put(code, uuid.toString());
            String kickMsg = linkKickMessage.replace("{CODE}", code);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMsg);
            return;
        }

        SessionInfo session = sessions.get(uuid);
        if (session != null && session.ip.equals(ip) && System.currentTimeMillis() < session.expiry) {
            return;
        }

        long timeoutMillis = TimeUnit.MINUTES.toMillis(verificationTimeoutMinutes);
        pendingVerifications.entrySet().removeIf(e ->
                System.currentTimeMillis() - e.getValue().timestamp > timeoutMillis);

        long timestamp = System.currentTimeMillis();
        pendingVerifications.put(uuid, new PendingVerification(ip, timestamp));
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, verifyKickMessage);

        User user = jda.retrieveUserById(discordId).complete();
        if (user != null) {
            user.openPrivateChannel().queue(channel -> {
                EmbedBuilder eb = new EmbedBuilder();
                eb.setColor(new Color(0xCD72DF));
                eb.setTitle("Подтверждение входа на сервер");
                String location = getLocationFromIp(ip);
                eb.setDescription("Кто-то пытается войти под вашим аккаунтом Minecraft.\n🌍 Локация: " + location +
                        "\nЕсли это вы, нажмите кнопку ниже.");
                eb.setFooter("Запрос действителен 5 минут");
                channel.sendMessageEmbeds(eb.build())
                        .setActionRow(Button.success("2fa_confirm_" + uuid, "Подтвердить вход"))
                        .queue();
            });
        }
    }

    public void handleLinkCommand(MessageReceivedEvent event, String code) {
        UUID uuid;
        synchronized (linkCodes) {
            String uuidStr = linkCodes.remove(code);
            if (uuidStr == null) {
                event.getMessage().reply("Неверный или устаревший код.").queue();
                return;
            }
            uuid = UUID.fromString(uuidStr);
        }
        String discordId = event.getAuthor().getId();

        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            if (entry.getValue().equals(discordId)) {
                String existingName = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                event.getMessage().reply("Ваш Discord уже привязан к аккаунту `" + existingName + "`. Сначала отвяжите его командой `!unlink`.").queue();
                return;
            }
        }

        linkedAccounts.put(uuid, discordId);
        linkedConfig.set(uuid.toString(), discordId);
        try { linkedConfig.save(linkedFile); } catch (IOException e) { getLogger().warning("Ошибка сохранения linked.yml"); }
        event.getMessage().reply("✅ Аккаунт привязан! Можете заходить.").queue();
    }

    public void handleUnlinkCommand(MessageReceivedEvent event) {
        String discordId = event.getAuthor().getId();
        UUID uuidToRemove = null;
        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            if (entry.getValue().equals(discordId)) {
                uuidToRemove = entry.getKey();
                break;
            }
        }
        if (uuidToRemove == null) {
            event.getMessage().reply("Ваш Discord не привязан ни к одному аккаунту.").queue();
            return;
        }

        Player onlinePlayer = Bukkit.getPlayer(uuidToRemove);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            String kickMessage = ChatColor.RED + "Ваш Discord-аккаунт был отвязан.\n" +
                    ChatColor.WHITE + "Перезайдите и привяжите новый аккаунт.";
            Bukkit.getScheduler().runTask(this, () -> onlinePlayer.kickPlayer(kickMessage));
        }

        linkedAccounts.remove(uuidToRemove);
        linkedConfig.set(uuidToRemove.toString(), null);
        try { linkedConfig.save(linkedFile); } catch (IOException e) { getLogger().warning("Ошибка сохранения linked.yml"); }
        sessions.remove(uuidToRemove);
        pendingVerifications.remove(uuidToRemove);

        if (!guildId.isEmpty()) {
            Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.retrieveMemberById(discordId).queue(member -> {
                    if (!canModifyMember(member)) return;
                    if (!playerRoleId.isEmpty()) {
                        Role role = guild.getRoleById(playerRoleId);
                        if (role != null && member.getRoles().contains(role)) {
                            guild.removeRoleFromMember(member, role).queue();
                        }
                    }
                    if (syncNick && member.getNickname() != null) {
                        guild.modifyNickname(member, null).queue();
                    }
                });
            }
        }
        event.getMessage().reply("Привязка удалена.").queue();
    }

    public void handleConfirmButton(ButtonInteractionEvent event, UUID uuid) {
        PendingVerification pending = pendingVerifications.get(uuid);
        if (pending == null) {
            event.reply("⏳ Запрос недействителен.").setEphemeral(true).queue();
            return;
        }

        long timeoutMillis = TimeUnit.MINUTES.toMillis(verificationTimeoutMinutes);
        if (System.currentTimeMillis() - pending.timestamp > timeoutMillis) {
            pendingVerifications.remove(uuid);
            event.reply("⏳ Запрос истёк. Перезайдите на сервер.").setEphemeral(true).queue();
            return;
        }

        pendingVerifications.remove(uuid);
        sessions.put(uuid, new SessionInfo(pending.ip,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(sessionDurationMinutes)));
        event.editMessage("✅ Вход подтверждён. Можете зайти на сервер.").setComponents().queue();
    }

    private String getLocationFromIp(String ip) {
        try {
            URL url = new URL("http://ip-api.com/json/" + ip + "?fields=city,country");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);

            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();
            con.disconnect();

            String json = content.toString();
            String city = "неизвестно";
            String country = "неизвестно";
            if (json.contains("\"city\":")) {
                city = json.split("\"city\":\"")[1].split("\"")[0];
            }
            if (json.contains("\"country\":")) {
                country = json.split("\"country\":\"")[1].split("\"")[0];
            }
            return city + ", " + country;
        } catch (Exception e) {
            getLogger().warning("Не удалось определить локацию: " + e.getMessage());
            return "локация не определена";
        }
    }

    private LevelData getLevelData(String discordId) {
        return levelCache.computeIfAbsent(discordId, k -> {
            LevelData data = new LevelData();
            data.xp = levelsConfig.getInt(k + ".xp", 0);
            data.level = levelsConfig.getInt(k + ".level", 1);
            data.lastTextXp = levelsConfig.getLong(k + ".lastTextXp", 0);
            data.lastLevelUp = levelsConfig.getLong(k + ".lastLevelUp", 0);
            data.highestRewardClaimed = levelsConfig.getInt(k + ".highestRewardClaimed", 0);
            return data;
        });
    }

    private void saveLevelData(String discordId, LevelData data) {
        levelsConfig.set(discordId + ".xp", data.xp);
        levelsConfig.set(discordId + ".level", data.level);
        levelsConfig.set(discordId + ".lastTextXp", data.lastTextXp);
        levelsConfig.set(discordId + ".lastLevelUp", data.lastLevelUp);
        levelsConfig.set(discordId + ".highestRewardClaimed", data.highestRewardClaimed);
        try { levelsConfig.save(levelsFile); } catch (IOException e) {
            getLogger().warning("Не удалось сохранить levels.yml");
        }
    }

    private int getXpForLevel(int level) {
        return (int) (levelConfig.base * Math.pow(level, levelConfig.exponent));
    }

    public void addXp(String discordId, int amount) {
        LevelData data = getLevelData(discordId);
        int oldLevel = data.level;
        data.xp += amount;
        int nextLevelXp = getXpForLevel(data.level + 1);
        boolean leveledUp = false;
        while (data.xp >= nextLevelXp) {
            data.xp -= nextLevelXp;
            data.level++;
            leveledUp = true;
            nextLevelXp = getXpForLevel(data.level + 1);
        }
        if (leveledUp) {
            data.lastLevelUp = System.currentTimeMillis();
            // Поздравление в Discord
            notifyLevelUp(discordId, data.level);
            // Поздравление в Minecraft
            UUID playerUuid = null;
            for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
                if (entry.getValue().equals(discordId)) {
                    playerUuid = entry.getKey();
                    break;
                }
            }
            if (playerUuid != null) {
                Player player = Bukkit.getPlayer(playerUuid);
                if (player != null && player.isOnline()) {
                    player.sendMessage(ChatColor.GOLD + "🎉 Поздравляем! Вы достигли " + ChatColor.GREEN + data.level + " уровня!" + ChatColor.GOLD + " Продолжайте в том же духе!");
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                }
            }
        }
        saveLevelData(discordId, data);

        // Выдача наград за новые уровни
        if (leveledUp && levelConfig.levelRewards != null && !levelConfig.levelRewards.isEmpty()) {
            UUID playerUuid = null;
            for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
                if (entry.getValue().equals(discordId)) {
                    playerUuid = entry.getKey();
                    break;
                }
            }
            if (playerUuid != null) {
                Player player = Bukkit.getPlayer(playerUuid);
                if (player != null && player.isOnline()) {
                    grantRewards(player, discordId);
                }
            }
        }

        checkLevelRoles(discordId, data.level);
    }

    // ========== НОВЫЙ МЕТОД ВЫДАЧИ НАГРАД ==========
    private void grantRewards(Player player, String discordId) {
        LevelData data = getLevelData(discordId);
        int currentLevel = data.level;
        int highestClaimed = data.highestRewardClaimed;
        if (currentLevel <= highestClaimed) return;

        String playerName = player.getName();
        for (int lvl = highestClaimed + 1; lvl <= currentLevel; lvl++) {
            List<String> commands = levelConfig.levelRewards.get(lvl);
            if (commands == null) continue;

            for (String cmd : commands) {
                String finalCmd = cmd.replace("{player}", playerName);

                // Выдача предметов напрямую
                if (finalCmd.startsWith("give " + playerName + " ")) {
                    try {
                        giveItem(player, finalCmd.substring(("give " + playerName + " ").length()));
                    } catch (Exception e) {
                        getLogger().warning("Ошибка парсинга предмета: " + finalCmd + " - " + e.getMessage());
                        // fallback на команду
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                    }
                } else {
                    // Обычные команды (xp, tellraw, effect)
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                }
            }
        }
        data.highestRewardClaimed = currentLevel;
        saveLevelData(discordId, data);
    }

    private void giveItem(Player player, String itemString) throws Exception {
        // Формат: material[components] amount (amount опционально)
        String[] parts = itemString.split(" ");
        int amount = 1;
        String itemDef;
        if (parts.length > 1) {
            try {
                amount = Integer.parseInt(parts[parts.length - 1]);
                itemDef = String.join(" ", Arrays.copyOf(parts, parts.length - 1));
            } catch (NumberFormatException e) {
                itemDef = itemString;
            }
        } else {
            itemDef = itemString;
        }

        // Отделяем компоненты
        String materialName = itemDef;
        String componentStr = "";
        int bracketStart = itemDef.indexOf('[');
        if (bracketStart != -1) {
            materialName = itemDef.substring(0, bracketStart);
            componentStr = itemDef.substring(bracketStart);
        }

        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            throw new IllegalArgumentException("Неизвестный материал: " + materialName);
        }

        ItemStack item = new ItemStack(material, amount);
        if (!componentStr.isEmpty()) {
            applyComponents(item, componentStr);
        }

        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack left : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    private void applyComponents(ItemStack item, String compStr) throws Exception {
        if (compStr.startsWith("[") && compStr.endsWith("]")) {
            compStr = compStr.substring(1, compStr.length() - 1);
        }

        if (compStr.startsWith("enchantments=")) {
            applyEnchantments(item, compStr.substring("enchantments=".length()), false);
        } else if (compStr.startsWith("stored_enchantments=")) {
            applyEnchantments(item, compStr.substring("stored_enchantments=".length()), true);
        } else if (compStr.startsWith("profile=")) {
            applyProfile(item, compStr.substring("profile=".length()));
        } else {
            throw new IllegalArgumentException("Неподдерживаемый компонент: " + compStr);
        }
    }

    private void applyEnchantments(ItemStack item, String value, boolean isBook) throws Exception {
        Map<String, Integer> enchants = new HashMap<>();

        if (value.startsWith("[")) {
            // [{id:"...",lvl:N}]
            String inner = value.substring(1, value.length() - 1);
            String[] entries = inner.split("\\},\\{");
            for (String entry : entries) {
                entry = entry.replace("{", "").replace("}", "");
                String[] fields = entry.split(",");
                String id = null;
                int lvl = 1;
                for (String field : fields) {
                    if (field.contains("id")) {
                        id = field.split(":")[1].replace("\"", "").trim();
                    } else if (field.contains("lvl")) {
                        lvl = Integer.parseInt(field.split(":")[1].trim());
                    }
                }
                if (id != null) enchants.put(id, lvl);
            }
        } else if (value.startsWith("{levels:")) {
            // {levels:{"minecraft:...":3}}
            String inner = value.substring("{levels:".length());
            if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);
            JsonObject obj = JsonParser.parseString(inner).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                enchants.put(entry.getKey(), entry.getValue().getAsInt());
            }
        } else {
            throw new IllegalArgumentException("Неизвестный формат зачарований: " + value);
        }

        for (Map.Entry<String, Integer> e : enchants.entrySet()) {
            String enchName = e.getKey().toUpperCase();
            if (enchName.startsWith("MINECRAFT:")) {
                enchName = enchName.substring("MINECRAFT:".length());
            }
            Enchantment enchant = Enchantment.getByName(enchName);
            if (enchant == null) {
                enchant = Enchantment.getByKey(NamespacedKey.minecraft(e.getKey().substring("minecraft:".length())));
            }
            if (enchant != null) {
                if (isBook && item.getType() == Material.ENCHANTED_BOOK) {
                    EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
                    meta.addStoredEnchant(enchant, e.getValue(), true);
                    item.setItemMeta(meta);
                } else {
                    item.addUnsafeEnchantment(enchant, e.getValue());
                }
            }
        }
    }

    private void applyProfile(ItemStack item, String value) {
        if (value.startsWith("{") && value.endsWith("}")) {
            String inner = value.substring(1, value.length() - 1);
            String name = inner.split(":")[1].replace("\"", "").trim();
            if (item.getType() == Material.PLAYER_HEAD) {
                SkullMeta meta = (SkullMeta) item.getItemMeta();
                meta.setOwner(name);
                item.setItemMeta(meta);
            }
        }
    }

    private void notifyLevelUp(String discordId, int newLevel) {
        if (jda == null) return;
        // Личное сообщение
        jda.retrieveUserById(discordId).queue(user -> {
            user.openPrivateChannel().queue(channel -> {
                EmbedBuilder eb = new EmbedBuilder();
                eb.setColor(new Color(0xFFD700));
                eb.setTitle("🎉 Поздравляем!");
                eb.setDescription("Вы достигли **" + newLevel + " уровня**!");
                eb.setFooter("Продолжайте играть и получать опыт!");
                channel.sendMessageEmbeds(eb.build()).queue();
            });
        });

        // Канал в гильдии
        if (levelUpChannelId != null && !levelUpChannelId.isEmpty()) {
            GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, levelUpChannelId);
            if (channel != null) {
                EmbedBuilder eb = new EmbedBuilder();
                eb.setColor(new Color(0xFFD700));
                eb.setTitle("🎉 Новый уровень!");
                eb.setDescription("<@" + discordId + "> достиг **" + newLevel + " уровня**!");
                channel.sendMessageEmbeds(eb.build()).queue();
            }
        }
    }

    private void checkLevelRoles(String discordId, int level) {
        if (!guildId.isEmpty() && levelConfig.levelRoles != null) {
            Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.retrieveMemberById(discordId).queue(member -> {
                    if (!canModifyMember(member)) return;
                    for (Map.Entry<Integer, String> entry : levelConfig.levelRoles.entrySet()) {
                        String roleId = entry.getValue();
                        if (roleId == null || roleId.isEmpty()) continue;
                        Role role = guild.getRoleById(roleId);
                        if (role != null) {
                            if (level >= entry.getKey() && !member.getRoles().contains(role)) {
                                guild.addRoleToMember(member, role).queue();
                            } else if (level < entry.getKey() && member.getRoles().contains(role)) {
                                guild.removeRoleFromMember(member, role).queue();
                            }
                        }
                    }
                });
            }
        }
    }

    private void sampleVoiceActivity() {
        if (!levelsEnabled || !levelConfig.voiceXpEnabled) return;
        for (Guild guild : jda.getGuilds()) {
            for (VoiceChannel vc : guild.getVoiceChannels()) {
                List<Member> realMembers = vc.getMembers().stream()
                        .filter(m -> !m.getUser().isBot())
                        .collect(Collectors.toList());
                if (realMembers.size() < levelConfig.voiceMinMembers) continue;

                for (Member member : realMembers) {
                    if (member.getVoiceState() == null) continue;
                    if (levelConfig.voiceExcludeAfk && member.getVoiceState().isSelfDeafened()) continue;

                    String id = member.getId();
                    voiceTotalSamples.merge(id, 1, Integer::sum);
                    if (!member.getVoiceState().isSelfMuted()) {
                        voiceUnmutedSamples.merge(id, 1, Integer::sum);
                    }
                }
            }
        }
    }

    private void awardVoiceXp() {
        if (!levelsEnabled || !levelConfig.voiceXpEnabled) return;

        for (Guild guild : jda.getGuilds()) {
            for (VoiceChannel vc : guild.getVoiceChannels()) {
                List<Member> realMembers = vc.getMembers().stream()
                        .filter(m -> !m.getUser().isBot())
                        .collect(Collectors.toList());
                if (realMembers.size() < levelConfig.voiceMinMembers) continue;

                for (Member member : realMembers) {
                    String id = member.getId();
                    int total = voiceTotalSamples.getOrDefault(id, 0);
                    int unmuted = voiceUnmutedSamples.getOrDefault(id, 0);

                    if (total > 0) {
                        double speakingFraction = (double) unmuted / total;
                        double xpPerMinute = levelConfig.voiceSpeakingXpPerMinute * speakingFraction
                                + levelConfig.voiceMutedXpPerMinute * (1 - speakingFraction);
                        if (member.getVoiceState() != null && member.getVoiceState().isStream()) {
                            xpPerMinute *= levelConfig.voiceStreamMultiplier;
                        }
                        int minutes = levelConfig.voiceCheckInterval;
                        addXp(id, (int) (xpPerMinute * minutes));

                        // Квестовый прогресс
                        addQuestProgress(id, "discord_voice_minutes", minutes);
                        if (member.getVoiceState() != null && member.getVoiceState().isStream()) {
                            addQuestProgress(id, "discord_stream_minutes", minutes);
                        }
                    }
                }
            }
        }
        voiceTotalSamples.clear();
        voiceUnmutedSamples.clear();
    }

    public void handleTextXp(MessageReceivedEvent event) {
        if (!levelsEnabled || !levelConfig.textXpEnabled) return;
        if (event.getAuthor().isBot()) return;
        String channelId = event.getChannel().getId();
        if (!levelConfig.textChannels.contains(channelId)) return;
        String content = event.getMessage().getContentRaw();
        if (content.length() < levelConfig.textMinLength) return;

        String discordId = event.getAuthor().getId();
        LevelData data = getLevelData(discordId);
        long now = System.currentTimeMillis();
        if (now - data.lastTextXp < TimeUnit.SECONDS.toMillis(levelConfig.textCooldownSeconds)) return;

        data.lastTextXp = now;
        addXp(discordId, levelConfig.textXpPerMessage);
        saveLevelData(discordId, data);
    }

    private long parseStatistic(String raw) {
        if (raw == null) return 0;
        String cleaned = raw.replaceAll("[^0-9]", "");
        if (cleaned.isEmpty()) return 0;
        try { return Long.parseLong(cleaned); } catch (NumberFormatException e) { return 0; }
    }

    private int getAdvancementCount(OfflinePlayer player) {
        UUID uuid = player.getUniqueId();
        if (uuid == null || uuid.toString().isEmpty()) return 0;
        int count = 0;
        File worldsDir = Bukkit.getWorldContainer();

        for (World world : Bukkit.getWorlds()) {
            File advFile = new File(worldsDir, world.getName() + "/advancements/" + uuid + ".json");
            if (!advFile.exists()) continue;

            try {
                String content = new String(Files.readAllBytes(advFile.toPath()));
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();
                JsonObject source = root.has("advancements") ? root.getAsJsonObject("advancements") : root;

                for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
                    String key = entry.getKey();
                    if (key.contains("recipes/")) continue;

                    JsonElement value = entry.getValue();
                    if (value.isJsonObject()) {
                        JsonObject advancement = value.getAsJsonObject();
                        if (advancement.has("done") && advancement.get("done").getAsBoolean()) {
                            count++;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return count;
    }

    public String getFieldValue(OfflinePlayer player, String placeholder) {
        switch (placeholder) {
            case "_blank_": return "\u200B";
            case "_discord_": {
                UUID uuid = player.getUniqueId();
                String discordId = linkedAccounts.get(uuid);
                if (discordId != null) {
                    return "<@" + discordId + ">";
                } else {
                    return "—";
                }
            }
            case "_monster_kills_": {
                String[] hostileMobs = {"Zombie","Skeleton","Creeper","Spider","Enderman","Witch","Slime","Ghast","Blaze","Wither_Skeleton","Phantom","Drowned","Guardian","Elder_Guardian","Hoglin","Piglin","Zombified_Piglin","Magma_Cube","Silverfish","Endermite"};
                long total = 0;
                for (String mob : hostileMobs) {
                    total += parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_kill_entity:" + mob + "%"));
                }
                return String.valueOf(total);
            }
            case "_average_life_": {
                long mins = parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_play_one_minute%"));
                long deaths = parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_deaths%"));
                if (deaths > 0 && mins > 0) return String.format("%.0f мин.", (double) mins / deaths);
                else return "∞";
            }
            case "_damage_dealt_hearts_": {
                try {
                    long raw = parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_damage_dealt%"));
                    double hearts = raw / 2.0;
                    return hearts >= 10 ? String.format("%.0f ❤", hearts) : String.format("%.1f ❤", hearts);
                } catch (Exception e) {
                    return "—";
                }
            }
            case "_damage_taken_hearts_": {
                try {
                    long raw = parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_damage_taken%"));
                    double hearts = raw / 2.0;
                    return hearts >= 10 ? String.format("%.0f ❤", hearts) : String.format("%.1f ❤", hearts);
                } catch (Exception e) {
                    return "—";
                }
            }
            case "_distance_m_": {
                long cm = parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_walk_one_cm%"));
                if (cm >= 100000) return String.format("%.1f км", cm / 100000.0);
                else return String.format("%.0f м", cm / 100.0);
            }
            case "_efficiency_": {
                long mins = parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_play_one_minute%"));
                long blocks = parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_mine_block%"));
                if (mins > 0 && blocks > 0) return String.format("%.0f бл/ч", blocks / (mins / 60.0));
                else return "—";
            }
            case "_level_": {
                String discordId = linkedAccounts.get(player.getUniqueId());
                if (discordId != null && levelsEnabled) {
                    return String.valueOf(getLevelData(discordId).level);
                } else {
                    return "—";
                }
            }
            case "_xp_": {
                String discordId = linkedAccounts.get(player.getUniqueId());
                if (discordId != null && levelsEnabled) {
                    LevelData data = getLevelData(discordId);
                    int next = getXpForLevel(data.level + 1);
                    return data.xp + " / " + next;
                } else {
                    return "—";
                }
            }
            case "_achievement_count_": {
                UUID uuid = player.getUniqueId();
                if (!achievementsEnabled) return "—";
                List<String> earned = achievementsConfig.getStringList(uuid.toString());
                int total = achievements.size();
                return earned.size() + " / " + total;
            }
            case "advancements_count":
            case "advancements_completed": {
                int advCount = getAdvancementCount(player);
                return String.valueOf(advCount);
            }
            case "statistic_mine_block": {
                UUID uuid = player.getUniqueId();
                File statsFile = new File(Bukkit.getWorldContainer(), "world/stats/" + uuid + ".json");
                if (!statsFile.exists()) return "0";
                try {
                    String content = new String(Files.readAllBytes(statsFile.toPath()));
                    JsonObject root = JsonParser.parseString(content).getAsJsonObject();
                    JsonObject stats = root.getAsJsonObject("stats");
                    if (stats != null && stats.has("minecraft:mined")) {
                        JsonObject mined = stats.getAsJsonObject("minecraft:mined");
                        long total = 0;
                        for (Map.Entry<String, JsonElement> entry : mined.entrySet()) {
                            if (entry.getValue().isJsonPrimitive()) {
                                total += entry.getValue().getAsLong();
                            }
                        }
                        return String.valueOf(total);
                    }
                } catch (Exception ignored) {}
                return "0";
            }
            default: {
                String result = PlaceholderAPI.setPlaceholders(player, "%" + placeholder + "%");
                if (result == null || result.equals("%" + placeholder + "%") || result.equalsIgnoreCase("NO_WORKING")) {
                    return "—";
                }
                return result;
            }
        }
    }

    private OfflinePlayer findOfflinePlayer(String name) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);
        if (target.getName() != null && target.hasPlayedBefore()) return target;

        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null && p.getName().equalsIgnoreCase(name) && p.hasPlayedBefore()) {
                return p;
            }
        }

        target = Bukkit.getOfflinePlayer("." + name);
        if (target.getName() != null && target.hasPlayedBefore()) return target;
        target = Bukkit.getOfflinePlayer("*" + name);
        if (target.getName() != null && target.hasPlayedBefore()) return target;

        return null;
    }

    private List<Map.Entry<String, String>> getTopForCategoryFormatted(String placeholder, String categoryKey) {
        Map<String, OfflinePlayer> latestPlayers = new HashMap<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            String name = p.getName();
            if (name == null) continue;
            if (name.startsWith(".") || name.startsWith("*")) continue;
            OfflinePlayer existing = latestPlayers.get(name);
            if (existing == null || p.getLastSeen() > existing.getLastSeen()) {
                latestPlayers.put(name, p);
            }
        }

        Map<String, Long> scores = new HashMap<>();
        Map<String, String> formattedValues = new HashMap<>();
        Map<String, Long> lastLevelUpTimes = new HashMap<>();

        for (Map.Entry<String, OfflinePlayer> entry : latestPlayers.entrySet()) {
            String name = entry.getKey();
            OfflinePlayer p = entry.getValue();
            String rawValue = getFieldValue(p, placeholder);
            long numeric;
            if (placeholder.equals("statistic_time_played")) {
                numeric = parsePlaytimeToMinutes(rawValue);
            } else {
                numeric = parseStatistic(rawValue);
            }
            scores.put(name, numeric);
            formattedValues.put(name, formatCategoryValue(categoryKey, rawValue, numeric));

            if (placeholder.equals("_level_")) {
                String discordId = linkedAccounts.get(p.getUniqueId());
                if (discordId != null) {
                    LevelData data = getLevelData(discordId);
                    lastLevelUpTimes.put(name, data.lastLevelUp);
                } else {
                    lastLevelUpTimes.put(name, 0L);
                }
            }
        }

        Stream<Map.Entry<String, Long>> stream = scores.entrySet().stream();
        if (placeholder.equals("_level_")) {
            stream = stream.sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                    .thenComparingLong(e -> lastLevelUpTimes.getOrDefault(e.getKey(), 0L)));
        } else {
            stream = stream.sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                    .thenComparingLong(e -> {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
                        return op.getFirstPlayed();
                    }));
        }

        return stream
                .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), formattedValues.get(e.getKey())))
                .collect(Collectors.toList());
    }

    private long parsePlaytimeToMinutes(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        raw = raw.toLowerCase().replaceAll("\\s+", "");
        long total = 0;
        try {
            if (raw.contains("h")) {
                String[] parts = raw.split("h");
                total += Long.parseLong(parts[0]) * 60;
                if (parts.length > 1) raw = parts[1]; else raw = "";
            }
            if (raw.contains("m")) {
                String[] parts = raw.split("m");
                total += Long.parseLong(parts[0]);
            }
        } catch (NumberFormatException e) { return 0; }
        return total;
    }

    private String formatCategoryValue(String categoryKey, String rawValue, long numeric) {
        switch (categoryKey) {
            case "distance":
                if (numeric >= 100000) return String.format("%.1f км", numeric / 100000.0);
                else return String.format("%.0f м", numeric / 100.0);
            case "playtime":
                return rawValue;
            default:
                return rawValue;
        }
    }

    private MessageEmbed buildTopEmbed(String categoryKey, int page) {
        TopCategory cat = topCategories.get(categoryKey);
        List<Map.Entry<String, String>> list = getTopForCategoryFormatted(cat.placeholder, categoryKey);
        int from = page * topPageSize;
        int to = Math.min(from + topPageSize, list.size());

        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(new Color(0xE2DD60));
        eb.setTitle("🏆 Топ игроков: " + cat.label);
        if (list.isEmpty()) {
            eb.setDescription("Нет данных.");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                Map.Entry<String, String> e = list.get(i);
                sb.append("**").append(i + 1).append(".** `").append(e.getKey()).append("` — ").append(e.getValue()).append("\n");
            }
            eb.setDescription(sb.toString());
            eb.setFooter("Страница " + (page + 1) + "/" + Math.max(1, (list.size() + topPageSize - 1) / topPageSize));
        }
        return eb.build();
    }

    private List<ActionRow> buildTopButtons(String currentCategory, int currentPage) {
        List<Button> catButtons = new ArrayList<>();
        for (Map.Entry<String, TopCategory> entry : topCategories.entrySet()) {
            String key = entry.getKey();
            catButtons.add(Button.secondary("top_cat_" + key, entry.getValue().label)
                    .withDisabled(key.equals(currentCategory)));
        }
        List<ActionRow> rows = new ArrayList<>();
        int maxPerRow = 5;
        for (int i = 0; i < catButtons.size(); i += maxPerRow) {
            int end = Math.min(i + maxPerRow, catButtons.size());
            rows.add(ActionRow.of(catButtons.subList(i, end)));
        }
        Button prevBtn = Button.secondary("top_page_" + currentCategory + "_" + (currentPage - 1), "◀️ Назад")
                .withDisabled(currentPage <= 0);
        Button nextBtn = Button.secondary("top_page_" + currentCategory + "_" + (currentPage + 1), "Вперед ▶️");
        rows.add(ActionRow.of(prevBtn, nextBtn));
        return rows;
    }

    // ================== СЛУШАТЕЛИ ====================

    private static class CommandListener extends ListenerAdapter {
        private final IndepProfileBot plugin;
        public CommandListener(IndepProfileBot plugin) { this.plugin = plugin; }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;
            String content = event.getMessage().getContentRaw();
            String lower = content.toLowerCase();

            // Квестовый прогресс для Discord
            String userId = event.getAuthor().getId();
            plugin.addQuestProgress(userId, "discord_chat", 1);
            if (content.contains("**") || content.contains("*") || content.contains("__") ||
                content.contains("~~") || content.contains("||") || content.contains("```")) {
                plugin.addQuestProgressByEvent(userId, "discord_formatted_message", 1);
            }

            if (lower.equals("!quests")) {
                String uid = event.getAuthor().getId();
                if (plugin.playerQuestDataMap.get(uid) == null || plugin.needNewQuests(uid)) {
                    plugin.generateDailyQuests(uid);
                }
                List<QuestSlot> slots = new ArrayList<>();
                PlayerQuestData data = plugin.playerQuestDataMap.get(uid);
                if (data != null) slots = data.slots;
                EmbedBuilder embed = new EmbedBuilder();
                embed.setColor(new Color(0xFFA500));
                embed.setTitle("📋 Ежедневные задания");
                embed.setDescription("До сброса в 00:00 МСК");
                if (slots.isEmpty()) {
                    embed.addField("Нет активных квестов", "Попробуйте перезайти на сервер или повторите !quests", false);
                } else {
                    for (QuestSlot slot : slots) {
                        String progressBar = plugin.makeProgressBar(slot.progress, slot.template.target);
                        String fieldValue = progressBar + " " + slot.progress + "/" + slot.template.target +
                                            " (XP: " + slot.template.reward + ")\n" +
                                            (slot.template.description != null ? slot.template.description : "");
                        embed.addField(
                            (slot.completed ? "✅ " : "") + slot.template.label,
                            fieldValue,
                            false
                        );
                    }
                }
                event.getChannel().sendMessageEmbeds(embed.build()).queue();
                return;
            }

            if (lower.startsWith("!top")) {
                String defaultCategory = plugin.topCategories.keySet().iterator().next();
                TopState state = new TopState(defaultCategory, 0);
                MessageEmbed embed = plugin.buildTopEmbed(defaultCategory, 0);
                event.getChannel().sendMessageEmbeds(embed)
                        .setComponents(plugin.buildTopButtons(defaultCategory, 0))
                        .queue(message -> plugin.topStates.put(message.getId(), state));
                return;
            }

            if (lower.startsWith("!link ") && plugin.twoFactorEnabled) {
                plugin.handleLinkCommand(event, content.substring(6).trim());
                return;
            }

            if (lower.equals("!unlink") && plugin.twoFactorEnabled) {
                plugin.handleUnlinkCommand(event);
                return;
            }

            if (lower.equals("!me")) {
                showProfile(event, null, true);
                return;
            }

            if (lower.equals("!rank") || lower.equals("!level")) {
                showRank(event);
                return;
            }

            String[] prefixes = {"!profile ", "!p ", "!stats ", "!myprofile "};
            String targetName = null;
            for (String prefix : prefixes) {
                if (lower.startsWith(prefix)) {
                    targetName = content.substring(prefix.length()).trim();
                    break;
                }
            }
            if (targetName == null || targetName.isEmpty()) {
                if (lower.equals("!profile") || lower.equals("!p") || lower.equals("!stats") || lower.equals("!myprofile")) {
                    event.getMessage().reply("ℹ️ Используйте: `!profile <ник>`").queue();
                }
                return;
            }

            showProfile(event, targetName, false);
        }

        private void showProfile(MessageReceivedEvent event, String targetName, boolean isSelf) {
            OfflinePlayer target;
            if (isSelf) {
                String discordId = event.getAuthor().getId();
                UUID playerUuid = null;
                for (Map.Entry<UUID, String> entry : plugin.linkedAccounts.entrySet()) {
                    if (entry.getValue().equals(discordId)) {
                        playerUuid = entry.getKey();
                        break;
                    }
                }
                if (playerUuid == null) {
                    event.getMessage().reply("❌ Ваш Discord не привязан к аккаунту Minecraft. Используйте `!link <код>`, полученный при входе на сервер.").queue();
                    return;
                }
                target = Bukkit.getOfflinePlayer(playerUuid);
                if (!target.hasPlayedBefore()) {
                    event.getMessage().reply("❌ Ваш Minecraft-аккаунт не найден.").queue();
                    return;
                }
            } else {
                target = plugin.findOfflinePlayer(targetName);
                if (target == null) {
                    event.getMessage().reply("❌ Игрок не найден.").queue();
                    return;
                }
            }

            sendProfileEmbed(event.getChannel().asGuildMessageChannel(), target);
        }

        private void sendProfileEmbed(GuildMessageChannel channel, OfflinePlayer player) {
            EmbedBuilder embed = new EmbedBuilder().setColor(new Color(0x5865F2));
            embed.setAuthor(player.getName(), null, "https://minotar.net/avatar/" + player.getName() + "/128");
            embed.setThumbnail("https://minotar.net/avatar/" + player.getName() + "/128");
            for (Map<?, ?> fieldMap : plugin.profileFields) {
                String emoji = (String) fieldMap.get("emoji");
                String label = (String) fieldMap.get("label");
                String placeholder = (String) fieldMap.get("placeholder");
                boolean inline = fieldMap.containsKey("inline") && (boolean) fieldMap.get("inline");
                embed.addField(emoji + " " + label, plugin.getFieldValue(player, placeholder), inline);
            }
            embed.setFooter("Запрошено через Discord бота");
            channel.sendMessageEmbeds(embed.build()).queue();
        }

        private void showRank(MessageReceivedEvent event) {
            if (!plugin.levelsEnabled) {
                event.getMessage().reply("❌ Система уровней отключена.").queue();
                return;
            }
            String discordId = event.getAuthor().getId();
            LevelData data = plugin.getLevelData(discordId);
            int nextLevelXp = plugin.getXpForLevel(data.level + 1);
            int currentXp = data.xp;
            double percent = (double) currentXp / nextLevelXp;
            int bars = 10;
            int filled = Math.min((int) (percent * bars), bars);
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < bars; i++) {
                bar.append(i < filled ? "🟦" : "⬜");
            }

            EmbedBuilder eb = new EmbedBuilder();
            eb.setColor(new Color(0x5865F2));
            eb.setAuthor(event.getAuthor().getEffectiveName(), null, event.getAuthor().getEffectiveAvatarUrl());
            eb.setThumbnail(event.getAuthor().getEffectiveAvatarUrl());
            eb.addField("⭐ Уровень " + data.level, bar.toString(), false);
            eb.addField("✨ Опыт", currentXp + " / " + nextLevelXp + " XP (" + String.format("%.0f", percent * 100) + "%)", true);
            eb.setFooter("Заработано за общение");

            event.getMessage().replyEmbeds(eb.build()).queue();
        }
    }

    private static class SlashCommandListener extends ListenerAdapter {
        private final IndepProfileBot plugin;
        public SlashCommandListener(IndepProfileBot plugin) { this.plugin = plugin; }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            switch (event.getName()) {
                case "profile":
                    event.deferReply().queue();
                    String nick = event.getOption("ник").getAsString();
                    OfflinePlayer target = plugin.findOfflinePlayer(nick);
                    if (target == null) {
                        event.getHook().sendMessage("❌ Игрок не найден.").queue();
                    } else {
                        EmbedBuilder embed = new EmbedBuilder().setColor(new Color(0x5865F2));
                        embed.setAuthor(target.getName(), null, "https://minotar.net/avatar/" + target.getName() + "/128");
                        embed.setThumbnail("https://minotar.net/avatar/" + target.getName() + "/128");
                        for (Map<?, ?> fieldMap : plugin.profileFields) {
                            String emoji = (String) fieldMap.get("emoji");
                            String label = (String) fieldMap.get("label");
                            String placeholder = (String) fieldMap.get("placeholder");
                            boolean inline = fieldMap.containsKey("inline") && (boolean) fieldMap.get("inline");
                            embed.addField(emoji + " " + label, plugin.getFieldValue(target, placeholder), inline);
                        }
                        embed.setFooter("Запрошено через слеш-команду");
                        event.getHook().sendMessageEmbeds(embed.build()).queue();
                    }
                    break;
                case "top":
                    event.deferReply().queue();
                    String defaultCategory = plugin.topCategories.keySet().iterator().next();
                    MessageEmbed topEmbed = plugin.buildTopEmbed(defaultCategory, 0);
                    event.getHook().sendMessageEmbeds(topEmbed)
                            .addComponents(plugin.buildTopButtons(defaultCategory, 0))
                            .queue();
                    break;
                case "me":
                    event.deferReply().queue();
                    String discordId = event.getUser().getId();
                    UUID playerUuid = null;
                    for (Map.Entry<UUID, String> entry : plugin.linkedAccounts.entrySet()) {
                        if (entry.getValue().equals(discordId)) {
                            playerUuid = entry.getKey();
                            break;
                        }
                    }
                    if (playerUuid == null) {
                        event.getHook().sendMessage("❌ Ваш Discord не привязан к Minecraft. Используйте `!link <код>`.").queue();
                    } else {
                        OfflinePlayer self = Bukkit.getOfflinePlayer(playerUuid);
                        if (!self.hasPlayedBefore()) {
                            event.getHook().sendMessage("❌ Ваш Minecraft-аккаунт не найден.").queue();
                        } else {
                            EmbedBuilder selfEmbed = new EmbedBuilder().setColor(new Color(0x5865F2));
                            selfEmbed.setAuthor(self.getName(), null, "https://minotar.net/avatar/" + self.getName() + "/128");
                            selfEmbed.setThumbnail("https://minotar.net/avatar/" + self.getName() + "/128");
                            for (Map<?, ?> fieldMap : plugin.profileFields) {
                                String emoji = (String) fieldMap.get("emoji");
                                String label = (String) fieldMap.get("label");
                                String placeholder = (String) fieldMap.get("placeholder");
                                boolean inline = fieldMap.containsKey("inline") && (boolean) fieldMap.get("inline");
                                selfEmbed.addField(emoji + " " + label, plugin.getFieldValue(self, placeholder), inline);
                            }
                            selfEmbed.setFooter("Запрошено через слеш-команду");
                            event.getHook().sendMessageEmbeds(selfEmbed.build()).queue();
                        }
                    }
                    break;
                case "rank":
                    event.deferReply().queue();
                    if (!plugin.levelsEnabled) {
                        event.getHook().sendMessage("❌ Система уровней отключена.").queue();
                    } else {
                        String userId = event.getUser().getId();
                        LevelData data = plugin.getLevelData(userId);
                        int nextLevelXp = plugin.getXpForLevel(data.level + 1);
                        int currentXp = data.xp;
                        double percent = (double) currentXp / nextLevelXp;
                        int bars = 10;
                        int filled = Math.min((int) (percent * bars), bars);
                        StringBuilder bar = new StringBuilder();
                        for (int i = 0; i < bars; i++) {
                            bar.append(i < filled ? "🟦" : "⬜");
                        }
                        EmbedBuilder rankEmbed = new EmbedBuilder();
                        rankEmbed.setColor(new Color(0x5865F2));
                        rankEmbed.setAuthor(event.getUser().getEffectiveName(), null, event.getUser().getEffectiveAvatarUrl());
                        rankEmbed.setThumbnail(event.getUser().getEffectiveAvatarUrl());
                        rankEmbed.addField("⭐ Уровень " + data.level, bar.toString(), false);
                        rankEmbed.addField("✨ Опыт", currentXp + " / " + nextLevelXp + " XP (" + String.format("%.0f", percent * 100) + "%)", true);
                        rankEmbed.setFooter("Заработано за общение");
                        event.getHook().sendMessageEmbeds(rankEmbed.build()).queue();
                    }
                    break;
                case "link":
                case "unlink":
                    event.reply("ℹ️ Пожалуйста, используйте текстовую команду `!link <код>` или `!unlink`.").setEphemeral(true).queue();
                    break;
            }
        }
    }

    private static class ButtonListener extends ListenerAdapter {
        private final IndepProfileBot plugin;
        public ButtonListener(IndepProfileBot plugin) { this.plugin = plugin; }

        @Override
        public void onButtonInteraction(ButtonInteractionEvent event) {
            String componentId = event.getComponentId();

            if (componentId.startsWith("2fa_confirm_") && plugin.twoFactorEnabled) {
                plugin.handleConfirmButton(event, UUID.fromString(componentId.substring(12)));
                return;
            }

            String msgId = event.getMessageId();
            TopState state = plugin.topStates.get(msgId);
            if (state == null) {
                event.reply("⏳ Сообщение устарело. Вызовите `!top` снова.").setEphemeral(true).queue();
                return;
            }

            if (componentId.startsWith("top_cat_")) {
                state.category = componentId.substring(8);
                state.page = 0;
            } else if (componentId.startsWith("top_page_")) {
                String[] parts = componentId.substring(9).split("_");
                String cat = parts[0];
                int newPage = Integer.parseInt(parts[1]);
                if (cat.equals(state.category)) state.page = newPage;
            }

            MessageEmbed embed = plugin.buildTopEmbed(state.category, state.page);
            event.editMessageEmbeds(embed)
                    .setComponents(plugin.buildTopButtons(state.category, state.page)).queue();
        }
    }

    private static class LevelListener extends ListenerAdapter {
        private final IndepProfileBot plugin;
        public LevelListener(IndepProfileBot plugin) { this.plugin = plugin; }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            plugin.handleTextXp(event);
        }
    }

    private static class TopCategory { final String label; final String placeholder; TopCategory(String l, String p) { label = l; placeholder = p; } }
    private static class TopState { String category; int page; TopState(String c, int p) { category = c; page = p; } }
    private static class SessionInfo { String ip; long expiry; SessionInfo(String i, long e) { ip = i; expiry = e; } }
    private static class PendingVerification { String ip; long timestamp; PendingVerification(String i, long t) { ip = i; timestamp = t; } }
    private static class Achievement {
        String id, label, placeholder, roleId;
        long threshold;
        Achievement(String id, String label, String placeholder, long threshold, String roleId) {
            this.id = id; this.label = label; this.placeholder = placeholder;
            this.threshold = threshold; this.roleId = roleId;
        }
    }
    private static class LevelConfig {
        boolean textXpEnabled, voiceXpEnabled, voiceExcludeAfk, mcChatXpEnabled;
        boolean minecraftChatPrefixEnabled;
        String minecraftChatPrefixFormat;
        List<String> textChannels;
        int textXpPerMessage, textCooldownSeconds, textMinLength, voiceCheckInterval, voiceMinMembers, voiceSampleIntervalSeconds;
        double voiceStreamMultiplier;
        int voiceSpeakingXpPerMinute, voiceMutedXpPerMinute;
        int mcChatXpPerMessage, mcChatCooldownSeconds, mcChatMinLength;
        int base;
        double exponent;
        Map<Integer, String> levelRoles;
        Map<Integer, List<String>> levelRewards;
    }
    private static class LevelData {
        int xp, level;
        long lastTextXp;
        long lastLevelUp;
        int highestRewardClaimed;
    }

    // ---------- КЛАССЫ ДЛЯ КВЕСТОВ ----------
    public static class QuestTemplate {
        String id;
        String label;
        String description;
        String type;
        String stat;
        String event;
        int target;
        int reward;
    }

    public static class QuestSlot {
        String questId;
        String poolType;
        QuestTemplate template;
        int progress;
        boolean completed;
    }

    public static class PlayerQuestData {
        String date;
        List<QuestSlot> slots = new ArrayList<>();
    }
}
