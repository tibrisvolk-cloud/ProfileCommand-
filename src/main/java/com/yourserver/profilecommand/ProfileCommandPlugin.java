package com.yourserver.profilecommand;

import github.scarsz.discordsrv.DiscordSRV;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;

public class ProfileCommandPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        try {
            // Получаем JDA напрямую через API DiscordSRV
            Object jda = DiscordSRV.getPlugin().getJda();
            if (jda == null) {
                getLogger().severe("JDA от DiscordSRV не получен. Плагин не будет работать.");
                return;
            }

            // Регистрируем слушатель
            jda.getClass().getMethod("addEventListener", Object[].class)
                    .invoke(jda, new Object[]{new ProfileCommandListener()});

            getLogger().info("ProfileCommand успешно активирован. Команда !profile <ник> готова!");
        } catch (Exception e) {
            getLogger().severe("Ошибка при инициализации: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static class ProfileCommandListener {

        public void onGuildMessageReceived(Object event) {
            try {
                Object message = event.getClass().getMethod("getMessage").invoke(event);
                Object author = message.getClass().getMethod("getAuthor").invoke(message);

                if ((boolean) author.getClass().getMethod("isBot").invoke(author)) return;

                String content = (String) message.getClass().getMethod("getContentRaw").invoke(message);
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
                        sendMessage(event, "ℹ️ Используйте: `!profile <ник>` (например, `!profile Stev_Play`)");
                    }
                    return;
                }

                OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                if (target.getName() == null) {
                    sendMessage(event, "❌ Игрок с ником `" + targetName + "` не найден.");
                    return;
                }

                String timePlayed = PlaceholderAPI.setPlaceholders(target, "%statistic_time_played%");
                String mobKills = PlaceholderAPI.setPlaceholders(target, "%statistic_mob_kills%");
                String blocksMined = PlaceholderAPI.setPlaceholders(target, "%statistic_mine_block%");

                sendEmbed(event, target.getName(), timePlayed, mobKills, blocksMined);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void sendMessage(Object event, String text) {
            try {
                Object channel = event.getClass().getMethod("getChannel").invoke(event);
                channel.getClass().getMethod("sendMessage", CharSequence.class).invoke(channel, text);
            } catch (Exception ignored) {}
        }

        private void sendEmbed(Object event, String playerName, String time, String kills, String blocks) {
            try {
                Object channel = event.getClass().getMethod("getChannel").invoke(event);

                Class<?> embedBuilderClass = Class.forName("net.dv8tion.jda.api.EmbedBuilder");
                Object embedBuilder = embedBuilderClass.getDeclaredConstructor().newInstance();

                Class<?> colorClass = Class.forName("java.awt.Color");
                Object blurple = colorClass.getDeclaredConstructor(int.class).newInstance(0x5865F2);
                embedBuilderClass.getMethod("setColor", colorClass).invoke(embedBuilder, blurple);

                String avatarUrl = "https://minotar.net/avatar/" + playerName + "/128";
                embedBuilderClass.getMethod("setAuthor", String.class, String.class, String.class)
                        .invoke(embedBuilder, playerName, null, avatarUrl);
                embedBuilderClass.getMethod("setThumbnail", String.class).invoke(embedBuilder, avatarUrl);

                embedBuilderClass.getMethod("addField", String.class, String.class, boolean.class)
                        .invoke(embedBuilder, ":clock1: Наиграно", time, true);
                embedBuilderClass.getMethod("addField", String.class, String.class, boolean.class)
                        .invoke(embedBuilder, ":sword: Убито мобов", kills, true);
                embedBuilderClass.getMethod("addField", String.class, String.class, boolean.class)
                        .invoke(embedBuilder, ":pick: Добыто блоков", blocks, true);

                embedBuilderClass.getMethod("setFooter", String.class).invoke(embedBuilder, "Запрошено через Discord бота");

                Object embed = embedBuilderClass.getMethod("build").invoke(embedBuilder);
                channel.getClass().getMethod("sendMessageEmbeds", embed.getClass()).invoke(channel, embed);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
    }
}
