package com.artillexstudios.axtrade.trade;

import com.artillexstudios.axapi.gui.SignInput;
import com.artillexstudios.axapi.nms.NMSHandlers;
import com.artillexstudios.axapi.scheduler.Scheduler;
import com.artillexstudios.axapi.utils.Cooldown;
import com.artillexstudios.axapi.utils.StringUtils;
import com.artillexstudios.axtrade.hooks.HookManager;
import com.artillexstudios.axtrade.hooks.currency.CurrencyHook;
import com.artillexstudios.axtrade.hooks.other.LevityCosmeticsBridge;
import com.artillexstudios.axtrade.safety.SafetyManager;
import com.artillexstudios.axtrade.utils.BlacklistUtils;
import com.artillexstudios.axtrade.utils.NumberUtils;
import com.artillexstudios.axtrade.utils.ShulkerUtils;
import com.artillexstudios.axtrade.utils.TaxUtils;
import com.artillexstudios.axtrade.utils.Utils;
import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.StorageGui;
import net.kyori.adventure.text.Component;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.artillexstudios.axtrade.AxTrade.CONFIG;
import static com.artillexstudios.axtrade.AxTrade.GUIS;
import static com.artillexstudios.axtrade.AxTrade.LANG;
import static com.artillexstudios.axtrade.AxTrade.MESSAGEUTILS;

public class TradeGui extends GuiFrame {
    private static final Cooldown<Player> confirmCooldown = Cooldown.create();
    protected final Trade trade;
    private final TradePlayer player;
    protected final StorageGui gui;
    protected final List<Integer> slots = getSlots("own-slots");
    protected final List<Integer> otherSlots = getSlots("partner-slots");
    private String currentTitle = "";
    private boolean inSign = false;
    private boolean inCurrencyMenu = false;
    private long suppressAbortUntil = 0L;

    public TradeGui(@NotNull Trade trade, @NotNull TradePlayer player) {
        super(GUIS, player.getPlayer(), trade);
        this.trade = trade;
        this.player = player;
        this.gui = Gui.storage()
                .rows(GUIS.getInt("rows",6))
                .title(Component.empty())
                .disableItemDrop()
                .create();

        setGui(gui);
        gui.setDefaultTopClickAction(this::handleClickTop);
        gui.setDragAction(this::handleDrag);
        gui.setPlayerInventoryAction(this::handleClickBottom);
        gui.setCloseGuiAction(this::handleClose);

        if (trade.isEnded()) return;

        update();
        gui.open(player.getPlayer());
        updateTitle();
        opened = true;
    }

