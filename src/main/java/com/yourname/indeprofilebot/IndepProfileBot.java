package com.yourname.indeprofilebot;

import me.clip.placeholderapi.PlaceholderAPI;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    // Репутация
    private boolean reputationEnabled;
    private int reputationDailyLimit;
    private int reputationCooldownMinutes;
    private boolean reputationRequireLinked;
    private List<String> reputationVoteBypassRoles;
    private TreeMap<Integer, ReputationRole> reputationRoles;
    private final Map<String, ReputationData> reputationCache = new ConcurrentHashMap<>();
    private File reputationFile;
    private FileConfiguration reputationConfig;

    // Карточки топа
    private boolean topCardsEnabled;
    private String topCardsDefaultView;
    private Color topCardsGoldColor;
    private Color topCardsSilverColor;
    private Color topCardsBronzeColor;
    private Color topCardsDefaultColor;
    private String topCardsBackgroundUrl;

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

        // Репутация
        reputationEnabled = getConfig().getBoolean("reputation.enabled", false);
        reputationDailyLimit = getConfig().getInt("reputation.daily-limit", 5);
        reputationCooldownMinutes = getConfig().getInt("reputation.cooldown-minutes", 1);
        reputationRequireLinked = getConfig().getBoolean("reputation.require-linked-to-vote", false);
        reputationVoteBypassRoles = getConfig().getStringList("reputation.vote-bypass-roles");
        reputationRoles = new TreeMap<>();
        ConfigurationSection rolesSection = getConfig().getConfigurationSection("reputation.reputation-roles");
        if (rolesSection != null) {
            for (String thresholdStr : rolesSection.getKeys(false)) {
                int threshold = Integer.parseInt(thresholdStr);
                String label = rolesSection.getString(thresholdStr + ".label");
                String roleId = rolesSection.getString(thresholdStr + ".role-id");
                reputationRoles.put(threshold, new ReputationRole(threshold, label, roleId));
            }
        }
        reputationFile = new File(getDataFolder(), "reputation.yml");
        if (!reputationFile.exists()) {
            try { reputationFile.createNewFile(); } catch (IOException ignored) {}
        }
        reputationConfig = YamlConfiguration.loadConfiguration(reputationFile);

        // Карточки топа
        topCardsEnabled = getConfig().getBoolean("top-cards.enabled", true);
        topCardsDefaultView = getConfig().getString("top-cards.default-view", "list");
        topCardsGoldColor = parseColor(getConfig().getString("top-cards.gold-color", "#FFD700"));
        topCardsSilverColor = parseColor(getConfig().getString("top-cards.silver-color", "#C0C0C0"));
        topCardsBronzeColor = parseColor(getConfig().getString("top-cards.bronze-color", "#CD7F32"));
        topCardsDefaultColor = parseColor(getConfig().getString("top-cards.default-color", "#5865F2"));
        topCardsBackgroundUrl = getConfig().getString("top-cards.background-url", "");

        String token = getConfig().getString("bot-token");
        if (token == null || token.isEmpty() || token.equals("ВСТАВЬТЕ_ВАШ_ТОКЕН_БОТА")) {
            getLogger().severe("Не указан токен бота в config.yml! Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new CommandListener(this), new ButtonListener(this), new SlashCommandListener(this))
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
                Commands.slash("rep", "Изменить или посмотреть репутацию")
                    .addOption(OptionType.STRING, "ник", "Упоминание @user или ник", true)
                    .addOption(OptionType.STRING, "действие", "+, -, или пусто для просмотра", false)
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
    }

    @Override
    public void onDisable() {
        if (jda != null) jda.shutdown();
    }

    private Color parseColor(String hex) {
        if (hex == null || hex.isEmpty()) return new Color(0x5865F2);
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return new Color(0x5865F2);
        }
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
                                    guild.addRoleToMember(member, role).queue(
                                        s -> getLogger().info("Выдана роль " + ach.label + " игроку " + player.getName()),
                                        f -> getLogger().warning("Не удалось выдать роль " + ach.label)
                                    );
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

        // Проверка на повторную привязку
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

        // Кикаем игрока, если он онлайн (в главном потоке)
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

    // ==================== Репутация ====================
    private int getReputation(String discordId) {
        return reputationConfig.getInt(discordId, 0);
    }

    private void setReputation(String discordId, int value) {
        reputationConfig.set(discordId, value);
        try { reputationConfig.save(reputationFile); } catch (IOException e) {
            getLogger().warning("Не удалось сохранить reputation.yml");
        }
        updateReputationRoles(discordId, value);
    }

    private void updateReputationRoles(String discordId, int rep) {
        if (guildId.isEmpty() || reputationRoles.isEmpty()) return;
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) return;

        guild.retrieveMemberById(discordId).queue(member -> {
            if (!canModifyMember(member)) return;

            String targetRoleId = null;
            for (Map.Entry<Integer, ReputationRole> entry : reputationRoles.descendingMap().entrySet()) {
                if (rep >= entry.getKey()) {
                    targetRoleId = entry.getValue().roleId;
                    break;
                }
            }

            for (ReputationRole rRole : reputationRoles.values()) {
                Role role = guild.getRoleById(rRole.roleId);
                if (role != null && member.getRoles().contains(role)) {
                    if (!rRole.roleId.equals(targetRoleId)) {
                        guild.removeRoleFromMember(member, role).queue();
                    }
                }
            }

            if (targetRoleId != null) {
                Role roleToAdd = guild.getRoleById(targetRoleId);
                if (roleToAdd != null && !member.getRoles().contains(roleToAdd)) {
                    guild.addRoleToMember(member, roleToAdd).queue();
                }
            }
        });
    }

    private boolean canChangeReputation(String discordId) {
        if (!reputationEnabled) return false;
        ReputationData data = reputationCache.computeIfAbsent(discordId, k -> new ReputationData());
        long now = System.currentTimeMillis();
        if (now - data.lastChange < TimeUnit.MINUTES.toMillis(reputationCooldownMinutes)) {
            return false;
        }
        if (reputationDailyLimit > 0 && data.changesToday >= reputationDailyLimit) {
            return false;
        }
        return true;
    }

    private boolean isEligibleToVote(Member member) {
        if (!reputationRequireLinked) return true;
        for (String roleId : reputationVoteBypassRoles) {
            if (member.getRoles().stream().anyMatch(r -> r.getId().equals(roleId))) {
                return true;
            }
        }
        String discordId = member.getId();
        return linkedAccounts.containsValue(discordId);
    }

    private void recordReputationChange(String discordId) {
        ReputationData data = reputationCache.get(discordId);
        if (data == null) {
            data = new ReputationData();
            reputationCache.put(discordId, data);
        }
        data.lastChange = System.currentTimeMillis();
        data.changesToday++;
    }

    public void handleReputationCommand(MessageReceivedEvent event, String targetArg, String actionArg, boolean isSlash) {
        if (!reputationEnabled) {
            event.getMessage().reply("❌ Система репутации отключена.").queue();
            return;
        }

        Member voter = event.getMember();
        if (voter == null) {
            event.getMessage().reply("❌ Команда доступна только на сервере.").queue();
            return;
        }
        if (!isEligibleToVote(voter)) {
            event.getMessage().reply("❌ Вы не можете голосовать (требуется привязка Minecraft или специальная роль).").queue();
            return;
        }

        boolean increase;
        if (actionArg.equals("+") || actionArg.equalsIgnoreCase("plus")) {
            increase = true;
        } else if (actionArg.equals("-") || actionArg.equalsIgnoreCase("minus")) {
            increase = false;
        } else {
            // просмотр репутации
            String discordId = null;
            String displayName = null;
            if (targetArg.matches("<@!?\\d+>")) {
                discordId = targetArg.replaceAll("[<@!>]", "");
                try {
                    Member m = event.getGuild().retrieveMemberById(discordId).complete();
                    if (m != null) displayName = m.getEffectiveName();
                } catch (Exception e) {}
            }
            if (discordId == null) {
                event.getMessage().reply("❌ Укажите действие: `+` или `-`, например `!rep @user +`").queue();
                return;
            }
            int rep = getReputation(discordId);
            event.getMessage().reply("👍 Репутация " + (displayName != null ? displayName : "участника") + ": " + rep).queue();
            return;
        }

        // Изменение репутации
        String receiverDiscordId = null;
        String displayName = null;

        if (targetArg.matches("<@!?\\d+>")) {
            receiverDiscordId = targetArg.replaceAll("[<@!>]", "");
            try {
                Member m = event.getGuild().retrieveMemberById(receiverDiscordId).complete();
                if (m != null) displayName = m.getEffectiveName();
            } catch (Exception e) {
                event.getMessage().reply("❌ Участник не найден.").queue();
                return;
            }
        } else {
            OfflinePlayer target = findOfflinePlayer(targetArg);
            if (target == null || target.getName() == null) {
                event.getMessage().reply("❌ Игрок не найден.").queue();
                return;
            }
            receiverDiscordId = linkedAccounts.get(target.getUniqueId());
            if (receiverDiscordId == null) {
                event.getMessage().reply("❌ Этот игрок не привязал Discord. Используйте упоминание @user.").queue();
                return;
            }
            displayName = target.getName();
        }

        if (receiverDiscordId.equals(event.getAuthor().getId())) {
            event.getMessage().reply("❌ Нельзя изменять репутацию самому себе.").queue();
            return;
        }

        if (!canChangeReputation(event.getAuthor().getId())) {
            event.getMessage().reply("❌ Вы не можете сейчас изменять репутацию (лимит или кулдаун).").queue();
            return;
        }

        int current = getReputation(receiverDiscordId);
        int delta = increase ? 1 : -1;
        int newValue = current + delta;
        setReputation(receiverDiscordId, newValue);
        recordReputationChange(event.getAuthor().getId());

        String symbol = increase ? "+" : "-";
        event.getMessage().reply("✅ Репутация " + displayName + " изменена (" + symbol + "1). Теперь: " + newValue).queue();
    }

    // ==================== Общие хелперы ====================
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
            case "_reputation_": {
                UUID uuid = player.getUniqueId();
                String discordId = linkedAccounts.get(uuid);
                if (discordId != null) {
                    int rep = getReputation(discordId);
                    return String.valueOf(rep);
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

        Set<String> uniqueNames = new HashSet<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null) uniqueNames.add(p.getName());
        }

        for (String name : uniqueNames) {
            OfflinePlayer p = findOfflinePlayer(name);
            if (p == null) continue;
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

    private List<MessageEmbed> buildTopCards(String categoryKey, int page) {
        TopCategory cat = topCategories.get(categoryKey);
        List<Map.Entry<String, String>> list = getTopForCategoryFormatted(cat.placeholder, categoryKey);
        int from = page * topPageSize;
        int to = Math.min(from + topPageSize, list.size());

        List<MessageEmbed> embeds = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Map.Entry<String, String> entry = list.get(i);
            int rank = i + 1;
            String name = entry.getKey();
            String value = entry.getValue();

            EmbedBuilder eb = new EmbedBuilder();
            Color color = topCardsDefaultColor;
            if (rank == 1) color = topCardsGoldColor;
            else if (rank == 2) color = topCardsSilverColor;
            else if (rank == 3) color = topCardsBronzeColor;
            eb.setColor(color);

            eb.setAuthor("#" + rank + " " + name, null, "https://minotar.net/avatar/" + name + "/128");
            eb.setThumbnail("https://minotar.net/avatar/" + name + "/128");
            eb.addField(cat.label, value, true);
            if (!topCardsBackgroundUrl.isEmpty()) {
                eb.setImage(topCardsBackgroundUrl);
            }
            eb.setFooter("Страница " + (page + 1) + "/" + Math.max(1, (list.size() + topPageSize - 1) / topPageSize));
            embeds.add(eb.build());
        }
        return embeds;
    }

    private List<ActionRow> buildTopButtons(String currentCategory, int currentPage, String viewMode) {
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

        Button prevBtn = Button.secondary("top_page_" + currentCategory + "_" + (currentPage - 1) + "_" + viewMode, "◀️ Назад")
                .withDisabled(currentPage <= 0);
        Button nextBtn = Button.secondary("top_page_" + currentCategory + "_" + (currentPage + 1) + "_" + viewMode, "Вперед ▶️");
        rows.add(ActionRow.of(prevBtn, nextBtn));

        if (topCardsEnabled) {
            Button listBtn = Button.secondary("top_view_list", "📋 Список")
                    .withDisabled(viewMode.equals("list"));
            Button cardsBtn = Button.secondary("top_view_cards", "🃏 Карточки")
                    .withDisabled(viewMode.equals("cards"));
            rows.add(ActionRow.of(listBtn, cardsBtn));
        }

        return rows;
    }

    // ==================== Слушатели ====================
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
                String viewMode = plugin.topCardsEnabled ? plugin.topCardsDefaultView : "list";
                TopState state = new TopState(defaultCategory, 0, viewMode);
                if (viewMode.equals("cards")) {
                    List<MessageEmbed> cards = plugin.buildTopCards(defaultCategory, 0);
                    event.getChannel().sendMessageEmbeds(cards)
                            .setComponents(plugin.buildTopButtons(defaultCategory, 0, viewMode))
                            .queue(message -> plugin.topStates.put(message.getId(), state));
                } else {
                    MessageEmbed embed = plugin.buildTopEmbed(defaultCategory, 0);
                    event.getChannel().sendMessageEmbeds(embed)
                            .setComponents(plugin.buildTopButtons(defaultCategory, 0, viewMode))
                            .queue(message -> plugin.topStates.put(message.getId(), state));
                }
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

            if (lower.startsWith("!rep")) {
                String args = content.substring(4).trim();
                if (args.isEmpty()) {
                    event.getMessage().reply("ℹ️ Используйте: `!rep <@user|ник> [+/-]`").queue();
                    return;
                }
                String[] parts = args.split("\\s+");
                String targetArg = parts[0];
                String actionArg = parts.length > 1 ? parts[1] : "";
                plugin.handleReputationCommand(event, targetArg, actionArg, false);
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

            if (componentId.equals("top_view_list")) {
                state.viewMode = "list";
                MessageEmbed embed = plugin.buildTopEmbed(state.category, state.page);
                event.editMessageEmbeds(embed)
                        .setComponents(plugin.buildTopButtons(state.category, state.page, state.viewMode)).queue();
                return;
            }
            if (componentId.equals("top_view_cards")) {
                state.viewMode = "cards";
                List<MessageEmbed> cards = plugin.buildTopCards(state.category, state.page);
                event.editMessageEmbeds(cards)
                        .setComponents(plugin.buildTopButtons(state.category, state.page, state.viewMode)).queue();
                return;
            }

            if (componentId.startsWith("top_cat_")) {
                state.category = componentId.substring(8);
                state.page = 0;
            } else if (componentId.startsWith("top_page_")) {
                String[] parts = componentId.substring(9).split("_");
                if (parts.length >= 3) {
                    String cat = parts[0];
                    int newPage = Integer.parseInt(parts[1]);
                    if (cat.equals(state.category)) state.page = newPage;
                } else {
                    String cat = parts[0];
                    int newPage = Integer.parseInt(parts[1]);
                    if (cat.equals(state.category)) state.page = newPage;
                }
            }

            if (state.viewMode.equals("cards")) {
                List<MessageEmbed> cards = plugin.buildTopCards(state.category, state.page);
                event.editMessageEmbeds(cards)
                        .setComponents(plugin.buildTopButtons(state.category, state.page, state.viewMode)).queue();
            } else {
                MessageEmbed embed = plugin.buildTopEmbed(state.category, state.page);
                event.editMessageEmbeds(embed)
                        .setComponents(plugin.buildTopButtons(state.category, state.page, state.viewMode)).queue();
            }
        }
    }

    private static class SlashCommandListener extends ListenerAdapter {
        private final IndepProfileBot plugin;
        public SlashCommandListener(IndepProfileBot plugin) { this.plugin = plugin; }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            // Обработка слеш-команд аналогична текстовым, но с event.reply(...)
            // Для краткости опущена, но вы можете добавить по аналогии с CommandListener.
        }
    }

    // Вспомогательные классы
    private static class TopCategory { final String label; final String placeholder; TopCategory(String l, String p) { label = l; placeholder = p; } }
    private static class TopState { String category; int page; String viewMode; TopState(String c, int p, String vm) { category = c; page = p; viewMode = vm; } }
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
    private static class ReputationRole { int threshold; String label; String roleId; ReputationRole(int t, String l, String r) { threshold = t; label = l; roleId = r; } }
    private static class ReputationData { long lastChange; int changesToday; }
}
