package com.yourname.indeprofilebot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ClanWarsCore extends JavaPlugin implements Listener, CommandExecutor {

    private String botToken;
    private String guildId;

    private JDA jda;
    private final Map<String, String> linkCodes = new ConcurrentHashMap<>();
    private final Map<UUID, String> linkedAccounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PendingVerification> pendingVerifications = new ConcurrentHashMap<>();
    private File linkedFile;
    private FileConfiguration linkedConfig;

    public final Map<UUID, ClanPlayer> players = new ConcurrentHashMap<>();
    public final Map<String, Clan> clans = new ConcurrentHashMap<>();
    private final Map<UUID, List<ItemStack>> soulboundItems = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingInvites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> radarCooldowns = new ConcurrentHashMap<>();

    private NamespacedKey dirtyKey, guildItemKey, nexusKey, pawboxKey, scrollInfernoKey, scrollPlagueKey, c4Key;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        
        botToken = getConfig().getString("discord.bot-token");
        guildId = getConfig().getString("discord.guild-id");

        dirtyKey = new NamespacedKey(this, "dirty_point");
        guildItemKey = new NamespacedKey(this, "guild_item");
        nexusKey = new NamespacedKey(this, "nexus_block");
        pawboxKey = new NamespacedKey(this, "pawbox");
        scrollInfernoKey = new NamespacedKey(this, "scroll_inferno");
        scrollPlagueKey = new NamespacedKey(this, "scroll_plague");
        c4Key = new NamespacedKey(this, "c4_charge");

        loadLinks();
        loadGameData();

        if (botToken != null && !botToken.isEmpty() && !botToken.equals("ВСТАВЬТЕ_ТОКЕН_БОТА")) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    jda = JDABuilder.createDefault(botToken).enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES)
                            .addEventListeners(new DiscordListener()).build();
                    jda.awaitReady();
                } catch (Exception ignored) {}
            });
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("clan").setExecutor(this);

        new BukkitRunnable() {
            @Override public void run() { for (Player p : Bukkit.getOnlinePlayers()) applyGuildPassives(p); }
        }.runTaskTimer(this, 20L, 20L);

        new BukkitRunnable() {
            @Override public void run() { saveData(); }
        }.runTaskTimer(this, 1200L, 1200L);

        getLogger().info("ClanWars Season 2: Запущен!");
    }

    @Override
    public void onDisable() {
        saveData();
        if (jda != null) jda.shutdown();
    }

    // ==========================================
    //           СИСТЕМА ЧАТА И ПРЕФИКСОВ
    // ==========================================
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        ClanPlayer cp = getClanPlayer(p.getUniqueId());
        Clan clan = cp.getClanId() != null ? clans.get(cp.getClanId()) : null;

        if (cp.isClanChatEnabled()) {
            event.setCancelled(true);
            if (clan == null) {
                cp.setClanChatEnabled(false);
                p.sendMessage("§cВы не в клане! Чат переключен на глобальный.");
                return;
            }
            String msg = "§b[Клан] §f" + p.getName() + " §8» §7" + event.getMessage();
            for (UUID memId : clan.getMembers()) {
                Player mem = Bukkit.getPlayer(memId);
                if (mem != null) mem.sendMessage(msg);
            }
            
            if (jda != null && clan.getDiscordTextChannelId() != null) {
                net.dv8tion.jda.api.entities.channel.middleman.MessageChannel channel = jda.getTextChannelById(clan.getDiscordTextChannelId());
                if (channel != null) channel.sendMessage("**" + p.getName() + "**: " + event.getMessage()).queue();
            }
        } else {
            if (clan != null) {
                event.setFormat("§8[§e" + clan.getId() + " §8| §6" + clan.getRank().name() + "§8] §f%1$s §8» §7%2$s");
            } else {
                event.setFormat("§8[§7Без Клана§8] §f%1$s §8» §7%2$s");
            }
        }
    }

    // ==========================================
    //           МЕНЮ УЛУЧШЕНИЙ (GUI)
    // ==========================================
    private void openUpgradeMenu(Player player) {
        Clan clan = getClanPlayer(player.getUniqueId()).getClanId() != null ? clans.get(getClanPlayer(player.getUniqueId()).getClanId()) : null;
        if (clan == null) { player.sendMessage("§cВы не в клане!"); return; }

        Inventory inv = Bukkit.createInventory(null, 27, "§8Улучшения Клана");
        inv.setItem(11, getCustomItem(Material.GOLDEN_APPLE, "§c❤ Улучшить Здоровье", null, "§7Цена: §e15,000 Очков", "§7Дает +1 сердце всему клану."));
        
        if (clan.getGuildType() == GuildType.MAGE) {
            inv.setItem(15, getCustomItem(Material.PAPER, "§5Свитки Магии", null, "§7Цена: §e5,000 Очков", "§7Выдает Свиток Инферно и Чумы"));
        } else if (clan.getGuildType() == GuildType.ENGINEER) {
            inv.setItem(15, getCustomItem(Material.TNT, "§4Рейдовый заряд (C4)", null, "§7Цена: §e25,000 Очков", "§7Пробивает базу врагов"));
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals("§8Улучшения Клана")) {
            event.setCancelled(true);
            Player p = (Player) event.getWhoClicked();
            Clan clan = clans.get(getClanPlayer(p.getUniqueId()).getClanId());
            if (clan == null) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            if (clicked.getItemMeta().getDisplayName().contains("Здоровье")) {
                if (clan.getBankPoints() >= 15000) {
                    clan.addBankPoints(-15000);
                    for (UUID uid : clan.getMembers()) {
                        ClanPlayer cp = getClanPlayer(uid); cp.setMaxHealthLevel(cp.getMaxHealthLevel() + 1);
                        Player mem = Bukkit.getPlayer(uid); if (mem != null) applyGuildPassives(mem);
                    }
                    p.sendMessage("§aЗдоровье клана увеличено!");
                } else p.sendMessage("§cНедостаточно Очков!");
            }
            else if (clicked.getItemMeta().getDisplayName().contains("Свитки") && clan.getBankPoints() >= 5000) {
                clan.addBankPoints(-5000);
                p.getInventory().addItem(getCustomItem(Material.PAPER, "§cСвиток Инферно", scrollInfernoKey));
                p.getInventory().addItem(getCustomItem(Material.PAPER, "§2Свиток Чумы", scrollPlagueKey));
                p.sendMessage("§aСвитки куплены!");
            }
            else if (clicked.getItemMeta().getDisplayName().contains("C4") && clan.getBankPoints() >= 25000) {
                clan.addBankPoints(-25000);
                p.getInventory().addItem(getCustomItem(Material.TNT, "§4Рейдовый Заряд (C4)", c4Key));
                p.sendMessage("§aЗаряд C4 приобретен!");
            }
            p.closeInventory();
        }
    }

    // ==========================================
    //      КАСТОМНЫЕ ПРЕДМЕТЫ И МЕХАНИКИ
    // ==========================================
    private ItemStack getCustomItem(Material mat, String name, NamespacedKey key, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (key != null) meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        if (lore != null && lore.length > 0) meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onCustomItemUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer(); ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        if (item.getItemMeta().getPersistentDataContainer().has(scrollInfernoKey, PersistentDataType.BYTE)) {
            event.setCancelled(true); item.setAmount(item.getAmount() - 1);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GHAST_SHOOT, 1f, 1f);
            for (Entity e : p.getNearbyEntities(7, 7, 7)) if (e instanceof LivingEntity && e != p) e.setFireTicks(200);
            p.sendMessage("§cВы активировали Инферно!");
        }
        else if (item.getItemMeta().getPersistentDataContainer().has(scrollPlagueKey, PersistentDataType.BYTE)) {
            event.setCancelled(true); item.setAmount(item.getAmount() - 1);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITCH_THROW, 1f, 1f);
            for (Entity e : p.getNearbyEntities(7, 7, 7)) if (e instanceof LivingEntity && e != p) ((LivingEntity) e).addPotionEffect(new PotionEffect(PotionEffectType.POISON, 200, 1));
            p.sendMessage("§2Вы выпустили Чуму!");
        }
        else if (item.getType() == Material.COMPASS && item.getItemMeta().getDisplayName().contains("Трекер Крови")) {
            Player richest = null; int maxPts = 0;
            for (Player t : Bukkit.getOnlinePlayers()) {
                if (t.equals(p)) continue;
                int pts = countDirtyPoints(t);
                if (pts > maxPts) { maxPts = pts; richest = t; }
            }
            if (richest != null) { p.setCompassTarget(richest.getLocation()); p.sendMessage("§cЦель: " + richest.getName()); }
        }
        else if (item.getItemMeta().getPersistentDataContainer().has(pawboxKey, PersistentDataType.BYTE)) {
            event.setCancelled(true); item.setAmount(item.getAmount() - 1); openPawBox(p);
        }
    }

    private void openPawBox(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        int roll = new Random().nextInt(100);
        
        if (roll < 20) { 
            ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
            ItemMeta sm = sword.getItemMeta(); sm.setDisplayName("§6Клинок Падшего Короля"); sword.setItemMeta(sm);
            markAsGuildItem(sword); 
            player.getInventory().addItem(sword);
            Bukkit.broadcastMessage("§e§l[PawBox] §fИгрок §6" + player.getName() + " §fвыбил Легендарный Артефакт!");
        } else if (roll < 60) {
            player.getInventory().addItem(getDirtyPointItem(32));
            player.sendMessage("§aВыбили кучу Грязных Очков! Бегите в Банк!");
        } else {
            player.getInventory().addItem(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1));
            player.getInventory().addItem(new ItemStack(Material.ANCIENT_DEBRIS, 2));
        }
    }

    // ==========================================
    //           ГЛАВНЫЕ КОМАНДЫ (/CLAN)
    // ==========================================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        ClanPlayer cp = getClanPlayer(p.getUniqueId());

        if (args.length == 0) {
            p.sendMessage("§e=== КЛАНЫ: СЕЗОН 2 ===");
            p.sendMessage("§f/clan create <ID> <Имя>, /clan invite <Ник>, /clan join <ID>");
            p.sendMessage("§f/clan kick <Ник>, /clan leave, /clan disband");
            p.sendMessage("§f/clan chat §7- Вкл/Выкл клановый чат");
            p.sendMessage("§f/clan top §7- Топ контрибьюторов клана");
            p.sendMessage("§f/clan spec <КЛАСС>, /clan upgrade, /clan bank deposit");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create":
                if (cp.getClanId() != null) { 
                    p.sendMessage("§cВы уже состоите в клане!"); return true; 
                }
                if (args.length < 3) {
                    p.sendMessage("§cИспользование: /clan create <ID> <Имя>"); return true;
                }
                String clanId = args[1].toUpperCase();
                if (clans.containsKey(clanId)) { 
                    p.sendMessage("§cЭтот Тэг уже занят!"); return true; 
                }

                int baseCost = getConfig().getInt("economy.creation.base-cost", 2000);
                int secretCost = getConfig().getInt("economy.creation.secret-cost", 500);
                String secretRoleId = getConfig().getString("discord.secret-role-id", "");

                int finalCost = baseCost;
                boolean hasSecretRole = false;

                String discordId = linkedAccounts.get(p.getUniqueId());
                if (discordId != null && jda != null && secretRoleId != null && !secretRoleId.isEmpty()) {
                    try {
                        Guild guild = jda.getGuildById(guildId);
                        if (guild != null) {
                            net.dv8tion.jda.api.entities.Member member = guild.retrieveMemberById(discordId).complete();
                            if (member != null && member.getRoles().stream().anyMatch(r -> r.getId().equals(secretRoleId))) {
                                hasSecretRole = true;
                                finalCost = secretCost;
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (countDirtyPoints(p) < finalCost) {
                    p.sendMessage("§cДля создания клана нужно §e" + finalCost + " Грязных Очков§c в инвентаре!");
                    if (!hasSecretRole) p.sendMessage("§7(Игрокам с ролью Secret создание обойдется всего в " + secretCost + " очков)");
                    return true;
                }

                consumeDirtyPoints(p, finalCost);
                Clan newClan = new Clan(clanId, args[2], p.getUniqueId());
                clans.put(clanId, newClan); 
                cp.setClanId(clanId);
                
                p.sendMessage("§aКлан успешно создан за §e" + finalCost + " Грязных Очков§a!");
                if (hasSecretRole) p.sendMessage("§dПрименена скидка роли Secret!");
                
                if (jda != null) createDiscordRoleAndChannel(newClan, p);
                break;

            case "invite":
                if (cp.getClanId() == null) return true;
                Player target = Bukkit.getPlayer(args[1]);
                if (target != null) {
                    pendingInvites.put(target.getUniqueId(), cp.getClanId());
                    p.sendMessage("§aПриглашение отправлено!");
                    target.sendMessage("§eВас позвали в клан! Пиши: /clan join " + cp.getClanId());
                }
                break;
            case "join":
                if (cp.getClanId() != null) return true;
                String targetId = args[1].toUpperCase();
                if (targetId.equals(pendingInvites.get(p.getUniqueId()))) {
                    Clan jClan = clans.get(targetId);
                    if (jClan.getMembers().size() >= 5) { p.sendMessage("§cКлан заполнен!"); return true; }
                    jClan.getMembers().add(p.getUniqueId());
                    cp.setClanId(targetId);
                    p.sendMessage("§aВы вступили в клан!");
                    if (jda != null && jClan.getDiscordRoleId() != null) {
                        Guild g = jda.getGuildById(guildId);
                        String dId = linkedAccounts.get(p.getUniqueId());
                        if (g != null && dId != null) g.retrieveMemberById(dId).queue(m -> g.addRoleToMember(m, g.getRoleById(jClan.getDiscordRoleId())).queue());
                    }
                }
                break;
            case "chat":
                cp.setClanChatEnabled(!cp.isClanChatEnabled());
                p.sendMessage(cp.isClanChatEnabled() ? "§aКлановый чат включен!" : "§eКлановый чат выключен.");
                break;
            case "top":
                if (cp.getClanId() == null) return true;
                Clan tClan = clans.get(cp.getClanId());
                p.sendMessage("§e--- Топ вкладчиков в казну ---");
                tClan.getMembers().stream()
                        .map(this::getClanPlayer)
                        .sorted((a, b) -> Long.compare(b.getContributedPoints(), a.getContributedPoints()))
                        .forEach(m -> {
                            OfflinePlayer op = Bukkit.getOfflinePlayer(m.getUuid());
                            p.sendMessage("§f" + (op.getName() != null ? op.getName() : "Unknown") + " §7- §e" + m.getContributedPoints() + " Очков");
                        });
                break;
            case "kick":
                if (cp.getClanId() == null) return true;
                Clan kClan = clans.get(cp.getClanId());
                if (!kClan.getLeader().equals(p.getUniqueId())) { p.sendMessage("§cТолько лидер может выгонять!"); return true; }
                OfflinePlayer kTarget = Bukkit.getOfflinePlayer(args[1]);
                if (kClan.getMembers().contains(kTarget.getUniqueId()) && !kTarget.getUniqueId().equals(p.getUniqueId())) {
                    kClan.getMembers().remove(kTarget.getUniqueId());
                    ClanPlayer kcp = getClanPlayer(kTarget.getUniqueId());
                    kcp.setClanId(null); kcp.setMaxHealthLevel(0);
                    removeDiscordRole(kTarget.getUniqueId(), kClan.getDiscordRoleId());
                    p.sendMessage("§aИгрок изгнан!");
                }
                break;
            case "leave":
                if (cp.getClanId() == null) return true;
                Clan lClan = clans.get(cp.getClanId());
                if (lClan.getLeader().equals(p.getUniqueId())) { p.sendMessage("§cЛидер не может выйти! Сделай disband."); return true; }
                lClan.getMembers().remove(p.getUniqueId()); cp.setClanId(null); cp.setMaxHealthLevel(0);
                removeDiscordRole(p.getUniqueId(), lClan.getDiscordRoleId());
                p.setHealth(0);
                p.sendMessage("§cВы покинули клан! Навыки сброшены.");
                break;
            case "disband":
                if (cp.getClanId() == null) return true;
                Clan dClan = clans.get(cp.getClanId());
                if (!dClan.getLeader().equals(p.getUniqueId())) return true;
                for (UUID mId : dClan.getMembers()) {
                    ClanPlayer mcp = getClanPlayer(mId); mcp.setClanId(null); mcp.setMaxHealthLevel(0);
                    removeDiscordRole(mId, dClan.getDiscordRoleId());
                }
                deleteDiscordClanSetup(dClan);
                clans.remove(dClan.getId());
                p.sendMessage("§cКлан распущен!");
                break;
            case "spec":
                if (cp.getClanId() == null) return true;
                Clan specClan = clans.get(cp.getClanId());
                if (!specClan.getLeader().equals(p.getUniqueId()) || specClan.getRank().ordinal() < ClanRank.D.ordinal() || specClan.getGuildType() != GuildType.NONE) {
                    p.sendMessage("§cДоступно лидеру, Ранг D+, только 1 раз!"); return true;
                }
                try {
                    GuildType type = GuildType.valueOf(args[1].toUpperCase());
                    specClan.setGuildType(type);
                    Bukkit.broadcastMessage("§e§l[КЛАНЫ] §fКлан §6" + specClan.getName() + " §fвыбрал путь: §b" + type.getDisplayName());
                } catch (Exception e) { p.sendMessage("§cMAGE, BLACKSMITH, ENGINEER, SMUGGLER"); }
                break;
            case "upgrade": openUpgradeMenu(p); break;
            case "bank":
                if (args.length > 1 && args[1].equalsIgnoreCase("deposit") && cp.getClanId() != null) {
                    int amt = consumeDirtyPoints(p, Integer.MAX_VALUE);
                    if (amt > 0) { 
                        clans.get(cp.getClanId()).addBankPoints(amt); 
                        cp.addContributedPoints(amt);
                        p.sendMessage("§aСдано §e" + amt + " §aОчков!"); 
                        checkRankUp(clans.get(cp.getClanId()));
                    }
                }
                break;
            case "forge":
                if (cp.getClanId() != null && clans.get(cp.getClanId()).getGuildType() == GuildType.BLACKSMITH) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand.getType() != Material.AIR && countDirtyPoints(p) >= 500) {
                        consumeDirtyPoints(p, 500);
                        ItemMeta m = hand.getItemMeta(); m.setUnbreakable(true); hand.setItemMeta(m); markAsGuildItem(hand);
                        p.sendMessage("§aАртефакт скован!");
                    }
                }
                break;
            case "radar":
                if (cp.getClanId() != null && clans.get(cp.getClanId()).getGuildType() == GuildType.SMUGGLER && countDirtyPoints(p) >= 100) {
                    consumeDirtyPoints(p, 100); p.getInventory().addItem(getCustomItem(Material.COMPASS, "§cТрекер Крови", dirtyKey));
                }
                break;
            case "nexus":
                if (cp.getClanId() != null && clans.get(cp.getClanId()).getLeader().equals(p.getUniqueId())) {
                    p.getInventory().addItem(getCustomItem(Material.BEACON, "§d§lСердце Клана", nexusKey));
                }
                break;
            case "pawbox": 
                if (p.isOp()) p.getInventory().addItem(getCustomItem(Material.CHEST, "§e§lPawBox 2.0", pawboxKey));
                break;
        }
        return true;
    }

    // ==========================================
    //           ЛОГИКА PVP, PVE И ЛИМИТОВ
    // ==========================================
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() != null && Math.random() > 0.5) {
            Player killer = event.getEntity().getKiller();
            ClanPlayer cp = getClanPlayer(killer.getUniqueId());
            
            int limit = getConfig().getInt("limits.daily-pve-points", 500);
            String today = LocalDate.now().toString();
            if (!today.equals(cp.getLastFarmDate())) {
                cp.setLastFarmDate(today);
                cp.setDailyPvEPoints(0);
            }

            if (cp.getDailyPvEPoints() < limit) {
                cp.setDailyPvEPoints(cp.getDailyPvEPoints() + 1);
                event.getDrops().add(getCustomItem(Material.GOLD_NUGGET, "§6Осколок Влияния", dirtyKey));
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        
        List<ItemStack> saved = new ArrayList<>();
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            if (drop.hasItemMeta() && drop.getItemMeta().getPersistentDataContainer().has(guildItemKey, PersistentDataType.BYTE)) {
                saved.add(drop); it.remove();
            }
        }
        if (!saved.isEmpty()) soulboundItems.put(victim.getUniqueId(), saved);

        int pts = 0;
        it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            if (drop.hasItemMeta() && drop.getItemMeta().getPersistentDataContainer().has(dirtyKey, PersistentDataType.BYTE)) {
                pts += drop.getAmount(); it.remove();
            }
        }

        if (pts > 0) {
            if (killer != null) {
                int vRank = getClanPlayer(victim.getUniqueId()).getClanId() != null ? clans.get(getClanPlayer(victim.getUniqueId()).getClanId()).getRank().ordinal() : -1;
                int kRank = getClanPlayer(killer.getUniqueId()).getClanId() != null ? clans.get(getClanPlayer(killer.getUniqueId()).getClanId()).getRank().ordinal() : -1;

                if (kRank > vRank + 1) pts = 0; // Штраф за убийство слабого
                else if (vRank > kRank + 1) pts = (int)(pts * 1.5);
            }
            while (pts > 0) {
                int stack = Math.min(pts, 16);
                event.getDrops().add(getCustomItem(Material.GOLD_NUGGET, "§6Осколок Влияния", dirtyKey));
                pts -= stack;
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID u = event.getPlayer().getUniqueId();
        if (soulboundItems.containsKey(u)) {
            for (ItemStack i : soulboundItems.get(u)) event.getPlayer().getInventory().addItem(i);
            soulboundItems.remove(u);
        }
    }

    @EventHandler
    public void onC4Place(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(c4Key, PersistentDataType.BYTE)) {
            Player p = event.getPlayer();
            LocalTime now = LocalTime.now();
            LocalTime start = LocalTime.parse(getConfig().getString("raids.prime-time-start", "19:00"));
            LocalTime end = LocalTime.parse(getConfig().getString("raids.prime-time-end", "22:00"));
            if (now.isBefore(start) || now.isAfter(end)) { event.setCancelled(true); return; }

            for (Clan c : clans.values()) {
                if (c.getNexusLocation() != null && c.getNexusLocation().distance(event.getBlock().getLocation()) < 10 && !c.getMembers().contains(p.getUniqueId())) {
                    c.addBankPoints(-1000);
                    Bukkit.broadcastMessage("§4ВНИМАНИЕ! База клана " + c.getName() + " подрывается C4!");
                    if (c.getBankPoints() < 0) { c.setBankPoints(0); c.setNexusLocation(null); Bukkit.broadcastMessage("§c§lСЕРДЦЕ КЛАНА УНИЧТОЖЕНО!"); }
                }
            }
        }
    }

    // ==========================================
    //           МОНОПОЛИИ (Анти-Абуз)
    // ==========================================
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        Clan clan = clans.get(getClanPlayer(event.getPlayer().getUniqueId()).getClanId());
        GuildType type = clan != null ? clan.getGuildType() : GuildType.NONE;

        if (event.getInventory().getType() == InventoryType.BREWING && type != GuildType.MAGE) event.setCancelled(true);
        if (event.getInventory().getType() == InventoryType.ANVIL && type != GuildType.BLACKSMITH && type != GuildType.MAGE) event.setCancelled(true);
        if (event.getInventory().getType() == InventoryType.SMITHING && type != GuildType.BLACKSMITH) event.setCancelled(true);
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        Clan clan = clans.get(getClanPlayer(event.getView().getPlayer().getUniqueId()).getClanId());
        GuildType type = clan != null ? clan.getGuildType() : GuildType.NONE;
        ItemStack slot2 = event.getInventory().getItem(1);

        if (type == GuildType.BLACKSMITH && slot2 != null && slot2.getType() == Material.ENCHANTED_BOOK) event.setResult(null);
        if (type == GuildType.MAGE && slot2 != null && slot2.getType() != Material.ENCHANTED_BOOK) event.setResult(null);
    }

    @SuppressWarnings("deprecation")
    private void applyGuildPassives(Player player) {
        ClanPlayer cp = getClanPlayer(player.getUniqueId());
        Clan clan = cp.getClanId() != null ? clans.get(cp.getClanId()) : null;
        if (clan == null) return;

        double baseHp = 20;
        if (clan.getGuildType() == GuildType.MAGE) baseHp = 16;
        if (clan.getGuildType() == GuildType.SMUGGLER) baseHp = 18;
        
        double maxHp = baseHp + (cp.getMaxHealthLevel() * 2);
        
        // Устанавливаем здоровье надежным универсальным методом
        player.setMaxHealth(maxHp);

        switch (clan.getGuildType()) {
            case ENGINEER: player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 60, 0, false, false, false)); break;
            case SMUGGLER: player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 0, false, false, false)); break;
            case MAGE: player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, false, false, false)); break;
            case BLACKSMITH: player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 0, false, false, false)); break;
        }
    }

    @EventHandler
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            Clan clan = clans.get(getClanPlayer(p.getUniqueId()).getClanId());
            if (clan != null && clan.getGuildType() == GuildType.SMUGGLER) event.setCancelled(true);
        }
    }

    // ==========================================
    //           УТИЛИТЫ И ДИСКОРД
    // ==========================================
    private void removeDiscordRole(UUID playerUuid, String roleId) {
        if (jda == null || roleId == null || guildId == null) return;
        Guild g = jda.getGuildById(guildId);
        String dId = linkedAccounts.get(playerUuid);
        if (g != null && dId != null) g.retrieveMemberById(dId).queue(m -> g.removeRoleFromMember(m, g.getRoleById(roleId)).queue());
    }

    private void deleteDiscordClanSetup(Clan clan) {
        if (jda == null || guildId == null) return;
        Guild g = jda.getGuildById(guildId);
        if (g != null) {
            if (clan.getDiscordRoleId() != null) { Role r = g.getRoleById(clan.getDiscordRoleId()); if (r != null) r.delete().queue(); }
            if (clan.getDiscordTextChannelId() != null) {
                TextChannel tc = g.getTextChannelById(clan.getDiscordTextChannelId());
                if (tc != null) {
                    Category cat = tc.getParentCategory();
                    tc.delete().queue();
                    if (cat != null) {
                        for (net.dv8tion.jda.api.entities.channel.middleman.GuildChannel gc : cat.getChannels()) gc.delete().queue();
                        cat.delete().queue();
                    }
                }
            }
        }
    }

    private void createDiscordRoleAndChannel(Clan clan, Player leader) {
        if (jda == null || guildId == null) return;
        Guild guild = jda.getGuildById(guildId);
        if (guild != null) {
            guild.createRole().setName(clan.getName()).queue(role -> {
                clan.setDiscordRoleId(role.getId());
                String dId = linkedAccounts.get(leader.getUniqueId());
                if (dId != null) guild.retrieveMemberById(dId).queue(m -> guild.addRoleToMember(m, role).queue());
                guild.createCategory("🛡️ " + clan.getName()).queue(cat -> {
                    cat.createTextChannel("штаб").queue(txt -> clan.setDiscordTextChannelId(txt.getId()));
                });
            });
        }
    }

    private class DiscordListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) return;
            String msg = event.getMessage().getContentRaw();
            
            if (msg.startsWith("!link ")) {
                String code = msg.substring(6).trim();
                String uuidStr = linkCodes.remove(code);
                if (uuidStr != null) {
                    UUID uuid = UUID.fromString(uuidStr);
                    linkedAccounts.put(uuid, event.getAuthor().getId());
                    linkedConfig.set(uuid.toString(), event.getAuthor().getId());
                    try { linkedConfig.save(linkedFile); } catch (IOException ignored) {}
                    event.getChannel().sendMessage("✅ Аккаунт привязан!").queue();
                }
                return;
            }

            if (event.isFromGuild()) {
                String chId = event.getChannel().getId();
                for (Clan c : clans.values()) {
                    if (chId.equals(c.getDiscordTextChannelId())) {
                        String mcMsg = "§9[DS] §b[Клан] §f" + event.getAuthor().getName() + " §8» §7" + event.getMessage().getContentDisplay();
                        for (UUID memId : c.getMembers()) { Player m = Bukkit.getPlayer(memId); if (m != null) m.sendMessage(mcMsg); }
                        break;
                    }
                }
            }
        }
    }

    // ==========================================
    //           ДАТА КЛАССЫ И ПРОЧЕЕ
    // ==========================================
    public ItemStack getDirtyPointItem(int amount) {
        return getCustomItem(Material.GOLD_NUGGET, "§6Осколок Влияния", dirtyKey);
    }
    public boolean isDirtyPoint(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(dirtyKey, PersistentDataType.BYTE);
    }
    public int countDirtyPoints(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) if (isDirtyPoint(item)) count += item.getAmount();
        return count;
    }
    public int consumeDirtyPoints(Player player, int amountToConsume) {
        int consumed = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (isDirtyPoint(item)) {
                int stackAmount = item.getAmount();
                if (consumed + stackAmount <= amountToConsume) { consumed += stackAmount; player.getInventory().setItem(i, null); } 
                else { int needed = amountToConsume - consumed; item.setAmount(stackAmount - needed); consumed += needed; break; }
            }
        }
        return consumed;
    }
    private void checkRankUp(Clan clan) {
        for (ClanRank rank : ClanRank.values()) {
            if (clan.getRank().ordinal() < rank.ordinal() && clan.getBankPoints() >= rank.getRequiredPoints()) {
                clan.setRank(rank); Bukkit.broadcastMessage("§e§l[КЛАНЫ] §fКлан §6" + clan.getName() + " §fапнул Ранг §b" + rank.name() + "§f!");
            }
        }
    }
    public void markAsGuildItem(ItemStack item) {
        ItemMeta meta = item.getItemMeta(); meta.getPersistentDataContainer().set(guildItemKey, PersistentDataType.BYTE, (byte) 1);
        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>(); lore.add("§b⚜ Артефакт Гильдии (Не выпадает)");
        meta.setLore(lore); item.setItemMeta(meta);
    }

    private void loadLinks() {
        linkedFile = new File(getDataFolder(), "linked.yml");
        if (!linkedFile.exists()) { getDataFolder().mkdirs(); try { linkedFile.createNewFile(); } catch (IOException ignored) {} }
        linkedConfig = YamlConfiguration.loadConfiguration(linkedFile);
        for (String uuidStr : linkedConfig.getKeys(false)) linkedAccounts.put(UUID.fromString(uuidStr), linkedConfig.getString(uuidStr));
    }
    private void loadGameData() {
        File dataFile = new File(getDataFolder(), "data.yml"); if (!dataFile.exists()) return;
        FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        if (data.contains("clans")) {
            for (String clanId : data.getConfigurationSection("clans").getKeys(false)) {
                String path = "clans." + clanId;
                Clan clan = new Clan(clanId, data.getString(path + ".name"), UUID.fromString(data.getString(path + ".leader")));
                clan.setRank(ClanRank.valueOf(data.getString(path + ".rank", "F"))); clan.setGuildType(GuildType.valueOf(data.getString(path + ".guild", "NONE")));
                clan.addBankPoints(data.getLong(path + ".bank", 0)); clan.setDiscordRoleId(data.getString(path + ".discordRole")); clan.setDiscordTextChannelId(data.getString(path + ".discordChannel"));
                for (String m : data.getStringList(path + ".members")) clan.getMembers().add(UUID.fromString(m));
                clans.put(clanId, clan);
            }
        }
        if (data.contains("players")) {
            for (String uuidStr : data.getConfigurationSection("players").getKeys(false)) {
                ClanPlayer cp = new ClanPlayer(UUID.fromString(uuidStr));
                cp.setClanId(data.getString("players." + uuidStr + ".clanId")); cp.setMaxHealthLevel(data.getInt("players." + uuidStr + ".hpLevel", 0));
                cp.addContributedPoints(data.getLong("players." + uuidStr + ".contributed", 0));
                cp.setDailyPvEPoints(data.getInt("players." + uuidStr + ".dailyPoints", 0)); cp.setLastFarmDate(data.getString("players." + uuidStr + ".farmDate", ""));
                players.put(cp.getUuid(), cp);
            }
        }
    }
    private void saveData() {
        File dataFile = new File(getDataFolder(), "data.yml"); FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        data.set("clans", null);
        for (Clan c : clans.values()) {
            String path = "clans." + c.getId();
            data.set(path + ".name", c.getName()); data.set(path + ".leader", c.getLeader().toString()); data.set(path + ".rank", c.getRank().name());
            data.set(path + ".guild", c.getGuildType().name()); data.set(path + ".bank", c.getBankPoints()); data.set(path + ".discordRole", c.getDiscordRoleId());
            data.set(path + ".discordChannel", c.getDiscordTextChannelId());
            data.set(path + ".members", c.getMembers().stream().map(UUID::toString).collect(Collectors.toList()));
        }
        data.set("players", null);
        for (ClanPlayer cp : players.values()) {
            String path = "players." + cp.getUuid().toString();
            data.set(path + ".clanId", cp.getClanId()); data.set(path + ".hpLevel", cp.getMaxHealthLevel()); data.set(path + ".contributed", cp.getContributedPoints());
            data.set(path + ".dailyPoints", cp.getDailyPvEPoints()); data.set(path + ".farmDate", cp.getLastFarmDate());
        }
        try { data.save(dataFile); } catch (IOException ignored) {}
    }

    public ClanPlayer getClanPlayer(UUID uuid) { return players.computeIfAbsent(uuid, k -> new ClanPlayer(uuid)); }

    public enum GuildType { NONE("Без гильдии"), BLACKSMITH("Железный Легион"), MAGE("Тайный Орден"), ENGINEER("Гильдия Инженеров"), SMUGGLER("Синдикат");
        private final String d; GuildType(String d) { this.d = d; } public String getDisplayName() { return d; }
    }
    public enum ClanRank { F(0), E(5000), D(15000), C(40000), B(100000), A(250000), S(500000), S_PLUS(1000000); private final long r; ClanRank(long r) { this.r = r; } public long getRequiredPoints() { return r; } }

    public static class ClanPlayer {
        private final UUID uuid; private String clanId; private int maxHealthLevel = 0;
        private boolean clanChatEnabled = false; private long contributedPoints = 0;
        private int dailyPvEPoints = 0; private String lastFarmDate = "";
        
        public ClanPlayer(UUID uuid) { this.uuid = uuid; }
        public UUID getUuid() { return uuid; } public String getClanId() { return clanId; } public void setClanId(String c) { this.clanId = c; }
        public int getMaxHealthLevel() { return maxHealthLevel; } public void setMaxHealthLevel(int l) { this.maxHealthLevel = l; }
        public boolean isClanChatEnabled() { return clanChatEnabled; } public void setClanChatEnabled(boolean b) { this.clanChatEnabled = b; }
        public long getContributedPoints() { return contributedPoints; } public void addContributedPoints(long amt) { this.contributedPoints += amt; }
        public int getDailyPvEPoints() { return dailyPvEPoints; } public void setDailyPvEPoints(int p) { this.dailyPvEPoints = p; }
        public String getLastFarmDate() { return lastFarmDate; } public void setLastFarmDate(String d) { this.lastFarmDate = d; }
    }
    
    public static class Clan {
        private String id; private String name; private UUID leader; private Set<UUID> members = new HashSet<>();
        private ClanRank rank = ClanRank.F; private GuildType selectedGuild = GuildType.NONE;
        private long bankPoints = 0; private Location nexusLocation; private String discordRoleId; private String discordTextChannelId;
        public Clan(String id, String name, UUID leader) { this.id = id; this.name = name; this.leader = leader; this.members.add(leader); }
        public String getId() { return id; } public String getName() { return name; } public UUID getLeader() { return leader; }
        public Set<UUID> getMembers() { return members; } public ClanRank getRank() { return rank; } public void setRank(ClanRank r) { this.rank = r; }
        public GuildType getGuildType() { return selectedGuild; } public void setGuildType(GuildType t) { this.selectedGuild = t; }
        public long getBankPoints() { return bankPoints; } public void addBankPoints(long p) { this.bankPoints += p; }
        public void setBankPoints(long p) { this.bankPoints = p; }
        public Location getNexusLocation() { return nexusLocation; } public void setNexusLocation(Location l) { this.nexusLocation = l; }
        public String getDiscordRoleId() { return discordRoleId; } public void setDiscordRoleId(String i) { this.discordRoleId = i; }
        public String getDiscordTextChannelId() { return discordTextChannelId; } public void setDiscordTextChannelId(String i) { this.discordTextChannelId = i; }
    }

    public static class PendingVerification {
        public String ip; public long timestamp;
        public PendingVerification(String ip, long t) { this.ip = ip; this.timestamp = t; }
    }
}