    public void update() {
        if (LevityCosmeticsBridge.isAvailable()) {
            super.createItem("own.cosmetic-item", event -> {
                event.setCancelled(true);
                player.cancel();
                trade.update();
                inSign = true;
                trade.prepTime = System.currentTimeMillis();
                event.getWhoClicked().closeInventory();
                boolean opened = LevityCosmeticsBridge.openTradePicker(player.getPlayer(), trade.getTradeId());
                if (!opened) {
                    inSign = false;
                    gui.open(player.getPlayer());
                }
            }, Map.of(
                    "%cosmetic%", player.getCosmeticOffer() == null ? LANG.getString("placeholders.waiting") : player.getCosmeticOffer().cosmeticName()
            ));
            super.createItem("partner.cosmetic-item", event -> event.setCancelled(true), Map.of(
                    "%cosmetic%", player.getOtherPlayer().getCosmeticOffer() == null ? LANG.getString("placeholders.waiting") : player.getOtherPlayer().getCosmeticOffer().cosmeticName()
            ));
        }

        if (player.hasConfirmed()) {
            super.createItem("own.confirm-item.slot", "own.confirm-item.cancel", event -> {
                event.setCancelled(true);
                if (confirmCooldown.hasCooldown(player.getPlayer())) return;
                confirmCooldown.addCooldown(player.getPlayer(), 50L);
                player.cancel();
                trade.update();
            }, Map.of(), CONFIG.getBoolean("static-accept-item-amount", true) ? 1 : player.getConfirmed());
        } else {
            super.createItem("own.confirm-item.slot", "own.confirm-item.accept", event -> {
                event.setCancelled(true);
                if (confirmCooldown.hasCooldown(player.getPlayer())) return;
                confirmCooldown.addCooldown(player.getPlayer(), 50L);
                player.confirm();
            }, Map.of());
        }

        if (player.getOtherPlayer().hasConfirmed()) {
            super.createItem("partner.confirm-item.slot", "partner.confirm-item.cancel", event -> {
                event.setCancelled(true);
            }, Map.of(
                    "%own-name%", player.getPlayer().getName(),
                    "%partner-name%", player.getOtherPlayer().getPlayer().getName()
            ), CONFIG.getBoolean("static-accept-item-amount", true) ? 1 : player.getOtherPlayer().getConfirmed());
        } else {
            super.createItem("partner.confirm-item.slot", "partner.confirm-item.accept", event -> {
                event.setCancelled(true);
            }, Map.of(
                    "%own-name%", player.getPlayer().getName(),
                    "%partner-name%", player.getOtherPlayer().getPlayer().getName()
            ));
        }

        boolean selectorEnabled = GUIS.getBoolean("own.currency-selector.enabled", false);
        if (selectorEnabled) {
            ItemStack ownSelector = buildCurrencySelectorButton("own.currency-selector", player);
            int ownSelectorSlot = GUIS.getInt("own.currency-selector.slot");
            GuiItem ownSelectorItem = new GuiItem(ownSelector, event -> {
                event.setCancelled(true);
                openCurrencySelector();
            });
            if (opened) gui.updateItem(ownSelectorSlot, ownSelectorItem);
            else gui.setItem(ownSelectorSlot, ownSelectorItem);

            ItemStack partnerSelector = buildCurrencySelectorButton("partner.currency-selector", player.getOtherPlayer());
            int partnerSelectorSlot = GUIS.getInt("partner.currency-selector.slot");
            GuiItem partnerSelectorItem = new GuiItem(partnerSelector, event -> event.setCancelled(true));
            if (opened) gui.updateItem(partnerSelectorSlot, partnerSelectorItem);
            else gui.setItem(partnerSelectorSlot, partnerSelectorItem);
        } else {
            for (String currencyItem : GUIS.getSection("own").getRoutesAsStrings(false)) {
                final String currencyStr = GUIS.getString("own." + currencyItem + ".currency", null);
                if (currencyStr == null) continue;

                CurrencyHook currencyHook = HookManager.getCurrencyHook(currencyStr);
                if (currencyHook == null) continue;
                super.createItem("own." + currencyItem, event -> {
                    handleCurrencyClick(currencyStr, event);
                }, Map.of(
                        "%amount%", NumberUtils.formatNumber(player.getCurrency(currencyStr)),
                        "%tax-amount%", NumberUtils.formatNumber(TaxUtils.getTotalAfterTax(player.getCurrency(currencyStr), currencyHook)),
                        "%tax-percent%", NumberUtils.formatNumber(TaxUtils.getTaxPercent(currencyHook).doubleValue()),
                        "%tax-fee%", NumberUtils.formatNumber(TaxUtils.getTotalTax(player.getCurrency(currencyStr), currencyHook))
                ));
            }

            for (String currencyItem : GUIS.getSection("partner").getRoutesAsStrings(false)) {
                final String currencyStr = GUIS.getString("partner." + currencyItem + ".currency", null);
                if (currencyStr == null) continue;

                CurrencyHook currencyHook = HookManager.getCurrencyHook(currencyStr);
                if (currencyHook == null) continue;
                super.createItem("partner." + currencyItem, event -> {
                    event.setCancelled(true);
                }, Map.of(
                        "%amount%", NumberUtils.formatNumber(player.getOtherPlayer().getCurrency(currencyStr)),
                        "%tax-amount%", NumberUtils.formatNumber(TaxUtils.getTotalAfterTax(player.getOtherPlayer().getCurrency(currencyStr), currencyHook)),
                        "%tax-percent%", NumberUtils.formatNumber(TaxUtils.getTaxPercent(currencyHook).doubleValue()),
                        "%tax-fee%", NumberUtils.formatNumber(TaxUtils.getTotalTax(player.getOtherPlayer().getCurrency(currencyStr), currencyHook))
                ));
            }
        }

        for (int slot : otherSlots) {
            gui.removeItem(slot);
        }

        if (!opened) return;

        List<ItemStack> otherItems = player.getOtherPlayer().getTradeGui().getItems(true);
        int n = 0;
        for (int slot : otherSlots) {
            if (n >= otherItems.size()) break;
            ItemStack mirrored = otherItems.get(n);
            if (mirrored != null) {
                gui.updateItem(slot, new GuiItem(mirrored, event -> event.setCancelled(true)));
            }
            n++;
        }

        renderCosmeticOffers();
    }

