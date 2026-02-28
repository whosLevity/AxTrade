package com.artillexstudios.axtrade;

import com.artillexstudios.axapi.AxPlugin;
import com.artillexstudios.axapi.config.Config;
import com.artillexstudios.axapi.executor.ThreadedQueue;
import com.artillexstudios.axapi.libs.boostedyaml.dvs.versioning.BasicVersioning;
import com.artillexstudios.axapi.libs.boostedyaml.settings.dumper.DumperSettings;
import com.artillexstudios.axapi.libs.boostedyaml.settings.general.GeneralSettings;
import com.artillexstudios.axapi.libs.boostedyaml.settings.loader.LoaderSettings;
import com.artillexstudios.axapi.libs.boostedyaml.settings.updater.UpdaterSettings;
import com.artillexstudios.axapi.metrics.AxMetrics;
import com.artillexstudios.axapi.utils.MessageUtils;
import com.artillexstudios.axapi.utils.StringUtils;
import com.artillexstudios.axapi.utils.featureflags.FeatureFlags;
import com.artillexstudios.axtrade.commands.CommandManager;
import com.artillexstudios.axtrade.hooks.HookManager;
import com.artillexstudios.axtrade.lang.LanguageManager;
import com.artillexstudios.axtrade.listeners.EntityInteractListener;
import com.artillexstudios.axtrade.listeners.TradeListeners;
import com.artillexstudios.axtrade.safety.SafetyManager;
import com.artillexstudios.axtrade.hooks.other.LevityCosmeticsBridge;
import com.artillexstudios.axtrade.trade.TradeTicker;
import com.artillexstudios.axtrade.utils.NumberUtils;
import com.artillexstudios.axtrade.utils.UpdateNotifier;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;

import java.io.File;
import java.util.logging.Level;

public final class AxTrade extends AxPlugin {
    public static Config CONFIG;
    public static Config LANG;
    public static Config GUIS;
    public static Config HOOKS;
    public static Config TOGGLED;
    public static MessageUtils MESSAGEUTILS;
    private static AxPlugin instance;
    private static ThreadedQueue<Runnable> threadedQueue;
    private static AxMetrics metrics;

    public static ThreadedQueue<Runnable> getThreadedQueue() {
        return threadedQueue;
    }

    public static AxPlugin getInstance() {
        return instance;
    }

    public void enable() {
        instance = this;

        new Metrics(this, 21500);

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            Bukkit.getLogger().warning("[AxTrade] Failed to create plugin data folder: " + getDataFolder());
        }
        ensureResource("config.yml");
        ensureResource("guis.yml");
        ensureResource("lang.yml");
        ensureResource("currencies.yml");
        ensureResource("toggled.yml");

        CONFIG = loadConfigWithRecovery("config.yml", true, true);
        GUIS = loadConfigWithRecovery("guis.yml", true, true);
        LANG = loadConfigWithRecovery("lang.yml", true, true);
        HOOKS = loadConfigWithRecovery("currencies.yml", true, true);
        TOGGLED = loadConfigWithRecovery("toggled.yml", false, false);

        LanguageManager.reload();

        MESSAGEUTILS = new MessageUtils(LANG.getBackingDocument(), "prefix", CONFIG.getBackingDocument());

        threadedQueue = new ThreadedQueue<>("AxTrade-Datastore-thread");

        getServer().getPluginManager().registerEvents(new EntityInteractListener(), this);
        getServer().getPluginManager().registerEvents(new TradeListeners(), this);

        HookManager.setupHooks();
        LevityCosmeticsBridge.setup();
        NumberUtils.reload();

        TradeTicker.start();
        SafetyManager.start();

        CommandManager.load();

        Bukkit.getConsoleSender().sendMessage(StringUtils.formatToString("&#00FFDD[AxTrade] Loaded plugin!"));

        metrics = new AxMetrics(this, 8);
        metrics.start();

        if (CONFIG.getBoolean("update-notifier.enabled", true)) new UpdateNotifier(this, 5943);
    }

    private void ensureResource(String path) {
        File out = new File(getDataFolder(), path);
        if (!out.exists()) {
            saveResource(path, false);
        }
    }

    private Config loadConfigWithRecovery(String path, boolean autoUpdate, boolean keepAll) {
        try {
            Config config = buildConfig(path, autoUpdate, keepAll);
            if (config.getBackingDocument() != null) return config;

            Bukkit.getLogger().warning("[AxTrade] Failed to load " + path + " (backing document is null). Restoring default file.");
        } catch (Throwable throwable) {
            Bukkit.getLogger().log(Level.WARNING, "[AxTrade] Failed to load " + path + ". Restoring default file.", throwable);
        }

        saveResource(path, true);
        Config recovered = buildConfig(path, autoUpdate, keepAll);
        if (recovered.getBackingDocument() == null) {
            throw new IllegalStateException("Unable to load recovered config: " + path);
        }
        return recovered;
    }

    private Config buildConfig(String path, boolean autoUpdate, boolean keepAll) {
        File file = new File(getDataFolder(), path);
        LoaderSettings loaderSettings = autoUpdate ? LoaderSettings.builder().setAutoUpdate(true).build() : LoaderSettings.DEFAULT;
        UpdaterSettings updaterSettings = autoUpdate
                ? UpdaterSettings.builder().setKeepAll(keepAll).setVersioning(new BasicVersioning("version")).build()
                : UpdaterSettings.DEFAULT;

        return new Config(
                file,
                getResource(path),
                GeneralSettings.builder().setUseDefaults(false).build(),
                loaderSettings,
                DumperSettings.DEFAULT,
                updaterSettings
        );
    }

    public void disable() {
        if (metrics != null) metrics.cancel();
        SafetyManager.stop();
    }

    public void updateFlags() {
        FeatureFlags.USE_LEGACY_HEX_FORMATTER.set(true);
        FeatureFlags.PLACEHOLDER_API_HOOK.set(true);
        FeatureFlags.PLACEHOLDER_API_IDENTIFIER.set("axtrade");
        FeatureFlags.ENABLE_PACKET_LISTENERS.set(true);
    }
}
