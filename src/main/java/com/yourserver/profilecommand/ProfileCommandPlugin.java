package com.yourserver.profilecommand;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.events.message.guild.GuildMessageReceivedEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.UUID;

public class ProfileCommandPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        DiscordSRV.api.getJda().addEventListener(new ProfileCommandListener());
        getLogger().info("ProfileCommand успешно активирован. Команда !profile готова!");
    }

    private static class ProfileCommandListener extends ListenerAdapter {
        @Override
        public void onGuildMessageReceived(@Nonnull GuildMessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;

            String message = event.getMessage().getContentRaw();
            if (message.equalsIgnoreCase("!profile") ||
                message.equalsIgnoreCase("!p") ||
                message.equalsIgnoreCase("!stats") ||
                message.equalsIgnoreCase("!myprofile")) {

                String discordId = event.getAuthor().getId();
                UUID playerUuid = DiscordSRV.getPlugin().getAccountLinkManager().getUuid(discordId);
                if (playerUuid == null) {
                    event.getChannel().sendMessage("❌ Ваш Discord не привязан к аккаунту Minecraft. Привяжите его на сервере.").queue();
                    return;
                }

                OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
                if (player.getName() == null) {
                    event.getChannel().sendMessage("❌ Не удалось найти игрока с таким UUID.").queue();
                    return;
                }

                String name = player.getName();
                String timePlayed = PlaceholderAPI.setPlaceholders(player, "%statistic_time_played%");
                String mobKills = PlaceholderAPI.setPlaceholders(player, "%statistic_mob_kills%");
                String blocksMined = PlaceholderAPI.setPlaceholders(player, "%statistic_mine_block%");

                EmbedBuilder embed = new EmbedBuilder();
                embed.setColor(new Color(0x5865F2)); // синий Discord
                embed.setAuthor(name, null, "https://minotar.net/avatar/" + name + "/128");
                embed.setThumbnail("https://minotar.net/avatar/" + name + "/128");
                embed.addField(":clock1: Наиграно", timePlayed, true);
                embed.addField(":sword: Убито мобов", mobKills, true);
                embed.addField(":pick: Добыто блоков", blocksMined, true);
                embed.setFooter("Запрошено через Discord бота");

                event.getChannel().sendMessageEmbeds(embed.build()).queue();
            }
        }
    }
}
