package com.artillexstudios.axtrade.trade;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public record TradeCosmeticOffer(
        UUID slotUid,
        String cosmeticId,
        String cosmeticType,
        String cosmeticName,
        ItemStack previewItem
) {
}