    private void handleClickTop(InventoryClickEvent event) {
        if (confirmCooldown.hasCooldown(player.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        ItemStack it = getItem(event);

        if (BlacklistUtils.isBlacklisted(it)) {
            event.setCancelled(true);
            MESSAGEUTILS.sendLang(player.getPlayer(), "trade.blacklisted-item");
            return;
        }

        // prevent move with number key
        if (event.getCurrentItem() == null && it != null && checkFull(event)) return;

        if (event.getCurrentItem() != null && event.getClick().isRightClick() && Tag.SHULKER_BOXES.isTagged(event.getCurrentItem().getType())) {
            handleShulkerClick(event);
            return;
        }

        if (!slots.contains(event.getSlot())) {
            event.setCancelled(true);
            if (event.getCursor() == null) return;
            player.getPlayer().getInventory().addItem(event.getCursor().clone());
            event.getCursor().setAmount(0);
            return;
        }

        if (isOwnCosmeticSlot(event.getSlot())) {
            event.setCancelled(true);
            if (event.getClick().isRightClick() || event.getClick().isShiftClick()) {
                if (LevityCosmeticsBridge.isAvailable() && player.getCosmeticOffer() != null) {
                    LevityCosmeticsBridge.unlockTrade(player.getPlayer().getUniqueId(), player.getCosmeticOffer().slotUid(), trade.getTradeId());
                }
                player.clearCosmeticOffer();
                player.cancel();
                Scheduler.get().run(task -> trade.update());
            }
            return;
        }

        player.cancel();
        Scheduler.get().run(scheduledTask -> trade.update());
    }

    private void handleClickBottom(InventoryClickEvent event) {
        ItemStack it = getItem(event);

        if (BlacklistUtils.isBlacklisted(it)) {
            event.setCancelled(true);
            MESSAGEUTILS.sendLang(player.getPlayer(), "trade.blacklisted-item");
            return;
        }

        if (event.getCurrentItem() != null) {
            if (checkFull(event)) return;

            if (event.isShiftClick() && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && !slots.contains(event.getView().getTopInventory().firstEmpty())) {
                event.setCancelled(true);
                for (int i : slots) {
                    if (isOwnCosmeticSlot(i)) continue;
                    if (gui.getInventory().getItem(i) == null) {
                        gui.getInventory().setItem(i, event.getCurrentItem().clone());
                        event.getCurrentItem().setAmount(0);
                        break;
                    }
                }
            }
        }

        player.cancel();
        Scheduler.get().run(scheduledTask -> trade.update());
    }

    private void handleDrag(InventoryDragEvent event) {
        boolean ownInv = true;
        for (int s : event.getRawSlots()) {
            if (s > 53) continue;
            ownInv = false;
            break;
        }

        Scheduler.get().run(scheduledTask -> trade.update());
        if (ownInv) return;

        if (!new HashSet<>(slots).containsAll(event.getInventorySlots())) {
            event.setCancelled(true);
            return;
        }

        if (player.getCosmeticOffer() != null && player.getCosmeticSlot() != null && event.getInventorySlots().contains(player.getCosmeticSlot())) {
            event.setCancelled(true);
            return;
        }

        player.cancel();
    }

    private void handleClose(InventoryCloseEvent event) {
        if (inSign || inCurrencyMenu) return;
        if (System.currentTimeMillis() < suppressAbortUntil) return;
        trade.abort();
    }

    private boolean checkFull(Cancellable event) {
        if (!CONFIG.getBoolean("prevent-adding-items-when-inventory-full", true)) return false;

        // get items in gui
        int filledSlots = getItems(false).size();
        // get how many empty inventory slots the other player has
        int emptySlots = player.getOtherPlayer().getEmptySlots();

        if (filledSlots >= emptySlots) {
            event.setCancelled(true);
            MESSAGEUTILS.sendLang(player.getPlayer(), "trade.inventory-full");
            return true;
        }
        return false;
    }

    private void handleCurrencyClick(String currencyStr, InventoryClickEvent event) {
        event.setCancelled(true);
        openCurrencyInput(currencyStr, false);
    }

    private void openCurrencyInput(String currencyStr, boolean returnToSelector) {
        if (!SafetyManager.CURRENCY_SELECTOR.get()) {
            MESSAGEUTILS.sendLang(player.getPlayer(), "safety");
            return;
        }
        player.cancel();
        trade.update();
        inSign = true;
        inCurrencyMenu = false;
        suppressAbortUntil = System.currentTimeMillis() + 2000L;
        trade.prepTime = System.currentTimeMillis();
        player.getPlayer().closeInventory();

        var lines = StringUtils.formatList(LANG.getStringList("currency-editor-sign"));
        lines.set(0, Component.empty());

        var sign = new SignInput.Builder().setLines(lines).setHandler((player1, result) -> {
            if (trade.isEnded()) return;
            trade.prepTime = System.currentTimeMillis();
            String am = result[0];
            TradePlayer.Result addResult = player.setCurrency(currencyStr, am);
            if (addResult == TradePlayer.Result.SUCCESS) {
                MESSAGEUTILS.sendLang(player1, "currency-editor.success");
            } else {
                switch (addResult) {
                    case NOT_ENOUGH_CURRENCY:
                        MESSAGEUTILS.sendLang(player1, "currency-editor.not-enough");
                        break;
                    default:
                        MESSAGEUTILS.sendLang(player1, "currency-editor.failed");
                        break;
                }
            }
            Scheduler.get().run(scheduledTask -> {
                if (trade.isEnded()) return;
                inSign = false;
                trade.update();
                if (returnToSelector) {
                    openCurrencySelectorGui();
                } else {
                    gui.open(player.getPlayer());
                    currentTitle = "";
                    updateTitle();
                }
            });
        }).build(player.getPlayer());
        sign.open();
    }

    private void openCurrencySelector() {
        if (!SafetyManager.CURRENCY_SELECTOR.get()) {
            MESSAGEUTILS.sendLang(player.getPlayer(), "safety");
            return;
        }
        if (inCurrencyMenu || inSign) {
            return;
        }
        inCurrencyMenu = true;
        inSign = false;
        suppressAbortUntil = System.currentTimeMillis() + 2000L;
        player.cancel();
        trade.update();
        trade.prepTime = System.currentTimeMillis();
        player.getPlayer().closeInventory();
        Scheduler.get().runLater(task -> {
            if (trade.isEnded()) return;
            openCurrencySelectorGui();
        }, 1);
    }

    private void openCurrencySelectorGui() {
        if (trade.isEnded()) return;

        String title = GUIS.getString("currency-selector.title", "&0Select Currency");
        int rows = Math.max(1, Math.min(6, GUIS.getInt("currency-selector.rows", 3)));
        StorageGui currencyGui = Gui.storage()
                .rows(rows)
                .title(StringUtils.format(title))
                .disableItemDrop()
                .create();
        int size = rows * 9;

        List<CurrencyHook> hooks = HookManager.getCurrency().stream().toList();
        List<Integer> slots = getSelectorSlots(size, hooks.size());
        for (int i = 0; i < hooks.size() && i < slots.size(); i++) {
            CurrencyHook hook = hooks.get(i);
            String key = "currency-selector.items." + hook.getName();
            CurrencyHook currencyHook = HookManager.getCurrencyHook(hook.getName());
            if (currencyHook == null) continue;

            ItemStack item = buildItem(key, Map.of(
                    "%currency%", hook.getName(),
                    "%amount%", NumberUtils.formatNumber(player.getCurrency(hook.getName())),
                    "%tax-amount%", NumberUtils.formatNumber(TaxUtils.getTotalAfterTax(player.getCurrency(hook.getName()), currencyHook)),
                    "%tax-percent%", NumberUtils.formatNumber(TaxUtils.getTaxPercent(currencyHook).doubleValue()),
                    "%tax-fee%", NumberUtils.formatNumber(TaxUtils.getTotalTax(player.getCurrency(hook.getName()), currencyHook))
            ));
            if (item.getType().isAir()) {
                item = new ItemStack(org.bukkit.Material.PAPER);
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(StringUtils.formatToString("&#00ffdd&l" + hook.getName()));
                    meta.setLore(List.of(
                            StringUtils.formatToString("&7Current offer: &f" + NumberUtils.formatNumber(player.getCurrency(hook.getName()))),
                            StringUtils.formatToString("&8Click to set amount")
                    ));
                    item.setItemMeta(meta);
                }
            }
            String currencyName = hook.getName();
            currencyGui.setItem(slots.get(i), new GuiItem(item, event -> {
                event.setCancelled(true);
                openCurrencyInput(currencyName, true);
            }));
        }

        String closeKey = "currency-selector.close-item";
        ItemStack closeItem = buildItem(closeKey, Map.of());
        if (!closeItem.getType().isAir()) {
            int closeSlot = Math.min(size - 1, Math.max(0, GUIS.getInt(closeKey + ".slot", size - 1)));
            currencyGui.setItem(closeSlot, new GuiItem(closeItem, event -> {
                event.setCancelled(true);
                inCurrencyMenu = false;
                inSign = false;
                player.getPlayer().closeInventory();
            }));
        }

        currencyGui.setCloseGuiAction(e -> {
            Scheduler.get().run(task -> {
                if (trade.isEnded()) return;
                if (inSign) return;
                inCurrencyMenu = false;
                gui.open(player.getPlayer());
                trade.update();
                currentTitle = "";
                updateTitle();
            });
        });

        currencyGui.open(player.getPlayer());
        inCurrencyMenu = true;
        trade.touchPrepTime();
    }

    private List<Integer> getSelectorSlots(int inventorySize, int count) {
        List<String> configured = GUIS.getStringList("currency-selector.slots");
        List<Integer> resolved = new ArrayList<>();
        for (String entry : configured) {
            if (entry == null || entry.isBlank()) continue;
            if (entry.contains("-")) {
                String[] split = entry.split("-");
                if (split.length != 2) continue;
                try {
                    int min = Integer.parseInt(split[0].trim());
                    int max = Integer.parseInt(split[1].trim());
                    for (int i = min; i <= max; i++) {
                        if (i >= 0 && i < inventorySize) resolved.add(i);
                    }
                } catch (NumberFormatException ignored) {
                }
            } else {
                try {
                    int slot = Integer.parseInt(entry.trim());
                    if (slot >= 0 && slot < inventorySize) resolved.add(slot);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (!resolved.isEmpty()) return resolved;

        int max = Math.min(count, inventorySize);
        return java.util.stream.IntStream.range(0, max).boxed().collect(Collectors.toList());
    }

    private String formatSelectedCurrencies(TradePlayer tradePlayer) {
        if (tradePlayer.getCurrencies().isEmpty()) {
            return "None";
        }
        return tradePlayer.getCurrencies().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getName()))
                .map(entry -> toTitleCase(entry.getKey().getName()) + ": " + NumberUtils.formatNumber(entry.getValue()))
                .collect(Collectors.joining(", "));
    }

    private ItemStack buildCurrencySelectorButton(String route, TradePlayer source) {
        ItemStack item = buildItem(route, Map.of(
                "%count%", String.valueOf(source.getCurrencies().size()),
                "%selected%", formatSelectedCurrencies(source)
        ));
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getLore() == null) return item;

        List<String> lore = new ArrayList<>();
        List<String> selectedLines = formatSelectedCurrencyLines(source);
        for (String line : meta.getLore()) {
            if (line.contains("%selected-lines%")) {
                lore.addAll(selectedLines);
                continue;
            }
            lore.add(line);
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private List<String> formatSelectedCurrencyLines(TradePlayer source) {
        if (source.getCurrencies().isEmpty()) {
            return List.of(StringUtils.formatToString(" &7- &fNone"));
        }
        return source.getCurrencies().entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().getName()))
                .map(entry -> StringUtils.formatToString(" &7- &f" + toTitleCase(entry.getKey().getName()) + ": &#00ffdd" + NumberUtils.formatNumber(entry.getValue())))
                .toList();
    }

    private String toTitleCase(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replace('_', ' ').replace('-', ' ');
        String[] parts = normalized.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            String lower = part.toLowerCase(Locale.ROOT);
            out.append(Character.toUpperCase(lower.charAt(0)));
            if (lower.length() > 1) out.append(lower.substring(1));
            if (i < parts.length - 1) out.append(' ');
        }
        return out.toString();
    }

