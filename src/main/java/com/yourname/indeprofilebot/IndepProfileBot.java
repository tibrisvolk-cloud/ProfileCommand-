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

public class IndepProfileBot extends JavaPlugin {

    private JDA jda;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String token = getConfig().getString("bot-token");
        if (token == null || token.isEmpty() || token.equals("ВСТАВЬТЕ_ВАШ_ТОКЕН_БОТА")) {
            getLogger().severe("Не указан токен бота в config.yml! Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new CommandListener())
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

    private static class CommandListener extends ListenerAdapter {
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

            String timePlayed = PlaceholderAPI.setPlaceholders(target, "%statistic_time_played%");
            String mobKills = PlaceholderAPI.setPlaceholders(target, "%statistic_mob_kills%");
            String blocksMined = PlaceholderAPI.setPlaceholders(target, "%statistic_mine_block%");

            EmbedBuilder embed = new EmbedBuilder();
            embed.setColor(new Color(0x5865F2));
            embed.setAuthor(target.getName(), null, "https://minotar.net/avatar/" + target.getName() + "/128");
            embed.setThumbnail("https://minotar.net/avatar/" + target.getName() + "/128");
            embed.addField(":clock1: Наиграно", timePlayed, true);
            embed.addField(":sword: Убито мобов", mobKills, true);
            embed.addField(":pick: Добыто блоков", blocksMined, true);
            embed.setFooter("Запрошено через Discord бота");

            message.replyEmbeds(embed.build()).queue();
        }
    }
}
