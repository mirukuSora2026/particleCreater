package com.mirukusora26.particleCreater;

import com.mirukusora26.particleCreater.command.PcCommand;
import com.mirukusora26.particleCreater.engine.EffectService;
import com.mirukusora26.particleCreater.engine.PlaybackManager;
import com.mirukusora26.particleCreater.storage.EffectRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

public final class Main extends JavaPlugin {
    private EffectRepository repository;
    private EffectService effects;
    private PlaybackManager playback;

    @Override
    public void onEnable() {
        try {
            repository = new EffectRepository(getDataFolder().toPath(), getLogger());
            Files.createDirectories(getDataFolder().toPath().resolve("imports"));
            effects = new EffectService(repository, getLogger());
            playback = new PlaybackManager(this, effects);
            effects.attachPlayback(playback);
            Bukkit.getServicesManager().register(EffectService.class, effects, this, ServicePriority.Normal);
            registerCommand("pc", new PcCommand(effects, playback, getLogger(), getDataFolder().toPath().resolve("imports")));
            getLogger().info("Loaded " + effects.names().size() + " particle effects");
        } catch (SQLException | IOException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Could not start particleCreater", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        if (playback != null) playback.close();
        if (repository != null) {
            try { repository.close(); }
            catch (SQLException e) { getLogger().severe("Could not close effect database: " + e.getMessage()); }
        }
    }

    /** Public API entry point; also registered with Bukkit ServicesManager. */
    public EffectService effects() { return effects; }
}