    public void handleShulkerClick(InventoryClickEvent event) {
        event.setCancelled(true);
        player.cancel();
        trade.update();
        inSign = true;
        suppressAbortUntil = System.currentTimeMillis() + 2000L;
        trade.prepTime = System.currentTimeMillis();
        event.getWhoClicked().closeInventory();

        BaseGui shulkerGui = Gui.storage().rows(3).title(StringUtils.format(Utils.getFormattedItemName(event.getCurrentItem()))).disableAllInteractions().create();
        shulkerGui.getInventory().setContents(ShulkerUtils.getShulkerContents(event.getCurrentItem(), false));
        shulkerGui.setCloseGuiAction(e -> {
            Scheduler.get().runLaterAt(player.getPlayer().getLocation(), () -> {
                if (trade.isEnded()) return;
                trade.prepTime = System.currentTimeMillis();
                gui.open(player.getPlayer());
                inSign = false;
                trade.update();
                currentTitle = "";
                updateTitle();
            }, 1);
        });
        shulkerGui.open(player.getPlayer());
    }

    @Nullable
    private ItemStack getItem(InventoryClickEvent event) {
        if (event.getClickedInventory() != null) {
            if (event.getClick() == ClickType.SWAP_OFFHAND && event.getClickedInventory().getType() != InventoryType.PLAYER) {
                return player.getPlayer().getInventory().getItemInOffHand();
            }
            if (event.getClick() == ClickType.NUMBER_KEY) {
                // when using a number key, the game will move it from the another inventory, so use the opposite of the clicked inventory
                Inventory inventory = event.getClickedInventory().getType() == InventoryType.PLAYER ? event.getView().getTopInventory() : event.getView().getBottomInventory();
                return inventory.getItem(event.getHotbarButton());
            }
        }
        return event.getCurrentItem();
    }

