package net.iyha.core;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Exposes IYHA money to every Vault plugin. Vault is synchronous, so calls block the caller for the length of
 * one database transaction (milliseconds on SQLite or a local MariaDB).
 */
final class VaultHook implements Economy, Hook {
    private final IyhaCore plugin;

    VaultHook(IyhaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        Bukkit.getServicesManager().register(Economy.class, this, plugin, ServicePriority.Highest);
    }

    @Override
    public void disable() {
        Bukkit.getServicesManager().unregister(Economy.class, this);
    }

    private OfflinePlayer byName(String name) {
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private EconomyService.TxResult await(CompletableFuture<EconomyService.TxResult> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            return EconomyService.TxResult.of(EconomyService.Status.ERROR);
        }
    }

    private double balanceOf(UUID id) {
        return plugin.economy().balanceBlocking(id) / (double) Money.SCALE;
    }

    private EconomyResponse withdraw(OfflinePlayer player, double amount) {
        double bal = balanceOf(player.getUniqueId());
        if (amount < 0) {
            return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Cannot withdraw a negative amount");
        }
        long cents = Money.fromDouble(amount);
        if (cents == 0) {
            return new EconomyResponse(0, bal, EconomyResponse.ResponseType.SUCCESS, "");
        }
        EconomyService.TxResult r = await(plugin.economy().sink(player.getUniqueId(), cents, "VAULT_WITHDRAW", null, "vault"));
        return switch (r.status()) {
            case OK -> new EconomyResponse(amount, r.fromBalance() / (double) Money.SCALE, EconomyResponse.ResponseType.SUCCESS, "");
            case INSUFFICIENT -> new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
            case FROZEN -> new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Economy is frozen");
            default -> new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Transaction failed");
        };
    }

    private EconomyResponse deposit(OfflinePlayer player, double amount) {
        double bal = balanceOf(player.getUniqueId());
        if (amount < 0) {
            return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Cannot deposit a negative amount");
        }
        long cents = Money.fromDouble(amount);
        if (cents == 0) {
            return new EconomyResponse(0, bal, EconomyResponse.ResponseType.SUCCESS, "");
        }
        EconomyService.TxResult r = await(plugin.economy().mint(player.getUniqueId(), cents, "VAULT_DEPOSIT", null, "vault"));
        return switch (r.status()) {
            case OK -> new EconomyResponse(amount, r.toBalance() / (double) Money.SCALE, EconomyResponse.ResponseType.SUCCESS, "");
            case CAP -> new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Balance limit reached");
            case FROZEN -> new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Economy is frozen");
            default -> new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, "Transaction failed");
        };
    }

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not supported");
    }

    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "IYHA Economy"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return plugin.economy().format(Money.fromDouble(amount)); }
    @Override public String currencyNamePlural() { return "Money"; }
    @Override public String currencyNameSingular() { return "Money"; }

    @Override public boolean hasAccount(String playerName) { return byName(playerName) != null; }
    @Override public boolean hasAccount(OfflinePlayer player) { return true; }
    @Override public boolean hasAccount(String playerName, String worldName) { return hasAccount(playerName); }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return true; }

    @Override public double getBalance(String playerName) {
        OfflinePlayer p = byName(playerName);
        return p == null ? 0 : balanceOf(p.getUniqueId());
    }
    @Override public double getBalance(OfflinePlayer player) { return balanceOf(player.getUniqueId()); }
    @Override public double getBalance(String playerName, String world) { return getBalance(playerName); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    @Override public boolean has(String playerName, double amount) { return getBalance(playerName) >= amount; }
    @Override public boolean has(OfflinePlayer player, double amount) { return getBalance(player) >= amount; }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }

    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer p = byName(playerName);
        return p == null ? new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Unknown player") : withdraw(p, amount);
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return withdraw(player, amount); }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return withdrawPlayer(playerName, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return withdraw(player, amount); }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer p = byName(playerName);
        return p == null ? new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Unknown player") : deposit(p, amount);
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return deposit(player, amount); }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return depositPlayer(playerName, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return deposit(player, amount); }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }

    @Override public boolean createPlayerAccount(String playerName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { return true; }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return true; }
}
