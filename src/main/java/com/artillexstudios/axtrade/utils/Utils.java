package com.artillexstudios.axtrade.utils;

import com.artillexstudios.axtrade.hooks.currency.CurrencyHook;
import com.artillexstudios.axtrade.lang.LanguageManager;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

import static com.artillexstudios.axtrade.AxTrade.GUIS;

public class Utils {

    @NotNull
    public static String getFormattedItemName(@NotNull ItemStack itemStack) {
        return (itemStack.getItemMeta() == null || itemStack.getItemMeta().getDisplayName().isBlank()) ? LanguageManager.getTranslated(itemStack.getType()) : itemStack.getItemMeta().getDisplayName().replace("§", "&");
    }

    @NotNull
    public static String getFormattedCurrency(@NotNull CurrencyHook currencyHook) {
        String guiName = GUIS.getString("currency-selector.items." + currencyHook.getName() + ".name", null);
        if (guiName != null && !guiName.isBlank()) {
            return guiName;
        }

        String configured = currencyHook.getSettings().getOrDefault("name", currencyHook.getName()).toString();
        return toTitleCase(configured);
    }

    @NotNull
    public static String getPlainCurrency(@NotNull CurrencyHook currencyHook) {
        return stripFormatting(getFormattedCurrency(currencyHook));
    }

    @NotNull
    public static String stripFormatting(@NotNull String input) {
        String out = input;
        out = out.replaceAll("(?i)&#[0-9a-f]{6}", "");
        out = out.replaceAll("(?i)&[0-9a-fk-or]", "");
        out = out.replaceAll("(?i)<#[0-9a-f]{6}>", "");
        out = out.replaceAll("(?i)</?gradient(:[^>]*)?>", "");
        out = out.replaceAll("(?i)</?(bold|b|italic|i|underlined|u|strikethrough|st|obfuscated|obf|reset|white|gray|grey|gold|green|red|blue|aqua|yellow|black|dark_[a-z_]+)>", "");
        return out;
    }

    @NotNull
    public static String toTitleCase(@NotNull String value) {
        if (value.isBlank()) return value;
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
}
