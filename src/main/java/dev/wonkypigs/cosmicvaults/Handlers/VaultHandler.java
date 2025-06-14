package dev.wonkypigs.cosmicvaults.Handlers;

import dev.wonkypigs.cosmicvaults.Commands.VaultsCommand;
import dev.wonkypigs.cosmicvaults.CosmicVaults;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.wonkypigs.cosmicvaults.Helper.VaultSaveHelper.deserializeItemsArray;
import static dev.wonkypigs.cosmicvaults.Helper.VaultSaveHelper.serializeItemsArray;

public class VaultHandler implements Listener {

    private static final CosmicVaults plugin = CosmicVaults.getInstance();

    public static void openVault(Player player, int id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PreparedStatement statement = plugin.getConnection()
                        .prepareStatement("SELECT CONTENTS FROM player_vaults WHERE UUID=? AND VAULT_ID=?");
                statement.setString(1, player.getUniqueId().toString());
                statement.setInt(2, id);
                ResultSet result = statement.executeQuery();

                PreparedStatement statement2 = plugin.getConnection()
                        .prepareStatement("SELECT VAULT_ID FROM player_vaults WHERE UUID=? ORDER BY VAULT_ID ASC");
                statement2.setString(1, player.getUniqueId().toString());
                ResultSet results2 = statement2.executeQuery();
                int total_items = 0;
                while (results2.next()) {
                    total_items++;
                }

                // inventory
                int slots = (plugin.getConfig().getInt("vault-storage-rows")+1)*9;
                if (slots > 54) {
                    slots = 54;
                }
                Inventory inv = plugin.getServer().createInventory(null, slots, "&5&lKho #".replace("&", "§") + id);

                // filling up
                if (result.next()) {
                    inv.setContents(deserializeItemsArray(result.getString("CONTENTS")));
                }
                result.close();

                for (int i = slots-9; i < slots; i++) {
                    ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
                    ItemMeta meta = item.getItemMeta();
                    meta.setDisplayName(" ");
                    item.setItemMeta(meta);
                    inv.setItem(i, item);
                }
                ItemStack back = new ItemStack(Material.BARRIER);
                ItemMeta backMeta = back.getItemMeta();
                backMeta.setDisplayName("&c&lQuay lại".replace("&", "§"));
                back.setItemMeta(backMeta);
                inv.setItem(slots-5, back);

                if (id != total_items) {
                    ItemStack next = new ItemStack(Material.GREEN_WOOL);
                    ItemMeta nextMeta = next.getItemMeta();
                    nextMeta.setDisplayName("&a&lKho tiếp theo".replace("&", "§"));
                    next.setItemMeta(nextMeta);
                    inv.setItem(slots-3, next);
                }

                if (id != 1) {
                    ItemStack prev = new ItemStack(Material.RED_WOOL);
                    ItemMeta prevMeta = prev.getItemMeta();
                    prevMeta.setDisplayName("&c&lKho trước đó".replace("&", "§"));
                    prev.setItemMeta(prevMeta);
                    inv.setItem(slots-7, prev);
                }

                Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(inv));

            } catch (SQLException | IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static void saveVault(Player player, Inventory inv, int id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // delete old items
                PreparedStatement statement = plugin.getConnection()
                        .prepareStatement("DELETE FROM player_vaults WHERE UUID=? AND VAULT_ID=?");
                statement.setString(1, player.getUniqueId().toString());
                statement.setInt(2, id);
                statement.executeUpdate();

                // save new items
                statement = plugin.getConnection()
                        .prepareStatement("INSERT INTO player_vaults (UUID, VAULT_ID, CONTENTS) VALUES (?, ?, ?)");
                statement.setString(1, player.getUniqueId().toString());
                statement.setInt(2, id);
                String contents = serializeItemsArray(inv.getContents());
                statement.setString(3, contents);
                statement.executeUpdate();

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static void createNewVault(Player player, int page) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // can player make more vaults
                PreparedStatement statement = plugin.getConnection()
                        .prepareStatement("SELECT VAULT_ID FROM player_vaults WHERE UUID=? ORDER BY VAULT_ID DESC LIMIT 1");
                statement.setString(1, player.getUniqueId().toString());
                ResultSet result = statement.executeQuery();
                // if no vaults
                int vaults = 0;
                if (result.next()) {
                    vaults = result.getInt("VAULT_ID");
                } else {
                    vaults = 0;
                }
                result.close();

                // get player's effective perms
                AtomicInteger maxVaults = new AtomicInteger();
                maxVaults.set(Integer.parseInt(plugin.getConfig().getString("default-vaults")));
                player.getEffectivePermissions().forEach((perm) -> {
                    if(!player.hasPermission("cosmicvaults.vaults.unlimited")) {
                        if (perm.getPermission().startsWith("cosmicvaults.vaults.")) {
                            int vaultsPerm = Integer.parseInt(perm.getPermission().replace("cosmicvaults.vaults.", ""));
                            if (vaultsPerm > maxVaults.get()) {
                                maxVaults.set(vaultsPerm);
                            }
                        }
                    } else {
                        maxVaults.set(99999);
                    }
                });

                // max vaults and no cosmicvaults.vaults.unlimited perm?
                if ((vaults >= maxVaults.get()) && (!player.hasPermission("cosmicvaults.vaults.unlimited"))) {
                    player.sendMessage(plugin.getConfigValue("max-vaults-reached")
                            .replace("{prefix}", plugin.getConfigValue("prefix"))
                            .replace("&", "§"));
                    return;
                }

                // create new vault for player
                statement = plugin.getConnection()
                        .prepareStatement("INSERT INTO player_vaults (UUID, VAULT_ID, CONTENTS) VALUES (?, ?, ?)");
                statement.setString(1, player.getUniqueId().toString());
                statement.setInt(2, vaults + 1);
                Inventory vault = plugin.getServer().createInventory(null, 27, "");
                statement.setString(3, serializeItemsArray(vault.getContents()));
                statement.executeUpdate();

                // update vaultsmenu
                VaultsCommand.vaultMenuFiller(page, player);
                player.sendMessage("§aĐã tạo một kho mới! (ID: #" + (vaults + 1) + ")");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