    public List<ItemStack> getItems(boolean includeAir) {
        final List<ItemStack> items = new ArrayList<>();
        for (int slot : slots) {
            if (isOwnCosmeticSlot(slot)) {
                if (includeAir) {
                    items.add(null);
                }
                continue;
            }
            ItemStack item = gui.getInventory().getItem(slot);
            if (!includeAir && item == null) continue;
            items.add(item);
        }
        return items;
    }

    public void updateTitle() {
        String newTitle = GUIS.getString("title")
                .replace("%player%", player.getOtherPlayer().getPlayer().getName())
                .replace("%own-status%", player.hasConfirmed() ? LANG.getString("placeholders.ready") : LANG.getString("placeholders.waiting"))
                .replace("%partner-status%", player.getOtherPlayer().hasConfirmed() ? LANG.getString("placeholders.ready") : LANG.getString("placeholders.waiting"));

        // don't update title if it didn't change
        if (currentTitle.equals(newTitle)) return;
        this.currentTitle = newTitle;

        Scheduler.get().runLater(task -> {
            Inventory topInv = player.getPlayer().getOpenInventory().getTopInventory();
            if (topInv.equals(gui.getInventory())) {
                NMSHandlers.getNmsHandler().setTitle(player.getPlayer().getOpenInventory().getTopInventory(), StringUtils.format(newTitle));
            }
        }, 1);
    }

