package com.yourname.indeprofilebot;

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
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    private final Map<String, LevelData> levelCache = new ConcurrentHashMap<>();
    private File levelsFile;
    private FileConfiguration levelsConfig;

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
            levelConfig.voiceXpPerMinute = levelSec.getInt("voice-xp.xp-per-minute", 5);
            levelConfig.voiceCheckInterval = levelSec.getInt("voice-xp.check-interval-minutes", 5);
            levelConfig.voiceExcludeAfk = levelSec.getBoolean("voice-xp.exclude-afk", true);
            levelConfig.voiceMinMembers = levelSec.getInt("voice-xp.min-members", 2);
            levelConfig.voiceStreamMultiplier = levelSec.getDouble("voice-xp.multiplier-for-streaming", 1.5);
            levelConfig.base = levelSec.getInt("level-formula.base", 100);
            levelConfig.exponent = levelSec.getDouble("level-formula.exponent", 2.0);
            levelConfig.levelRoles = new HashMap<>();
            ConfigurationSection rolesSec = levelSec.getConfigurationSection("level-roles");
            if (rolesSec != null) {
                for (String levelStr : rolesSec.getKeys(false)) {
                    levelConfig.levelRoles.put(Integer.parseInt(levelStr), rolesSec.getString(levelStr));
                }
            }

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
                    .addEventListeners(new CommandListener(this), new ButtonListener(this), new LevelListener(this))
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
                    awardVoiceXp();
                }
            }.runTaskTimer(this, 20L * 30, 20L * 60 * levelConfig.voiceCheckInterval);
        }
    }

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

        if (discordId != null && !guildId.isEmpty() && (syncNick || syncRole)) {
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
        embed.setColor(new Color(0x5865F2));
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
                eb.setColor(new Color(0x5865F2));
                eb.setTitle("Подтверждение входа на сервер");
                eb.setDescription("Кто-то пытается войти под вашим аккаунтом Minecraft.\nIP: " + ip +
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
                event.getMessage().reply("❌ Неверный или устаревший код.").queue();
                return;
            }
            uuid = UUID.fromString(uuidStr);
        }
        String discordId = event.getAuthor().getId();

        for (Map.Entry<UUID, String> entry : linkedAccounts.entrySet()) {
            if (entry.getValue().equals(discordId)) {
                String existingName = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                event.getMessage().reply("❌ Ваш Discord уже привязан к аккаунту `" + existingName + "`. Сначала отвяжите его командой `!unlink`.").queue();
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
            event.getMessage().reply("❌ Ваш Discord не привязан ни к одному аккаунту.").queue();
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
        event.getMessage().reply("✅ Привязка удалена.").queue();
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

    private LevelData getLevelData(String discordId) {
        return levelCache.computeIfAbsent(discordId, k -> {
            LevelData data = new LevelData();
            data.xp = levelsConfig.getInt(k + ".xp", 0);
            data.level = levelsConfig.getInt(k + ".level", 1);
            data.lastTextXp = levelsConfig.getLong(k + ".lastTextXp", 0);
            return data;
        });
    }

    private void saveLevelData(String discordId, LevelData data) {
        levelsConfig.set(discordId + ".xp", data.xp);
        levelsConfig.set(discordId + ".level", data.level);
        levelsConfig.set(discordId + ".lastTextXp", data.lastTextXp);
        try { levelsConfig.save(levelsFile); } catch (IOException e) {
            getLogger().warning("Не удалось сохранить levels.yml");
        }
    }

    private int getXpForLevel(int level) {
        return (int) (levelConfig.base * Math.pow(level, levelConfig.exponent));
    }

    private void addXp(String discordId, int amount) {
        LevelData data = getLevelData(discordId);
        data.xp += amount;
        int nextLevelXp = getXpForLevel(data.level + 1);
        while (data.xp >= nextLevelXp) {
            data.xp -= nextLevelXp;
            data.level++;
            nextLevelXp = getXpForLevel(data.level + 1);
        }
        saveLevelData(discordId, data);
        checkLevelRoles(discordId, data.level);
    }

    private void checkLevelRoles(String discordId, int level) {
        if (!guildId.isEmpty() && levelConfig.levelRoles != null) {
            Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.retrieveMemberById(discordId).queue(member -> {
                    if (!canModifyMember(member)) return;
                    for (Map.Entry<Integer, String> entry : levelConfig.levelRoles.entrySet()) {
                        Role role = guild.getRoleById(entry.getValue());
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

    private void awardVoiceXp() {
        if (!levelsEnabled || !levelConfig.voiceXpEnabled) return;
        for (Guild guild : jda.getGuilds()) {
            for (VoiceChannel vc : guild.getVoiceChannels()) {
                List<Member> members = vc.getMembers();
                if (members.size() < levelConfig.voiceMinMembers) continue;
                for (Member member : members) {
                    if (member.getUser().isBot()) continue;
                    if (levelConfig.voiceExcludeAfk && member.getVoiceState() != null && member.getVoiceState().isSelfDeafened()) continue;
                    double multiplier = 1.0;
                    if (member.getVoiceState() != null && member.getVoiceState().isStream()) {
                        multiplier = levelConfig.voiceStreamMultiplier;
                    }
                    addXp(member.getId(), (int) (levelConfig.voiceXpPerMinute * levelConfig.voiceCheckInterval * multiplier));
                }
            }
        }
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
                long raw = parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_damage_dealt%"));
                double hearts = raw / 2.0;
                return hearts >= 10 ? String.format("%.0f ❤", hearts) : String.format("%.1f ❤", hearts);
            }
            case "_damage_taken_hearts_": {
                long raw = parseStatistic(PlaceholderAPI.setPlaceholders(player, "%statistic_damage_taken%"));
                double hearts = raw / 2.0;
                return hearts >= 10 ? String.format("%.0f ❤", hearts) : String.format("%.1f ❤", hearts);
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
            default: {
                return PlaceholderAPI.setPlaceholders(player, "%" + placeholder + "%");
            }
        }
    }

    private OfflinePlayer findOfflinePlayer(String name) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);
        if (target.getName() != null) return target;
        target = Bukkit.getOfflinePlayer("." + name);
        if (target.getName() != null) return target;
        target = Bukkit.getOfflinePlayer("*" + name);
        if (target.getName() != null) return target;
        return null;
    }

    private List<Map.Entry<String, String>> getTopForCategoryFormatted(String placeholder, String categoryKey) {
        Map<String, Long> scores = new HashMap<>();
        Map<String, String> formattedValues = new HashMap<>();

        // Собираем самых свежих игроков для каждого ника
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
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
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
        eb.setColor(new Color(0x5865F2));
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

    private static class CommandListener extends ListenerAdapter {
        private final IndepProfileBot plugin;
        public CommandListener(IndepProfileBot plugin) { this.plugin = plugin; }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;
            String content = event.getMessage().getContentRaw();
            String lower = content.toLowerCase();

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
                OfflinePlayer target = Bukkit.getOfflinePlayer(playerUuid);
                if (target.getName() == null) {
                    event.getMessage().reply("❌ Не удалось найти ваш Minecraft-аккаунт.").queue();
                    return;
                }

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
                embed.setFooter("Запрошено через Discord бота");
                event.getMessage().replyEmbeds(embed.build()).queue();
                return;
            }

            if (lower.equals("!rank") || lower.equals("!level")) {
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
                eb.setFooter("Заработано в сообществе");

                event.getMessage().replyEmbeds(eb.build()).queue();
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

            OfflinePlayer target = plugin.findOfflinePlayer(targetName);
            if (target == null || target.getName() == null) {
                event.getMessage().reply("❌ Игрок не найден.").queue();
                return;
            }

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
            embed.setFooter("Запрошено через Discord бота");
            event.getMessage().replyEmbeds(embed.build()).queue();
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
        boolean textXpEnabled, voiceXpEnabled, voiceExcludeAfk;
        List<String> textChannels;
        int textXpPerMessage, textCooldownSeconds, textMinLength, voiceXpPerMinute, voiceCheckInterval, voiceMinMembers;
        double voiceStreamMultiplier;
        int base;
        double exponent;
        Map<Integer, String> levelRoles;
    }
    private static class LevelData {
        int xp, level;
        long lastTextXp;
    }
}
