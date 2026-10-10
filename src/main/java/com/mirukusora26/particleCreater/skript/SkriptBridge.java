package com.mirukusora26.particleCreater.skript;

import ch.njol.skript.lang.function.Functions;
import com.mirukusora26.particleCreater.Main;
import com.mirukusora26.particleCreater.engine.EffectService;
import com.mirukusora26.particleCreater.engine.PlaybackManager;
import java.lang.ref.WeakReference;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.skriptlang.skript.addon.SkriptAddon;
import org.skriptlang.skript.common.function.DefaultFunction;
import org.skriptlang.skript.common.function.Parameter;

/** Optional Skript integration. The main plugin must only load this class while Skript is enabled. */
public final class SkriptBridge {
    private static final List<DefaultFunction<?>> registered = new ArrayList<>();

    private SkriptBridge() {}

    public static void register(Main plugin, EffectService effects) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(effects, "effects");
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Skript functions must be registered on the server thread");
        if (!registered.isEmpty()) throw new IllegalStateException("Skript functions are already registered");

        Binding binding = new Binding(new WeakReference<>(plugin), new WeakReference<>(effects));
        SkriptAddon addon = ch.njol.skript.Skript.instance().registerAddon(Main.class, plugin.getName());
        // Skript bypasses the executor for unset mandatory values. Optional parser parameters
        // let our validators log the missing argument and return the documented failure value.
        List<DefaultFunction<?>> functions = List.of(
            DefaultFunction.builder(addon, "pcPlay", String.class)
                .description("Start a saved particle effect at a location and return its run ID.")
                .examples("set {_run} to pcPlay(\"ring\", location of player)")
                .since("particleCreater 0.0.3")
                .parameter("name", String.class, Parameter.Modifier.OPTIONAL)
                .parameter("location", Location.class, Parameter.Modifier.OPTIONAL)
                .build(args -> binding.call("pcPlay", () -> "effect='" + safe(args.get("name")) + "' at " + position(args.get("location")), null, service -> {
                    String name = requireText(args.get("name"), "effect name");
                    Location location = requireLocation(args.get("location"));
                    return service.play(name, PlaybackManager.Context.at(location)).id();
                })),
            DefaultFunction.builder(addon, "pcSpawn", Boolean.class)
                .description("Spawn one frame of a saved particle effect at a location.")
                .examples("set {_ok} to pcSpawn(\"ring\", location of player)")
                .since("particleCreater 0.0.3")
                .parameter("name", String.class, Parameter.Modifier.OPTIONAL)
                .parameter("location", Location.class, Parameter.Modifier.OPTIONAL)
                .build(args -> binding.call("pcSpawn", () -> "effect='" + safe(args.get("name")) + "' at " + position(args.get("location")), false, service -> {
                    String name = requireText(args.get("name"), "effect name");
                    Location location = requireLocation(args.get("location"));
                    service.spawn(name, PlaybackManager.Context.at(location));
                    return true;
                })),
            DefaultFunction.builder(addon, "pcStop", Boolean.class)
                .description("Stop a particle playback run by its ID.")
                .examples("set {_ok} to pcStop({_run})")
                .since("particleCreater 0.0.3")
                .parameter("runId", String.class, Parameter.Modifier.OPTIONAL)
                .build(args -> binding.call("pcStop", () -> "runId='" + safe(args.get("runId")) + "'", false, service -> {
                    String id = requireText(args.get("runId"), "run ID");
                    if (!service.stop(id)) throw new RunNotFoundException(id);
                    return true;
                })),
            DefaultFunction.builder(addon, "pcPause", Boolean.class)
                .description("Pause a particle playback run by its ID.")
                .examples("set {_ok} to pcPause({_run})")
                .since("particleCreater 0.0.3")
                .parameter("runId", String.class, Parameter.Modifier.OPTIONAL)
                .build(args -> binding.call("pcPause", () -> "runId='" + safe(args.get("runId")) + "'", false, service -> {
                    String id = requireText(args.get("runId"), "run ID");
                    if (!service.pause(id)) throw new RunNotFoundException(id);
                    return true;
                })),
            DefaultFunction.builder(addon, "pcResume", Boolean.class)
                .description("Resume a paused particle playback run by its ID.")
                .examples("set {_ok} to pcResume({_run})")
                .since("particleCreater 0.0.3")
                .parameter("runId", String.class, Parameter.Modifier.OPTIONAL)
                .build(args -> binding.call("pcResume", () -> "runId='" + safe(args.get("runId")) + "'", false, service -> {
                    String id = requireText(args.get("runId"), "run ID");
                    if (!service.resume(id)) throw new RunNotFoundException(id);
                    return true;
                })),
            DefaultFunction.builder(addon, "pcLocation", Location.class)
                .description("Find a saved particle location by name.")
                .examples("set {_place} to pcLocation(\"spawn_gate\")")
                .since("particleCreater 0.0.3")
                .parameter("name", String.class, Parameter.Modifier.OPTIONAL)
                .build(args -> binding.call("pcLocation", () -> "savedLocation='" + safe(args.get("name")) + "'", null, service ->
                    service.resolveLocation(requireText(args.get("name"), "saved location name"))))
        );