    public void returnFromExternalPicker() {
        Scheduler.get().run(task -> {
            if (trade.isEnded()) return;
            inSign = false;
            gui.open(player.getPlayer());
            currentTitle = "";
            updateTitle();
            trade.update();
        });
    }

    public int firstEmptyOwnSlotForCosmetic() {
        for (int slot : slots) {
            if (gui.getInventory().getItem(slot) == null) return slot;
        }
        return -1;
    }

    private boolean isOwnCosmeticSlot(int slot) {
        return player.getCosmeticOffer() != null && player.getCosmeticSlot() != null && player.getCosmeticSlot() == slot;
    }

    private void renderCosmeticOffers() {
        TradeCosmeticOffer ownOffer = player.getCosmeticOffer();
        if (ownOffer != null && player.getCosmeticSlot() != null) {
            gui.updateItem(player.getCosmeticSlot(), new GuiItem(buildCosmeticItem(ownOffer), event -> {
                event.setCancelled(true);
                if (event.getClick().isRightClick() || event.getClick().isShiftClick()) {
                    if (LevityCosmeticsBridge.isAvailable()) {
                        LevityCosmeticsBridge.unlockTrade(player.getPlayer().getUniqueId(), ownOffer.slotUid(), trade.getTradeId());
                    }
                    player.clearCosmeticOffer();
                    player.cancel();
                    Scheduler.get().run(task -> trade.update());
                }
            }));
        }

        TradeCosmeticOffer otherOffer = player.getOtherPlayer().getCosmeticOffer();
        Integer otherOwnSlot = player.getOtherPlayer().getCosmeticSlot();
        if (otherOffer != null && otherOwnSlot != null) {
            int idx = slots.indexOf(otherOwnSlot);
            if (idx >= 0 && idx < otherSlots.size()) {
                int partnerViewSlot = otherSlots.get(idx);
                gui.updateItem(partnerViewSlot, new GuiItem(buildCosmeticItem(otherOffer), event -> event.setCancelled(true)));
            }
        }
    }

    private ItemStack buildCosmeticItem(TradeCosmeticOffer offer) {
        ItemStack stack = offer.previewItem() == null
                ? new ItemStack(org.bukkit.Material.NETHER_STAR)
                : offer.previewItem().clone();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            lore.add(StringUtils.formatToString("&8"));
            lore.add(StringUtils.formatToString("&7Trade Cosmetic: &f" + offer.cosmeticName()));
            lore.add(StringUtils.formatToString("&7Type: &f" + offer.cosmeticType()));
            lore.add(StringUtils.formatToString("&8Right-click to remove"));
            meta.setLore(lore);
            if (offer.previewItem() == null || !meta.hasDisplayName()) {
                meta.setDisplayName(StringUtils.formatToString("&#00ffdd&lCosmetic: &f" + offer.cosmeticName()));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
