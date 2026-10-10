package com.mirukusora26.particleCreater;

import com.mirukusora26.particleCreater.command.PcCommand;
import com.mirukusora26.particleCreater.engine.EffectService;
import com.mirukusora26.particleCreater.engine.PlaybackManager;
import com.mirukusora26.particleCreater.skript.SkriptBridge;
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
    private boolean skriptRegistrationAttempted;

    @Override
    public void onEnable() {
        String stage="database initialization";
        try {
            repository = new EffectRepository(getDataFolder().toPath(), getLogger());
            stage="import directory creation";
            Files.createDirectories(getDataFolder().toPath().resolve("imports"));
            stage="effect loading and validation";
            effects = new EffectService(repository, getLogger());
            stage="playback scheduler registration";
            playback = new PlaybackManager(this, effects);
            effects.attachPlayback(playback);
            stage="service registration";
            Bukkit.getServicesManager().register(EffectService.class, effects, this, ServicePriority.Normal);
            stage="command registration";
            registerCommand("pc", new PcCommand(effects, playback, getLogger(), getDataFolder().toPath().resolve("imports")));
            var skript=Bukkit.getPluginManager().getPlugin("Skript");
            if(skript!=null&&skript.isEnabled()) {
                skriptRegistrationAttempted=true;
                try { SkriptBridge.register(this,effects); }
                catch(RuntimeException|LinkageError e) {
                    getLogger().log(Level.SEVERE,"[SKRIPT] Optional function registration failed; particleCreater remains enabled: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),e);
                    try { SkriptBridge.unregister(); }
                    catch(RuntimeException|LinkageError cleanup) { getLogger().log(Level.SEVERE,"[SKRIPT] Could not clean up functions after registration failure",cleanup); }
                }
            } else getLogger().info("[SKRIPT] Skript is not enabled; optional functions are unavailable.");
            getLogger().info("Loaded " + effects.names().size() + " particle effects");
        } catch (SQLException | IOException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "[STARTUP] particleCreater failed during "+stage+": "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()), e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if(skriptRegistrationAttempted) {
            try {SkriptBridge.unregister();}
            catch(RuntimeException|LinkageError e) {getLogger().log(Level.SEVERE,"[SKRIPT] Could not unregister optional functions: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),e);}
            skriptRegistrationAttempted=false;
        }
        try { Bukkit.getServicesManager().unregisterAll(this); }
        catch (RuntimeException e) { getLogger().log(Level.SEVERE,"Could not unregister particle service",e); }
        if (playback != null) {
            try { playback.close(); }
            catch (RuntimeException e) { getLogger().log(Level.SEVERE,"Could not stop particle playback",e); }
        }
        if (repository != null) {
            try { repository.close(); }
            catch (SQLException e) { getLogger().log(Level.SEVERE,"Could not close effect database",e); }
        }
    }

    /** Public API entry point; also registered with Bukkit ServicesManager. */
    public EffectService effects() { return effects; }
}
