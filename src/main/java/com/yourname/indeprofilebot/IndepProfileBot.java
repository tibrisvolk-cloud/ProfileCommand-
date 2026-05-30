package com.yourname.indeprofilebot;

import me.clip.placeholderapi.PlaceholderAPI;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

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
    private String linkKickMessage;
    private String verifyKickMessage;
    private final Map<String, String> linkCodes = new ConcurrentHashMap<>();
    private final Map<UUID, String> linkedAccounts = new ConcurrentHashMap<>();
    private final Map<UUID, PendingVerification> pendingVerifications = new ConcurrentHashMap<>();
    private final Map<UUID, SessionInfo> sessions = new ConcurrentHashMap<>();
    private File linkedFile;
    private FileConfiguration linkedConfig;

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
        linkKickMessage = getConfig().getString("two-factor-auth.link-kick-message", "Привяжите Discord! Код: {CODE}");
        verifyKickMessage = getConfig().getString("two-factor-auth.verify-kick-message", "Подтвердите вход через Discord");

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

        String token = getConfig().getString("bot-token");
        if (token == null || token.isEmpty() || token.equals("ВСТАВЬТЕ_ВАШ_ТОКЕН_БОТА")) {
            getLogger().severe("Не указан токен бота в config.yml! Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new CommandListener(this), new ButtonListener(this))
                    .build();
            jda.awaitReady();
            getLogger().info("Discord бот запущен. Профили, топы и 2FA готовы.");
        } catch (Exception e) {
            getLogger().severe("Ошибка запуска Discord бота: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (jda != null) jda.shutdown();
    }

    // ==================== 2FA ====================
    @EventHandler
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!twoFactorEnabled) return;

        UUID uuid = event.getUniqueId();
        String ip = event.getAddress().getHostAddress();

        String discordId = linkedAccounts.get(uuid);
        if (discordId == null) {
            String code = String.format("%06d", new Random().nextInt(999999));
            linkCodes.put(code, uuid.toString());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    linkKickMessage.replace("{CODE}", code));
            return;
        }

        SessionInfo session = sessions.get(uuid);
        if (session != null && session.ip.equals(ip) && System.currentTimeMillis() < session.expiry) {
            return;
        }

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
                event.getMessage().reply("❌ Неверный или устаревший код. Зайдите на сервер для получения нового.").queue();
                return;
            }
            uuid = UUID.fromString(uuidStr);
        }
        String discordId = event.getAuthor().getId();
        linkedAccounts.put(uuid, discordId);
        linkedConfig.set(uuid.toString(), discordId);
        try { linkedConfig.save(linkedFile); } catch (IOException e) { getLogger().warning("Не удалось сохранить linked.yml"); }
        event.getMessage().reply("✅ Ваш Minecraft-аккаунт привязан к Discord! Можете заходить.").queue();
    }

    public void handleConfirmButton(ButtonInteractionEvent event, UUID uuid) {
        PendingVerification pending = pendingVerifications.remove(uuid);
        if (pending == null) {
            event.reply("⏳ Запрос уже недействителен. Перезайдите на сервер.").setEphemeral(true).queue();
            return;
        }
        sessions.put(uuid, new SessionInfo(pending.ip, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(sessionDurationMinutes)));
        event.editMessage("✅ Вход подтверждён. Можете зайти на сервер.").setComponents().queue();
    }

    // ==================== Общие хелперы ====================
    public String getFieldValue(OfflinePlayer player, String placeholder) {
        switch (placeholder) {
            case "_blank_": return "\u200B";
            case "_monster_kills_": {
                String[] hostileMobs = {"Zombie","Skeleton","Creeper","Spider","Enderman","Witch","Slime","Ghast","Blaze","Wither_Skeleton","Phantom","Drowned","Guardian","Elder_Guardian","Hoglin","Piglin","Zombified_Piglin","Magma_Cube","Silverfish","Endermite"};
                long total = 0;
                for (String mob : hostileMobs) {
                    String kills = PlaceholderAPI.setPlaceholders(player, "%statistic_kill_entity:" + mob + "%");
                    try { total += Long.parseLong(kills.replace(",", "")); } catch (NumberFormatException ignored) {}
                }
                return String.valueOf(total);
            }
            case "_average_life_": {
                try {
                    long timeMinutes = Long.parseLong(PlaceholderAPI.setPlaceholders(player, "%statistic_play_one_minute%"));
                    long deaths = Long.parseLong(PlaceholderAPI.setPlaceholders(player, "%statistic_deaths%"));
                    return (deaths > 0 && timeMinutes > 0) ? String.format("%.0f мин.", (double) timeMinutes / deaths) : "∞";
                } catch (Exception e) { return "—"; }
            }
            case "_distance_m_": {
                try {
                    String cmStr = PlaceholderAPI.setPlaceholders(player, "%statistic_walk_one_cm%");
                    double cm = Double.parseDouble(cmStr.replace(",", ""));
                    return cm >= 100000 ? String.format("%.1f км", cm / 100000.0) : String.format("%.0f м", cm / 100.0);
                } catch (Exception e) { return "—"; }
            }
            case "_efficiency_": {
                try {
                    long timeMinutes = Long.parseLong(PlaceholderAPI.setPlaceholders(player, "%statistic_play_one_minute%"));
                    long blocks = Long.parseLong(PlaceholderAPI.setPlaceholders(player, "%statistic_mine_block%"));
                    return (timeMinutes > 0 && blocks > 0) ? String.format("%.0f бл/ч", blocks / (timeMinutes / 60.0)) : "—";
                } catch (Exception e) { return "—"; }
            }
            default: return PlaceholderAPI.setPlaceholders(player, "%" + placeholder + "%");
        }
    }

    private List<Map.Entry<String, Long>> getTopForCategory(String placeholder) {
        Map<String, Long> scores = new HashMap<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() == null) continue;
            String raw = getFieldValue(p, placeholder);
            try { scores.put(p.getName(), Long.parseLong(raw.replace(",", ""))); } catch (NumberFormatException ignored) {}
        }
        return scores.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).collect(Collectors.toList());
    }

    private MessageEmbed buildTopEmbed(String categoryKey, int page) {
        TopCategory cat = topCategories.get(categoryKey);
        List<Map.Entry<String, Long>> list = getTopForCategory(cat.placeholder);
        int from = page * topPageSize, to = Math.min(from + topPageSize, list.size());
        EmbedBuilder eb = new EmbedBuilder().setColor(new Color(0x5865F2)).setTitle("🏆 Топ игроков: " + cat.label);
        if (list.isEmpty()) { eb.setDescription("Нет данных."); }
        else {
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                Map.Entry<String, Long> e = list.get(i);
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
            catButtons.add(Button.secondary("top_cat_" + key, entry.getValue().label).withDisabled(key.equals(currentCategory)));
        }
        Button prevBtn = Button.secondary("top_page_" + currentCategory + "_" + (currentPage - 1), "◀️ Назад").withDisabled(currentPage <= 0);
        Button nextBtn = Button.secondary("top_page_" + currentCategory + "_" + (currentPage + 1), "Вперед ▶️");
        return List.of(ActionRow.of(catButtons), ActionRow.of(prevBtn, nextBtn));
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
                MessageEmbed embed = plugin.buildTopEmbed(defaultCategory, 0);
                event.getChannel().sendMessageEmbeds(embed)
                        .setComponents(plugin.buildTopButtons(defaultCategory, 0))
                        .queue(message -> plugin.topStates.put(message.getId(), new TopState(defaultCategory, 0)));
                return;
            }

            if (lower.startsWith("!link ") && plugin.twoFactorEnabled) {
                String code = content.substring(6).trim();
                plugin.handleLinkCommand(event, code);
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
                    event.getMessage().reply("ℹ️ Используйте: `!profile <ник>` (например, `!profile Stev_Play`)").queue();
                }
                return;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            if (target.getName() == null) {
                event.getMessage().reply("❌ Игрок с ником `" + targetName + "` не найден.").queue();
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
                String value = plugin.getFieldValue(target, placeholder);
                embed.addField(emoji + " " + label, value, inline);
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
                UUID uuid = UUID.fromString(componentId.substring(12));
                plugin.handleConfirmButton(event, uuid);
                return;
            }

            String msgId = event.getMessageId();
            TopState state = plugin.topStates.get(msgId);
            if (state == null) {
                event.reply("⏳ Это сообщение устарело. Вызовите `!top` снова.").setEphemeral(true).queue();
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
            MessageEmbed newEmbed = plugin.buildTopEmbed(state.category, state.page);
            event.editMessageEmbeds(newEmbed).setComponents(plugin.buildTopButtons(state.category, state.page)).queue();
        }
    }

    // Вспомогательные классы
    private static class TopCategory { final String label; final String placeholder; TopCategory(String l, String p) { label = l; placeholder = p; } }
    private static class TopState { String category; int page; TopState(String c, int p) { category = c; page = p; } }
    private static class SessionInfo { String ip; long expiry; SessionInfo(String i, long e) { ip = i; expiry = e; } }
    private static class PendingVerification { String ip; long timestamp; PendingVerification(String i, long t) { ip = i; timestamp = t; } }
}
