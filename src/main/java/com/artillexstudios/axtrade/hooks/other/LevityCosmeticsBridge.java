package com.artillexstudios.axtrade.hooks.other;

import com.artillexstudios.axtrade.trade.Trade;
import com.artillexstudios.axtrade.trade.TradeCosmeticOffer;
import com.artillexstudios.axtrade.trade.TradePlayer;
import com.artillexstudios.axtrade.trade.Trades;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.UUID;

import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public class LevityCosmeticsBridge {
    private static boolean available = false;
    private static Class<?> apiClass;
    private static Object api;

    private static Method openTradePickerMethod;
    private static Method lockForTradeMethod;
    private static Method unlockTradeMethod;
    private static Method completeTradeMethod;
    private static Method getTradePreviewItemMethod;

    public static void setup() {
        available = false;
        apiClass = null;
        api = null;

        Plugin levity = Bukkit.getPluginManager().getPlugin("LevityCosmetics");
        if (levity == null || !levity.isEnabled()) return;

        try {
            apiClass = Class.forName("me.levity.levityCosmetics.api.trade.LevityCosmeticsTradeApi");
            RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(apiClass);
            if (provider == null) return;
            api = provider.getProvider();
            if (api == null) return;

            openTradePickerMethod = apiClass.getMethod("openTradePicker", Player.class, String.class);
            lockForTradeMethod = apiClass.getMethod("lockForTrade", UUID.class, UUID.class, String.class);
            unlockTradeMethod = apiClass.getMethod("unlockTrade", UUID.class, UUID.class, String.class);
            completeTradeMethod = apiClass.getMethod("completeTrade", UUID.class, UUID.class, UUID.class, String.class);
            try {
                getTradePreviewItemMethod = apiClass.getMethod("getTradePreviewItem", UUID.class, UUID.class, Player.class);
            } catch (NoSuchMethodException ignored) {
                getTradePreviewItemMethod = null;
            }

            registerSelectionListener();
            available = true;
            Bukkit.getLogger().info("[AxTrade] Hooked into LevityCosmetics trade API.");
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[AxTrade] Failed to hook LevityCosmetics API: " + t.getMessage());
        }
    }

    public static boolean isAvailable() {
        return available && api != null;
    }

    public static boolean openTradePicker(Player player, String tradeId) {
        if (!isAvailable()) return false;
        try {
            Object out = openTradePickerMethod.invoke(api, player, tradeId);
            return out instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean lockForTrade(UUID ownerId, UUID slotUid, String tradeId) {
        if (!isAvailable()) return false;
        try {
            Object result = lockForTradeMethod.invoke(api, ownerId, slotUid, tradeId);
            Method locked = result.getClass().getMethod("getLocked");
            Object out = locked.invoke(result);
            return out instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unlockTrade(UUID ownerId, UUID slotUid, String tradeId) {
        if (!isAvailable()) return false;
        try {
            Object result = unlockTradeMethod.invoke(api, ownerId, slotUid, tradeId);
            return result instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean completeTrade(UUID fromId, UUID toId, UUID slotUid, String tradeId) {
        if (!isAvailable()) return false;
        try {
            Object result = completeTradeMethod.invoke(api, fromId, toId, slotUid, tradeId);
            Method transferred = result.getClass().getMethod("getTransferred");
            Object out = transferred.invoke(result);
            return out instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static ItemStack getTradePreviewItem(UUID ownerId, UUID slotUid, Player viewer) {
        if (!isAvailable() || getTradePreviewItemMethod == null) return null;
        try {
            Object result = getTradePreviewItemMethod.invoke(api, ownerId, slotUid, viewer);
            return result instanceof ItemStack stack ? stack : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void registerSelectionListener() {
        try {
            Class<?> eventClass = Class.forName("me.levity.levityCosmetics.api.trade.event.CosmeticTradeSelectionEvent");
            Bukkit.getPluginManager().registerEvent(
                    eventClass.asSubclass(Event.class),
                    new Listener() {},
                    EventPriority.NORMAL,
                    (listener, event) -> handleSelection(event),
                    Bukkit.getPluginManager().getPlugin("AxTrade"),
                    false
            );
            Class<?> closedEventClass = Class.forName("me.levity.levityCosmetics.api.trade.event.CosmeticTradePickerClosedEvent");
            Bukkit.getPluginManager().registerEvent(
                    closedEventClass.asSubclass(Event.class),
                    new Listener() {},
                    EventPriority.NORMAL,
                    (listener, event) -> handlePickerClosed(event),
                    Bukkit.getPluginManager().getPlugin("AxTrade"),
                    false
            );
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[AxTrade] Could not register Levity selection listener: " + t.getMessage());
        }
    }

    private static void handleSelection(Event event) {
        try {
            Class<?> cls = event.getClass();
            UUID playerId = (UUID) cls.getMethod("getPlayerId").invoke(event);
            String tradeId = (String) cls.getMethod("getTradeId").invoke(event);
            UUID slotUid = (UUID) cls.getMethod("getSlotUid").invoke(event);
            String cosmeticId = (String) cls.getMethod("getCosmeticId").invoke(event);
            String cosmeticType = String.valueOf(cls.getMethod("getCosmeticType").invoke(event));
            String cosmeticName = (String) cls.getMethod("getCosmeticName").invoke(event);

            Player player = Bukkit.getPlayer(playerId);
            if (player == null) return;
            Trade trade = Trades.getTrade(player);
            if (trade == null) return;
            if (!trade.getTradeId().equals(tradeId)) return;

            TradePlayer tradePlayer = trade.getTradePlayer(playerId);
            if (tradePlayer == null) return;

            TradeCosmeticOffer previous = tradePlayer.getCosmeticOffer();
            Integer selectedSlot = tradePlayer.getCosmeticSlot();
            if (previous != null && !previous.slotUid().equals(slotUid)) {
                unlockTrade(playerId, previous.slotUid(), tradeId);
                selectedSlot = null;
            }

            if (selectedSlot == null) {
                int first = tradePlayer.getTradeGui().firstEmptyOwnSlotForCosmetic();
                if (first < 0) {
                    MESSAGEUTILS.sendLang(player, "trade.inventory-full");
                    tradePlayer.getTradeGui().returnFromExternalPicker();
                    return;
                }
                selectedSlot = first;
            }

            if (!lockForTrade(playerId, slotUid, tradeId)) {
                MESSAGEUTILS.sendLang(player, "trade.cosmetic-lock-failed");
                tradePlayer.getTradeGui().returnFromExternalPicker();
                return;
            }

            ItemStack preview = getTradePreviewItem(playerId, slotUid, player);
            tradePlayer.setCosmeticOffer(new TradeCosmeticOffer(slotUid, cosmeticId, cosmeticType, cosmeticName, preview));
            tradePlayer.setCosmeticSlot(selectedSlot);
            tradePlayer.cancel();
            trade.touchPrepTime();
            tradePlayer.getTradeGui().returnFromExternalPicker();
            trade.update();
            MESSAGEUTILS.sendLang(player, "trade.cosmetic-selected");
        } catch (Throwable ignored) {
        }
    }

    private static void handlePickerClosed(Event event) {
        try {
            Class<?> cls = event.getClass();
            UUID playerId = (UUID) cls.getMethod("getPlayerId").invoke(event);
            String tradeId = (String) cls.getMethod("getTradeId").invoke(event);

            Player player = Bukkit.getPlayer(playerId);
            if (player == null) return;
            Trade trade = Trades.getTrade(player);
            if (trade == null) return;
            if (!trade.getTradeId().equals(tradeId)) return;

            TradePlayer tradePlayer = trade.getTradePlayer(playerId);
            if (tradePlayer == null) return;
            tradePlayer.getTradeGui().returnFromExternalPicker();
        } catch (Throwable ignored) {
        }
    }
}
