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
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
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
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IndepProfileBot extends JavaPlugin implements Listener {

    private JDA jda;
    public List<Map<?, ?>> profileFields;
    public Map<String, TopCategory> topCategories;
    private int topPageSize;
    public final Map<String, TopState> topStates = new ConcurrentHashMap<>();

    // 2FA
    public boolean twoFactorEnabled;
    private int sessionDurationMinutes;
    private int verificationTimeoutMinutes;
    private String linkKickMessage;
    private String verifyKickMessage;
    private final Map<String, String> linkCodes = new ConcurrentHashMap<>();
    public final Map<UUID, String> linkedAccounts = new ConcurrentHashMap<>();
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

    // Система уровней (PawPass) – хранится по UUID
    public boolean levelsEnabled;
    public LevelConfig levelConfig;
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
    public ZoneId questTimezone;
    private ScheduledExecutorService questScheduler;

    private final Map<String, QuestTemplate> normalPool = new LinkedHashMap<>();
    private final Map<String, QuestTemplate> rarePool = new LinkedHashMap<>();
    private final Map<String, QuestTemplate> legendaryPool = new LinkedHashMap<>();

    public final Map<String, PlayerQuestData> playerQuestDataMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> statSnapshots = new ConcurrentHashMap<>();

    private File questProgressFile;
    private FileConfiguration questProgressConfig;

    // ---------- ГЛОБАЛЬНЫЙ КВЕСТ ----------
    public boolean globalQuestEnabled;
    public String globalQuestStat;
    public long globalQuestTarget;
    public String globalQuestDesc;
    public int globalQuestRewardXp;
    public List<String> globalQuestCommands;
    
    public long globalQuestProgress;
    public boolean globalQuestCompleted;
    public Set<String> globalQuestParticipants = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

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

            levelConfig.levelRewards = new HashMap<>();
            ConfigurationSection rewardsSec = levelSec.getConfigurationSection("level-rewards");
            if (rewardsSec != null) {
                for (String levelStr : rewardsSec.getKeys(false)) {
                    levelConfig.levelRewards.put(Integer.parseInt(levelStr), rewardsSec.getStringList(levelStr));
                }
            }
            
            levelConfig.rewardPreviews = new HashMap<>();
            ConfigurationSection previewsSec = levelSec.getConfigurationSection("reward-previews");
            if (previewsSec != null) {
                for (String levelStr : previewsSec.getKeys(false)) {
                    levelConfig.rewardPreviews.put(Integer.parseInt(levelStr), previewsSec.getString(levelStr));
                }
            }

            levelUpChannelId = levelSec.getString("level-up-channel", "");

            levelsFile = new File(getDataFolder(), "levels.yml");
            if (!levelsFile.exists()) {
                try { levelsFile.createNewFile(); } catch (IOException ignored) {}
            }
            levelsConfig = YamlConfiguration.loadConfiguration(levelsFile);
        }

        // Инициализация Глобального Квеста
        ConfigurationSection gqSec = getConfig().getConfigurationSection("global-quest");
        if (gqSec != null) {
            globalQuestEnabled = gqSec.getBoolean("enabled", false);
            globalQuestStat = gqSec.getString("stat");
            globalQuestTarget = gqSec.getLong("target", 50000);
            globalQuestDesc = gqSec.getString("description", "Глобальная цель");
            globalQuestRewardXp = gqSec.getInt("reward-xp", 500);
            globalQuestCommands = gqSec.getStringList("commands");
        }

        String token = getConfig().getString("bot-token");
        if (token == null || token.isEmpty() || token.equals("ВСТАВЬТЕ_ВАШ_ТОКЕН_БОТА")) {
            getLogger().severe("Не указан токен бота в config.yml! Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
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
                    Commands.slash("rank", "Показать PawPass и опыт"),
                    Commands.slash("quests", "Ежедневные квесты")
                ).queue();

                getLogger().info("Discord бот запущен. Все модули активны.");
            } catch (Exception e) {
                getLogger().severe("Ошибка запуска Discord бота: " + e.getMessage());
                e.printStackTrace();
            }
        });

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

        loadQuestConfig();
        startQuestResetTask();
        startMinecraftStatTracker();
    }

    @Override
    public void onDisable() {
        if (jda != null) jda.shutdown();
        if (questScheduler != null) questScheduler.shutdownNow();
    }

    // ==========================================
    //          КОМАНДЫ /ADMINXP И /UNLINKPLAYER
    // ==========================================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("adminxp")) {
            if (!sender.hasPermission("op") && !sender.hasPermission("indep.admin")) {
                sender.sendMessage("§cУ вас нет прав для использования этой команды.");
                return true;
            }
            if (args.length < 3 || !args[0].equalsIgnoreCase("add")) {
                sender.sendMessage("§cИспользование: /adminxp add <ник> <количество>");
                return true;
            }
            
            String targetName = args[1];
            int amount;
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cОшибка: Количество опыта должно быть числом.");
                return true;
            }

            OfflinePlayer target = findOfflinePlayer(targetName);
            if (target == null) {
                sender.sendMessage("§cИгрок с ником " + targetName + " не найден на сервере.");
                return true;
            }

            addXp(target.getUniqueId(), amount);
            sender.sendMessage("§aУспешно выдано " + amount + " XP игроку " + target.getName() + "!");
            return true;
        }
        else if (command.getName().equalsIgnoreCase("unlinkplayer")) {
            if (!sender.isOp() && !sender.hasPermission("indep.admin")) {
                sender.sendMessage("§cУ вас нет прав.");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage("§cИспользование: /unlinkplayer <ник>");
                return true;
            }
            OfflinePlayer target = findOfflinePlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cИгрок не найден.");
                return true;
            }
            UUID uuid = target.getUniqueId();
            String discordId = linkedAccounts.remove(uuid);
            if (discordId == null) {
                sender.sendMessage("§cИгрок не привязан.");
                return true;
            }
            linkedConfig.set(uuid.toString(), null);
            try { linkedConfig.save(linkedFile); } catch (IOException e) {
                getLogger().warning("Ошибка сохранения linked.yml");
            }
            sessions.remove(uuid);
            pendingVerifications.remove(uuid);
            if (!guildId.isEmpty() && jda != null) {
                Guild guild = jda.getGuildById(guildId);
                if (guild != null) {
                    guild.retrieveMemberById(discordId).queue(member -> {
                        if (member != null && member.getNickname() != null) {
                            member.modifyNickname(null).queue();
                        }
                    });
                }
            }
            sender.sendMessage("§aПривязка для " + target.getName() + " удалена.");
            return true;
        }
        return false;
    }

    private UUID getUuidByDiscord(String discordId) {
        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            if (entry.getValue().equals(discordId)) {
                return entry.getKey();
            }
        }
        return null;
    }

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
            t.commands = q.getStringList("commands");
            pool.put(key, t);
        }
    }

    private void loadQuestProgress() {
        ConfigurationSection dataSec = questProgressConfig.getConfigurationSection("data");
        if (dataSec != null) {
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
        
        ConfigurationSection globalSec = questProgressConfig.getConfigurationSection("global");
        if (globalSec != null) {
            globalQuestProgress = globalSec.getLong("progress", 0);
            globalQuestCompleted = globalSec.getBoolean("completed", false);
            List<String> parts = globalSec.getStringList("participants");
            if (parts != null) {
                globalQuestParticipants.addAll(parts);
            }
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
        
        questProgressConfig.set("global.progress", globalQuestProgress);
        questProgressConfig.set("global.completed", globalQuestCompleted);
        questProgressConfig.set("global.participants", new ArrayList<>(globalQuestParticipants));
        
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

    public UUID getUuidFromDiscord(String discordId) {
        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            if (entry.getValue().equals(discordId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void generateDailyQuests(String discordId) {
        PlayerQuestData data = playerQuestDataMap.computeIfAbsent(discordId, k -> new PlayerQuestData());
        data.date = LocalDate.now(questTimezone).toString();
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

    public boolean needNewQuests(String discordId) {
        PlayerQuestData data = playerQuestDataMap.get(discordId);
        if (data == null || !LocalDate.now(questTimezone).toString().equals(data.date) || data.slots.isEmpty()) {
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
                    addXp(getUuidByDiscord(discordId), slot.template.reward);
                    notifyQuestCompleted(discordId, slot);
                    executeQuestCommands(discordId, slot);
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
                    addXp(getUuidByDiscord(discordId), slot.template.reward);
                    notifyQuestCompleted(discordId, slot);
                    executeQuestCommands(discordId, slot);
                }
                saveQuestProgress();
                break;
            }
        }
        
        if (globalQuestEnabled && !globalQuestCompleted && globalQuestStat != null && globalQuestStat.equals(eventName)) {
            globalQuestProgress += amount;
            globalQuestParticipants.add(discordId);
            if (globalQuestProgress >= globalQuestTarget) {
                globalQuestProgress = globalQuestTarget;
                completeGlobalQuest();
            }
            saveQuestProgress();
        }
    }

    private void executeQuestCommands(String discordId, QuestSlot slot) {
        if (slot.template.commands != null && !slot.template.commands.isEmpty()) {
            UUID playerUuid = getUuidFromDiscord(discordId);
            if (playerUuid != null) {
                String playerName = Bukkit.getOfflinePlayer(playerUuid).getName();
                if (playerName != null) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        for (String cmd : slot.template.commands) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", playerName));
                        }
                    });
                }
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
        Bukkit.getScheduler().runTask(this, () -> {
            UUID uuid = getUuidFromDiscord(discordId);
            if (uuid != null) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    player.sendMessage("§a✅ Квест \"" + slot.template.label + "\" выполнен! +" + slot.template.reward + " XP");
                }
            }
        });
    }

    private void completeGlobalQuest() {
        globalQuestCompleted = true;
        
        for (String participantDiscordId : globalQuestParticipants) {
            addXp(getUuidByDiscord(participantDiscordId), globalQuestRewardXp);
            
            UUID playerUuid = getUuidFromDiscord(participantDiscordId);
            if (playerUuid != null) {
                String playerName = Bukkit.getOfflinePlayer(playerUuid).getName();
                if (playerName != null && globalQuestCommands != null && !globalQuestCommands.isEmpty()) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        for (String cmd : globalQuestCommands) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", playerName));
                        }
                    });
                }
            }
        }
        
        if (jda != null) {
            getLogger().info("Глобальный квест выполнен! Участники награждены.");
        }
    }

    public String makeProgressBar(int current, int max) {
        int bars = 10;
        int filled = Math.min((int) ((double) current / max * bars), bars);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < bars; i++) {
            bar.append(i < filled ? "🟦" : "⬜");
        }
        return bar.toString();
    }

    private void startQuestResetTask() {
        questScheduler = Executors.newScheduledThreadPool(1);
        ZonedDateTime now = ZonedDateTime.now(questTimezone);
        ZonedDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(questTimezone);
        long initialDelay = ChronoUnit.MILLIS.between(now, nextMidnight);

        questScheduler.scheduleAtFixedRate(() -> {
            String newDate = LocalDate.now(questTimezone).toString();
            for (Map.Entry<String, PlayerQuestData> entry : playerQuestDataMap.entrySet()) {
                if (!newDate.equals(entry.getValue().date)) {
                    generateDailyQuests(entry.getKey());
                }
            }
        }, initialDelay, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
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
                    
                    Set<String> statsToTrack = new HashSet<>();
                    if (globalQuestEnabled && !globalQuestCompleted && globalQuestStat != null && globalQuestStat.startsWith("statistic_")) {
                        statsToTrack.add(globalQuestStat);
                    }
                    
                    if (data != null && !data.slots.isEmpty()) {
                        for (QuestSlot slot : data.slots) {
                            if (!slot.completed && slot.template != null && "mc_stat_delta".equals(slot.template.type) && slot.template.stat != null) {
                                statsToTrack.add(slot.template.stat);
                            }
                        }
                    }

                    Map<String, Long> deltas = new HashMap<>();
                    for (String stat : statsToTrack) {
                        deltas.put(stat, getStatDelta(player, stat));
                    }

                    boolean saveNeeded = false;

                    if (globalQuestEnabled && !globalQuestCompleted && globalQuestStat != null) {
                        long d = deltas.getOrDefault(globalQuestStat, 0L);
                        if (d > 0) {
                            globalQuestProgress += d;
                            globalQuestParticipants.add(discordId);
                            saveNeeded = true;
                            if (globalQuestProgress >= globalQuestTarget) {
                                globalQuestProgress = globalQuestTarget;
                                completeGlobalQuest();
                            }
                        }
                    }

                    if (data != null && !data.slots.isEmpty()) {
                        for (QuestSlot slot : data.slots) {
                            if (slot.completed || slot.template == null) continue;
                            if ("mc_stat_delta".equals(slot.template.type) && slot.template.stat != null) {
                                long d = deltas.getOrDefault(slot.template.stat, 0L);
                                if (d > 0) {
                                    slot.progress += d;
                                    saveNeeded = true;
                                    if (slot.progress >= slot.template.target) {
                                        slot.progress = slot.template.target;
                                        slot.completed = true;
                                        addXp(uuid, slot.template.reward);
                                        notifyQuestCompleted(discordId, slot);
                                        executeQuestCommands(discordId, slot);
                                    }
                                }
                            }
                        }
                    }

                    if (saveNeeded) {
                        saveQuestProgress();
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
            } else if (player.getLastDamageCause() != null && 
                       player.getLastDamageCause().getCause() == EntityDamageEvent.DamageCause.LAVA) {
                handleQuestEvent(player, "player_death_by_lava", 1);
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

            if (levelsEnabled && levelConfig.levelRewards != null && !levelConfig.levelRewards.isEmpty()) {
                grantRewards(player);
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (levelsEnabled && levelConfig.minecraftChatPrefixEnabled) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            String discordId = linkedAccounts.get(uuid);
            if (discordId != null) {
                LevelData data = getLevelData(uuid);
                String format = levelConfig.minecraftChatPrefixFormat
                        .replace("{level}", String.valueOf(data.level));
                format = ChatColor.translateAlternateColorCodes('&', format);
                event.setFormat(format + event.getFormat());
            }
        }

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
            addXp(uuid, levelConfig.mcChatXpPerMessage);
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
                event.getMessage().reply("Ваш Discord уже привязан к аккаунту `" + existingName + "`. Обратитесь к администратору для отвязки.").queue();
                return;
            }
        }

        linkedAccounts.put(uuid, discordId);
        linkedConfig.set(uuid.toString(), discordId);
        try { linkedConfig.save(linkedFile); } catch (IOException e) { getLogger().warning("Ошибка сохранения linked.yml"); }
        event.getMessage().reply("✅ Аккаунт привязан! Можете заходить.").queue();
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
            return "локация не определена";
        }
    }

    // ==================== РАБОТА С УРОВНЯМИ (UUID) ====================
    public LevelData getLevelData(UUID uuid) {
        return levelCache.computeIfAbsent(uuid.toString(), k -> {
            LevelData data = new LevelData();
            data.xp = levelsConfig.getInt(k + ".xp", 0);
            data.level = levelsConfig.getInt(k + ".level", 1);
            data.totalXp = levelsConfig.getInt(k + ".totalXp", 0);
            data.lastTextXp = levelsConfig.getLong(k + ".lastTextXp", 0);
            data.lastLevelUp = levelsConfig.getLong(k + ".lastLevelUp", 0);
            data.highestRewardClaimed = levelsConfig.getInt(k + ".highestRewardClaimed", 0);
            return data;
        });
    }

    private void saveLevelData(UUID uuid, LevelData data) {
        String key = uuid.toString();
        levelsConfig.set(key + ".xp", data.xp);
        levelsConfig.set(key + ".level", data.level);
        levelsConfig.set(key + ".totalXp", data.totalXp);
        levelsConfig.set(key + ".lastTextXp", data.lastTextXp);
        levelsConfig.set(key + ".lastLevelUp", data.lastLevelUp);
        levelsConfig.set(key + ".highestRewardClaimed", data.highestRewardClaimed);
        try { levelsConfig.save(levelsFile); } catch (IOException e) {
            getLogger().warning("Не удалось сохранить levels.yml");
        }
    }

    public int getXpForLevel(int level) {
        return (int) (levelConfig.base * Math.pow(level, levelConfig.exponent));
    }

    public void addXp(UUID uuid, int amount) {
        if (uuid == null) return;
        LevelData data = getLevelData(uuid);
        data.xp += amount;
        data.totalXp += amount;
        
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
            String discordId = linkedAccounts.get(uuid);
            if (discordId != null) {
                notifyLevelUp(discordId, data.level);
            }
            
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.GOLD + "🎉 Поздравляем! Вы достигли " + ChatColor.GREEN + data.level + " уровня PawPass!" + ChatColor.GOLD + " Продолжайте в том же духе!");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                grantRewards(player);
            }
        }
        saveLevelData(uuid, data);
        String dId = linkedAccounts.get(uuid);
        if (dId != null) {
            checkLevelRoles(dId, data.level);
        }
    }

    private void grantRewards(Player player) {
        UUID uuid = player.getUniqueId();
        LevelData data = getLevelData(uuid);
        int currentLevel = data.level;
        int highestClaimed = data.highestRewardClaimed;
        if (currentLevel <= highestClaimed) return;

        String playerName = player.getName();
        for (int lvl = highestClaimed + 1; lvl <= currentLevel; lvl++) {
            List<String> commands = levelConfig.levelRewards.get(lvl);
            if (commands == null) continue;

            for (String cmd : commands) {
                String finalCmd = cmd.replace("{player}", playerName);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
            }
        }
        data.highestRewardClaimed = currentLevel;
        saveLevelData(uuid, data);
    }

    private void notifyLevelUp(String discordId, int newLevel) {
        if (jda == null) return;
        jda.retrieveUserById(discordId).queue(user -> {
            user.openPrivateChannel().queue(channel -> {
                EmbedBuilder eb = new EmbedBuilder();
                eb.setColor(new Color(0xFFD700));
                eb.setTitle("🎉 Поздравляем!");
                eb.setDescription("Вы достигли **" + newLevel + " уровня** PawPass!");
                eb.setFooter("Продолжайте играть и получать награды!");
                channel.sendMessageEmbeds(eb.build()).queue();
            });
        });

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
                    String discordId = member.getId();
                    UUID uuid = getUuidByDiscord(discordId);
                    if (uuid == null) continue;

                    int total = voiceTotalSamples.getOrDefault(discordId, 0);
                    int unmuted = voiceUnmutedSamples.getOrDefault(discordId, 0);

                    if (total > 0) {
                        double speakingFraction = (double) unmuted / total;
                        double xpPerMinute = levelConfig.voiceSpeakingXpPerMinute * speakingFraction
                                + levelConfig.voiceMutedXpPerMinute * (1 - speakingFraction);
                        if (member.getVoiceState() != null && member.getVoiceState().isStream()) {
                            xpPerMinute *= levelConfig.voiceStreamMultiplier;
                        }
                        int minutes = levelConfig.voiceCheckInterval;
                        addXp(uuid, (int) (xpPerMinute * minutes));

                        addQuestProgress(discordId, "voice", minutes);
                        if (member.getVoiceState() != null && member.getVoiceState().isStream()) {
                            addQuestProgress(discordId, "voice_stream", minutes);
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
        UUID uuid = getUuidByDiscord(discordId);
        if (uuid == null) return;

        LevelData data = getLevelData(uuid);
        long now = System.currentTimeMillis();
        if (now - data.lastTextXp < TimeUnit.SECONDS.toMillis(levelConfig.textCooldownSeconds)) return;

        data.lastTextXp = now;
        addXp(uuid, levelConfig.textXpPerMessage);
        saveLevelData(uuid, data);
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
                return (discordId != null) ? "<@" + discordId + ">" : "—";
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
                } catch (Exception e) { return "—"; }
            }
            case "_damage_taken_hearts_": {
                try {
                    long raw = parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_damage_taken%"));
                    double hearts = raw / 2.0;
                    return hearts >= 10 ? String.format("%.0f ❤", hearts) : String.format("%.1f ❤", hearts);
                } catch (Exception e) { return "—"; }
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
                UUID uuid = player.getUniqueId();
                if (levelsEnabled) {
                    return String.valueOf(getLevelData(uuid).level);
                } else {
                    return "—";
                }
            }
            case "_xp_": {
                UUID uuid = player.getUniqueId();
                if (levelsEnabled) {
                    LevelData data = getLevelData(uuid);
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

    public OfflinePlayer findOfflinePlayer(String name) {
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() == null) continue;
            String pName = p.getName();
            
            if (pName.equalsIgnoreCase(name)) return p;
            if (pName.equalsIgnoreCase("0" + name)) return p;
            if (pName.equalsIgnoreCase("." + name) || pName.equalsIgnoreCase("*" + name)) return p;
        }

        OfflinePlayer fallback = Bukkit.getOfflinePlayer(name);
        if (fallback != null && (fallback.hasPlayedBefore() || fallback.isOnline())) {
            return fallback;
        }

        return null;
    }

    private List<Map.Entry<String, String>> getTopForCategoryFormatted(String placeholder, String categoryKey) {
        Map<String, OfflinePlayer> latestPlayers = new HashMap<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            String name = p.getName();
            if (name == null) continue;
            if (name.startsWith(".") || name.startsWith("*") continue;
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
            if (placeholder.equals("_level_")) {
                UUID uuid = p.getUniqueId();
                LevelData data = getLevelData(uuid);
                numeric = data.totalXp; 
                lastLevelUpTimes.put(name, data.lastLevelUp);
                formattedValues.put(name, data.level + " Ур. (" + data.totalXp + " XP)");
            } else if (placeholder.equals("statistic_time_played")) {
                numeric = parsePlaytimeToMinutes(rawValue);
                formattedValues.put(name, formatCategoryValue(categoryKey, rawValue, numeric));
            } else {
                numeric = parseStatistic(rawValue);
                formattedValues.put(name, formatCategoryValue(categoryKey, rawValue, numeric));
            }
            scores.put(name, numeric);
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

    public long parsePlaytimeToMinutes(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        long total = 0;
        
        java.util.regex.Matcher mDays = java.util.regex.Pattern.compile("(\\d+)\\s*(d|д|day|дн)").matcher(raw.toLowerCase());
        if (mDays.find()) {
            try { total += Long.parseLong(mDays.group(1)) * 24 * 60; } catch (Exception ignored) {}
        }
        java.util.regex.Matcher mHours = java.util.regex.Pattern.compile("(\\d+)\\s*(h|ч|hour|час)").matcher(raw.toLowerCase());
        if (mHours.find()) {
            try { total += Long.parseLong(mHours.group(1)) * 60; } catch (Exception ignored) {}
        }
        java.util.regex.Matcher mMins = java.util.regex.Pattern.compile("(\\d+)\\s*(m|м|min|мин)").matcher(raw.toLowerCase());
        if (mMins.find()) {
            try { total += Long.parseLong(mMins.group(1)); } catch (Exception ignored) {}
        }
        
        return total;
    }

    private String formatCategoryValue(String categoryKey, String rawValue, long numeric) {
        switch (categoryKey) {
            case "distance":
                if (numeric >= 100000) return String.format("%.1f км", numeric / 100000.0);
                else return String.format("%.0f м", numeric / 100.0);
            case "playtime":
                return rawValue;
            case "level":
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
                
                PlayerQuestData data = plugin.playerQuestDataMap.get(uid);
                List<QuestSlot> slots = (data != null) ? data.slots : new ArrayList<>();
                
                EmbedBuilder embed = new EmbedBuilder();
                embed.setColor(new Color(0x2B2D31));
                embed.setTitle("📋 ЕЖЕДНЕВНЫЕ КВЕСТЫ");
                
                long midnightUnix = java.time.LocalDate.now(plugin.questTimezone)
                        .plusDays(1).atStartOfDay(plugin.questTimezone).toEpochSecond();
                embed.setDescription("🔄 Обновление <t:" + midnightUnix + ":R>\n*Выполняй задания, чтобы апать PawPass!*");

                if (plugin.globalQuestEnabled) {
                    String status = plugin.globalQuestCompleted 
                            ? "✅ Выполнено! Награды выданы." 
                            : plugin.makeProgressBar((int)plugin.globalQuestProgress, (int)plugin.globalQuestTarget) + " **" + plugin.globalQuestProgress + "/" + plugin.globalQuestTarget + "**";
                    
                    embed.addField("🌍 ГЛОБАЛЬНАЯ ЦЕЛЬ", 
                            "⚡ **Награда:** " + plugin.globalQuestRewardXp + " XP + Трофей\n" +
                            "💬 *" + plugin.globalQuestDesc + "*\n" + 
                            (plugin.globalQuestCompleted ? "" : status), false);
                }

                if (slots.isEmpty()) {
                    embed.addField("Нет активных квестов", "Попробуйте перезайти на сервер.", false);
                } else {
                    for (QuestSlot slot : slots) {
                        String rarityIcon = "⬜";
                        String titleSuffix = "";
                        if (slot.poolType.equals("rare")) {
                            rarityIcon = "🔷"; titleSuffix = " `[РЕДКОЕ]`";
                        } else if (slot.poolType.equals("legendary")) {
                            rarityIcon = "🟡"; titleSuffix = " `[СЕКРЕТНОЕ]`";
                        }
                        if (slot.completed) rarityIcon = "✅";

                        String progressBar = plugin.makeProgressBar(slot.progress, slot.template.target);
                        String desc = slot.template.description != null ? slot.template.description : "";
                        String rewardText = slot.poolType.equals("legendary") ? 
                                slot.template.reward + " XP + 🎁 Эксклюзивный Трофей" : 
                                slot.template.reward + " XP";

                        String fieldBody = progressBar + " **" + slot.progress + "/" + slot.template.target + "**\n" +
                                           "⚡ **Награда:** " + rewardText + "\n" +
                                           "💬 *" + desc + "*";

                        embed.addField(rarityIcon + " " + slot.template.label + titleSuffix, fieldBody, false);
                    }
                }
                
                embed.setFooter("Сезон 1 • Выполняй квесты каждый день");
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

            if (lower.equals("!me")) {
                showProfile(event, null, true);
                return;
            }

            if (lower.equals("!rank") || lower.equals("!pass") || lower.equals("!сезон")) {
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
            OfflinePlayer target = null;
            if (isSelf) {
                String discordId = event.getAuthor().getId();
                UUID playerUuid = plugin.getUuidFromDiscord(discordId);
                if (playerUuid == null) {
                    event.getMessage().reply("❌ Ваш Discord не привязан к аккаунту Minecraft. Используйте `!link <код>`.").queue();
                    return;
                }
                target = Bukkit.getOfflinePlayer(playerUuid);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
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
            String playerName = player.getName() != null ? player.getName() : "Unknown";
            String avatarUrl = "https://minotar.net/avatar/" + playerName + "/128";
            embed.setAuthor(playerName, null, avatarUrl);
            embed.setThumbnail(avatarUrl);
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
            UUID uuid = plugin.getUuidByDiscord(discordId);
            if (uuid == null) {
                event.getMessage().reply("❌ Ваш Discord не привязан к аккаунту Minecraft.").queue();
                return;
            }
            LevelData data = plugin.getLevelData(uuid);
            
            int currentLevel = data.level;
            int nextLevelXp = plugin.getXpForLevel(currentLevel + 1);
            int currentXp = data.xp;
            
            double percent = (double) currentXp / nextLevelXp;
            int bars = 10;
            int filled = Math.min((int) (percent * bars), bars);
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < bars; i++) {
                bar.append(i < filled ? "🟨" : "⬛"); 
            }

            EmbedBuilder eb = new EmbedBuilder();
            eb.setColor(new Color(0xFFD700)); 
            eb.setAuthor("PawPass: Сезон 1", null, event.getAuthor().getEffectiveAvatarUrl());
            
            eb.addField("⭐ Текущий уровень: " + currentLevel, 
                        bar.toString() + " **" + String.format("%.0f", percent * 100) + "%**\n" +
                        "Опыт: `" + currentXp + " / " + nextLevelXp + " XP`", false);

            String nextReward = plugin.levelConfig.rewardPreviews.getOrDefault(currentLevel + 1, "Новые ресурсы и бонусы");
            
            if (currentLevel < 20) {
                eb.addField("🎁 Награда за следующий уровень:", "> " + nextReward, false);
            } else {
                eb.addField("🏆 Максимальный уровень!", "> Вы достигли вершины этого сезона. Накапливайте XP для попадания в топ-1!", false);
            }

            eb.addField("📊 Общая статистика", 
                        "Всего заработано за сезон: **" + data.totalXp + " XP**\n" +
                        "*(Определяет ваше место в топе)*", false);

            eb.setThumbnail(event.getAuthor().getEffectiveAvatarUrl());
            eb.setFooter("Игрок: " + event.getAuthor().getEffectiveName());

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
                        String pName = target.getName() != null ? target.getName() : "Unknown";
                        String avatarUrl = "https://minotar.net/avatar/" + pName + "/128";
                        embed.setAuthor(pName, null, avatarUrl);
                        embed.setThumbnail(avatarUrl);
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
                    UUID playerUuid = plugin.getUuidFromDiscord(discordId);
                    
                    if (playerUuid == null) {
                        event.getHook().sendMessage("❌ Ваш Discord не привязан к Minecraft. Используйте `!link <код>`.").queue();
                    } else {
                        OfflinePlayer self = Bukkit.getOfflinePlayer(playerUuid);
                        if (!self.hasPlayedBefore() && !self.isOnline()) {
                            event.getHook().sendMessage("❌ Ваш Minecraft-аккаунт не найден.").queue();
                        } else {
                            EmbedBuilder selfEmbed = new EmbedBuilder().setColor(new Color(0x5865F2));
                            String pName = self.getName() != null ? self.getName() : "Unknown";
                            String avatarUrl = "https://minotar.net/avatar/" + pName + "/128";
                            selfEmbed.setAuthor(pName, null, avatarUrl);
                            selfEmbed.setThumbnail(avatarUrl);
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
                        UUID uuid = plugin.getUuidByDiscord(userId);
                        if (uuid == null) {
                            event.getHook().sendMessage("❌ Ваш Discord не привязан к Minecraft.").queue();
                            break;
                        }
                        LevelData data = plugin.getLevelData(uuid);
                        
                        int currentLevel = data.level;
                        int nextLevelXp = plugin.getXpForLevel(currentLevel + 1);
                        int currentXp = data.xp;
                        
                        double percent = (double) currentXp / nextLevelXp;
                        int bars = 10;
                        int filled = Math.min((int) (percent * bars), bars);
                        StringBuilder bar = new StringBuilder();
                        for (int i = 0; i < bars; i++) {
                            bar.append(i < filled ? "🟨" : "⬛"); 
                        }

                        EmbedBuilder rankEmbed = new EmbedBuilder();
                        rankEmbed.setColor(new Color(0xFFD700)); 
                        rankEmbed.setAuthor("PawPass: Сезон 1", null, event.getUser().getEffectiveAvatarUrl());
                        
                        rankEmbed.addField("⭐ Текущий уровень: " + currentLevel, 
                                    bar.toString() + " **" + String.format("%.0f", percent * 100) + "%**\n" +
                                    "Опыт: `" + currentXp + " / " + nextLevelXp + " XP`", false);

                        String nextReward = plugin.levelConfig.rewardPreviews.getOrDefault(currentLevel + 1, "Новые ресурсы и бонусы");
                        
                        if (currentLevel < 20) {
                            rankEmbed.addField("🎁 Награда за следующий уровень:", "> " + nextReward, false);
                        } else {
                            rankEmbed.addField("🏆 Максимальный уровень!", "> Вы достигли вершины этого сезона. Накапливайте XP для попадания в топ-1!", false);
                        }

                        rankEmbed.addField("📊 Общая статистика", 
                                    "Всего заработано за сезон: **" + data.totalXp + " XP**\n" +
                                    "*(Определяет ваше место в топе)*", false);

                        rankEmbed.setThumbnail(event.getUser().getEffectiveAvatarUrl());
                        rankEmbed.setFooter("Игрок: " + event.getUser().getEffectiveName());

                        event.getHook().sendMessageEmbeds(rankEmbed.build()).queue();
                    }
                    break;
                case "quests":
                    event.deferReply().queue();
                    String uId = event.getUser().getId();
                    if (plugin.playerQuestDataMap.get(uId) == null || plugin.needNewQuests(uId)) {
                        plugin.generateDailyQuests(uId);
                    }
                    PlayerQuestData qData = plugin.playerQuestDataMap.get(uId);
                    List<QuestSlot> qSlots = (qData != null) ? qData.slots : new ArrayList<>();
                    
                    EmbedBuilder qEmbed = new EmbedBuilder();
                    qEmbed.setColor(new Color(0x2B2D31));
                    qEmbed.setTitle("📋 ЕЖЕДНЕВНЫЕ КВЕСТЫ");
                    
                    long midnightUnix = java.time.LocalDate.now(plugin.questTimezone)
                            .plusDays(1).atStartOfDay(plugin.questTimezone).toEpochSecond();
                    qEmbed.setDescription("🔄 Обновление <t:" + midnightUnix + ":R>\n*Выполняй задания, чтобы апать PawPass!*");

                    if (plugin.globalQuestEnabled) {
                        String status = plugin.globalQuestCompleted 
                                ? "✅ Выполнено! Награды выданы." 
                                : plugin.makeProgressBar((int)plugin.globalQuestProgress, (int)plugin.globalQuestTarget) + " **" + plugin.globalQuestProgress + "/" + plugin.globalQuestTarget + "**";
                        
                        qEmbed.addField("🌍 ГЛОБАЛЬНАЯ ЦЕЛЬ", 
                                "⚡ **Награда:** " + plugin.globalQuestRewardXp + " XP + Трофей\n" +
                                "💬 *" + plugin.globalQuestDesc + "*\n" + 
                                (plugin.globalQuestCompleted ? "" : status), false);
                    }

                    if (qSlots.isEmpty()) {
                        qEmbed.addField("Нет активных квестов", "Попробуйте перезайти на сервер.", false);
                    } else {
                        for (QuestSlot slot : qSlots) {
                            String rarityIcon = "⬜";
                            String titleSuffix = "";
                            if (slot.poolType.equals("rare")) {
                                rarityIcon = "🔷"; titleSuffix = " `[РЕДКОЕ]`";
                            } else if (slot.poolType.equals("legendary")) {
                                rarityIcon = "🟡"; titleSuffix = " `[СЕКРЕТНОЕ]`";
                            }
                            if (slot.completed) rarityIcon = "✅";

                            String progressBar = plugin.makeProgressBar(slot.progress, slot.template.target);
                            String desc = slot.template.description != null ? slot.template.description : "";
                            String rewardText = slot.poolType.equals("legendary") ? 
                                    slot.template.reward + " XP + 🎁 Эксклюзивный Трофей" : 
                                    slot.template.reward + " XP";

                            String fieldBody = progressBar + " **" + slot.progress + "/" + slot.template.target + "**\n" +
                                               "⚡ **Награда:** " + rewardText + "\n" +
                                               "💬 *" + desc + "*";

                            qEmbed.addField(rarityIcon + " " + slot.template.label + titleSuffix, fieldBody, false);
                        }
                    }
                    
                    qEmbed.setFooter("Сезон 1 • Выполняй квесты каждый день");
                    event.getHook().sendMessageEmbeds(qEmbed.build()).queue();
                    break;
                case "link":
                case "unlink":
                    event.reply("ℹ️ Пожалуйста, используйте текстовую команду `!link <код>` или обратитесь к администратору для отвязки.").setEphemeral(true).queue();
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
                String data = componentId.substring(9);
                int lastUnderscore = data.lastIndexOf('_');
                if (lastUnderscore == -1) return;
                String cat = data.substring(0, lastUnderscore);
                int newPage;
                try {
                    newPage = Integer.parseInt(data.substring(lastUnderscore + 1));
                } catch (NumberFormatException ex) {
                    return;
                }
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

    public static class TopCategory { public final String label; public final String placeholder; public TopCategory(String l, String p) { label = l; placeholder = p; } }
    public static class TopState { public String category; public int page; public TopState(String c, int p) { category = c; page = p; } }
    public static class SessionInfo { public String ip; public long expiry; public SessionInfo(String i, long e) { ip = i; expiry = e; } }
    public static class PendingVerification { public String ip; public long timestamp; public PendingVerification(String i, long t) { ip = i; timestamp = t; } }
    public static class Achievement {
        public String id, label, placeholder, roleId;
        public long threshold;
        public Achievement(String id, String label, String placeholder, long threshold, String roleId) {
            this.id = id; this.label = label; this.placeholder = placeholder;
            this.threshold = threshold; this.roleId = roleId;
        }
    }
    public static class LevelConfig {
        public boolean textXpEnabled, voiceXpEnabled, voiceExcludeAfk, mcChatXpEnabled;
        public boolean minecraftChatPrefixEnabled;
        public String minecraftChatPrefixFormat;
        public List<String> textChannels;
        public int textXpPerMessage, textCooldownSeconds, textMinLength, voiceCheckInterval, voiceMinMembers, voiceSampleIntervalSeconds;
        public double voiceStreamMultiplier;
        public int voiceSpeakingXpPerMinute, voiceMutedXpPerMinute;
        public int mcChatXpPerMessage, mcChatCooldownSeconds, mcChatMinLength;
        public int base;
        public double exponent;
        public Map<Integer, String> levelRoles;
        public Map<Integer, List<String>> levelRewards;
        public Map<Integer, String> rewardPreviews;
    }
    public static class LevelData {
        public int xp, level;
        public int totalXp;
        public long lastTextXp;
        public long lastLevelUp;
        public int highestRewardClaimed;
    }

    // ---------- КЛАССЫ ДЛЯ КВЕСТОВ ----------
    public static class QuestTemplate {
        public String id;
        public String label;
        public String description;
        public String type;
        public String stat;
        public String event;
        public int target;
        public int reward;
        public List<String> commands;
    }

    public static class QuestSlot {
        public String questId;
        public String poolType;
        public QuestTemplate template;
        public int progress;
        public boolean completed;
    }

    public static class PlayerQuestData {
        public String date;
        public List<QuestSlot> slots = new ArrayList<>();
    }
}