        for (DefaultFunction<?> function : functions) {
            if (Functions.getGlobalSignature(function.name()) != null) {
                throw new IllegalStateException("Skript function name is already registered: " + function.name());
            }
        }
        List<DefaultFunction<?>> completed = new ArrayList<>();
        try {
            for (DefaultFunction<?> function : functions) {
                completed.add(function);
                Functions.register(function);
            }
            registered.addAll(completed);
        } catch (RuntimeException | LinkageError error) {
            rollback(completed, error);
            throw error;
        }
        plugin.getLogger().info("Registered 6 optional Skript particle functions");
    }

    public static void unregister() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Skript functions must be unregistered on the server thread");
        RuntimeException failure = null;
        for (int i = registered.size() - 1; i >= 0; i--) {
            try {
                remove(registered.get(i));
                registered.remove(i);
            }
            catch (RuntimeException | LinkageError error) {
                if (failure == null) failure = new IllegalStateException("One or more Skript functions could not be unregistered");
                failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private static void rollback(List<DefaultFunction<?>> completed, Throwable original) {
        for (int i = completed.size() - 1; i >= 0; i--) {
            try { remove(completed.get(i)); }
            catch (RuntimeException | LinkageError error) {
                registered.add(completed.get(i));
                original.addSuppressed(error);
            }
        }
    }

    private static void remove(DefaultFunction<?> function) {
        var current = Functions.getGlobalFunction(function.name());
        var signature = Functions.getGlobalSignature(function.name());
        if (current == null && signature == null) return;
        if (current != function || signature != function.signature()) {
            throw new IllegalStateException("Skript function registration changed before cleanup: " + function.name());
        }
        Functions.unregisterFunction(signature);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is missing or blank");
        return value;
    }

    private static Location requireLocation(Location location) {
        if (location == null || location.getWorld() == null) throw new IllegalArgumentException("location must have a loaded world");
        var loaded = Bukkit.getWorld(location.getWorld().getUID());
        if (loaded == null) throw new IllegalArgumentException("location world '" + safe(location.getWorld().getName()) + "' is not loaded");
        return new Location(loaded, location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private static String position(Location location) {
        if (location == null) return "location=null";
        return "world='" + safe(location.getWorld() == null ? null : location.getWorld().getName()) + "' "
            + String.format(Locale.ROOT, "x=%s y=%s z=%s", location.getX(), location.getY(), location.getZ());
    }

    private static String safe(Object value) {
        String source = String.valueOf(value);
        String cleaned = sanitize(source);
        return cleaned.length() <= 128 ? cleaned : cleaned.substring(0, 128) + "...";
    }

    private static String sanitize(String value) { return String.valueOf(value).replaceAll("[\\p{Cntrl}§]", "?"); }

    private static final class RunNotFoundException extends RuntimeException {
        private RunNotFoundException(String id) { super("Playback run ID '" + safe(id) + "' does not exist"); }
    }

    @FunctionalInterface
    private interface Action<T> { T run(EffectService service) throws SQLException; }

    private record Binding(WeakReference<Main> plugin, WeakReference<EffectService> effects) {
        private <T> T call(String function, Supplier<String> describe, T fallback, Action<T> action) {
            Main owner = plugin.get();
            String detail = "arguments unavailable";
            try {
                detail = describe.get();
                if (owner == null || !owner.isEnabled()) throw new IllegalStateException("particleCreater is disabled");
                if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Skript function must run on the server thread");
                EffectService service = effects.get();
                if (service == null) throw new IllegalStateException("particle service is unavailable");
                return action.run(service);
            } catch (SQLException error) {
                log(owner, Level.WARNING, "[SKRIPT][SQLITE] " + function + "(" + detail + ") failed: " + sanitize(error.getMessage()), error);
            } catch (RunNotFoundException error) {
                log(owner, Level.WARNING, "[SKRIPT][NOT_FOUND] " + function + "(" + detail + ") failed: " + sanitize(error.getMessage()), null);
            } catch (IllegalArgumentException error) {
                log(owner, Level.WARNING, "[SKRIPT][INPUT] " + function + "(" + detail + ") failed: " + sanitize(error.getMessage()), null);
            } catch (IllegalStateException error) {
                log(owner, Level.WARNING, "[SKRIPT][STATE] " + function + "(" + detail + ") failed: " + sanitize(error.getMessage()), null);
            } catch (RuntimeException error) {
                log(owner, Level.WARNING, "[SKRIPT][INTERNAL] " + function + "(" + detail + ") failed: " + sanitize(error.getMessage()), error);
            } catch (LinkageError error) {
                log(owner, Level.WARNING, "[SKRIPT][LINKAGE] " + function + "(" + detail + ") failed: " + sanitize(error.getMessage()), error);
            }
            return fallback;
        }
    }

    private static void log(Main plugin, Level level, String message, Throwable error) {
        java.util.logging.Logger logger = plugin == null ? Bukkit.getLogger() : plugin.getLogger();
        if (error == null) logger.log(level, message);
        else logger.log(level, message, error);
    }
}
