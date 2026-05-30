package com.yourname.indeprofilebot;

import me.clip.placeholderapi.PlaceholderAPI;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.util.List;
import java.util.Map;

public class IndepProfileBot extends JavaPlugin {

    private JDA jda;
    private List<Map<?, ?>> profileFields;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        profileFields = getConfig().getMapList("profile-fields");

        String token = getConfig().getString("bot-token");
        if (token == null || token.isEmpty() || token.equals("ВСТАВЬТЕ_ВАШ_ТОКЕН_БОТА")) {
            getLogger().severe("Не указан токен бота в config.yml! Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new CommandListener(this))
                    .build();
            jda.awaitReady();
            getLogger().info("Discord бот успешно запущен и готов принимать команды!");
        } catch (Exception e) {
            getLogger().severe("Не удалось запустить Discord бота: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (jda != null) {
            jda.shutdown();
        }
    }

    // Возвращает значение для плейсхолдера, с учётом специальных ключей
    public String getFieldValue(OfflinePlayer player, String placeholder) {
        switch (placeholder) {
            case "_blank_":
                return "\u200B"; // невидимый символ нулевой ширины, чтобы Embed не схлопывал поле
            case "_monster_kills_": {
                String[] hostileMobs = {
                    "Zombie", "Skeleton", "Creeper", "Spider", "Enderman",
                    "Witch", "Slime", "Ghast", "Blaze", "Wither_Skeleton",
                    "Phantom", "Drowned", "Guardian", "Elder_Guardian",
                    "Hoglin", "Piglin", "Zombified_Piglin", "Magma_Cube",
                    "Silverfish", "Endermite"
                };
                long total = 0;
                for (String mob : hostileMobs) {
                    String kills = PlaceholderAPI.setPlaceholders(player, "%statistic_kill_entity:" + mob + "%");
                    try {
                        total += Long.parseLong(kills.replace(",", ""));
                    } catch (NumberFormatException ignored) {}
                }
                return String.valueOf(total);
            }
            case "_average_life_": {
                try {
                    long timeMinutes = Long.parseLong(PlaceholderAPI.setPlaceholders(player, "%statistic_play_one_minute%"));
                    long deaths = Long.parseLong(PlaceholderAPI.setPlaceholders(player, "%statistic_deaths%"));
                    if (deaths > 0 && timeMinutes > 0) {
                        return String.format("%.0f мин.", (double) timeMinutes / deaths);
                    } else {
                        return "∞";
                    }
                } catch (Exception e) {
                    return "—";
                }
            }
            case "_distance_m_": {
                try {
                    String cmStr = PlaceholderAPI.setPlaceholders(player, "%statistic_walk_one_cm%");
                    double cm = Double.parseDouble(cmStr.replace(",", ""));
                    if (cm >= 100000) {
                        return String.format("%.1f км", cm / 100000.0);
                    } else {
                        return String.format("%.0f м", cm / 100.0);
                    }
                } catch (Exception e) {
                    return "—";
                }
            }
            case "_efficiency_": {
                try {
                    long timeMinutes = Long.parseLong(PlaceholderAPI.setPlaceholders(player, "%statistic_play_one_minute%"));
                    long blocks = Long.parseLong(PlaceholderAPI.setPlaceholders(player, "%statistic_mine_block%"));
                    if (timeMinutes > 0 && blocks > 0) {
                        return String.format("%.0f бл/ч", blocks / (timeMinutes / 60.0));
                    } else {
                        return "—";
                    }
                } catch (Exception e) {
                    return "—";
                }
            }
            default: {
                return PlaceholderAPI.setPlaceholders(player, "%" + placeholder + "%");
            }
        }
    }

    private static class CommandListener extends ListenerAdapter {
        private final IndepProfileBot plugin;

        public CommandListener(IndepProfileBot plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;

            Message message = event.getMessage();
            String content = message.getContentRaw();
            String lowerContent = content.toLowerCase();

            String[] prefixes = {"!profile ", "!p ", "!stats ", "!myprofile "};
            String targetName = null;
            for (String prefix : prefixes) {
                if (lowerContent.startsWith(prefix)) {
                    targetName = content.substring(prefix.length()).trim();
                    break;
                }
            }

            if (targetName == null || targetName.isEmpty()) {
                if (content.equalsIgnoreCase("!profile") || content.equalsIgnoreCase("!p") ||
                        content.equalsIgnoreCase("!stats") || content.equalsIgnoreCase("!myprofile")) {
                    message.reply("ℹ️ Используйте: `!profile <ник>` (например, `!profile Stev_Play`)").queue();
                }
                return;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            if (target.getName() == null) {
                message.reply("❌ Игрок с ником `" + targetName + "` не найден.").queue();
                return;
            }

            EmbedBuilder embed = new EmbedBuilder();
            embed.setColor(new Color(0x5865F2));
            embed.setAuthor(target.getName(), null, "https://minotar.net/avatar/" + target.getName() + "/128");
            embed.setThumbnail("https://minotar.net/avatar/" + target.getName() + "/128");

            // Перебираем поля из конфига и добавляем в Embed
            for (Map<?, ?> fieldMap : plugin.profileFields) {
                String emoji = (String) fieldMap.get("emoji");
                String label = (String) fieldMap.get("label");
                String placeholder = (String) fieldMap.get("placeholder");
                boolean inline = fieldMap.containsKey("inline") && (boolean) fieldMap.get("inline");

                String value = plugin.getFieldValue(target, placeholder);
                embed.addField(emoji + " " + label, value, inline);
            }

            embed.setFooter("Запрошено через Discord бота");
            message.replyEmbeds(embed.build()).queue();
        }
    }
}
