# 0.0.3 runtime smoke test

Tested on 2026-10-10 with a headless local server on macOS arm64: Java 25.0.3, Minecraft 26.2, Paper **build 133** (API `26.2.build.133-stable`), particleCreater 0.0.3, and optionally Skript 2.16.2. The build declares Paper API `26.2.build.129-stable` and `compileOnly` Skript 2.16.2; `runServer` uses separate `run/base/` and `run/skript/` directories.

The local distributable artifact `build/libs/particleCreater-0.0.3.jar` had SHA-256 `f820084a429f72b66d77a068af626b6161c82cd42878cb561f6ade9e345fdd0b` when inspected. The [official Skript 2.16.2 release JAR](https://github.com/SkriptLang/Skript/releases/download/2.16.2/Skript-2.16.2.jar) downloaded by `runServer` had SHA-256 `14cf743ee2c7cdd014bc63993c9092965edec3bdecff93ae47626fdef5d23b10`. Paper builds are available from the [official Paper downloads page](https://papermc.io/downloads/paper).

## Recorded results

| Local log | Observed result |
| --- | --- |
| `build/reports/runtime/without-skript.log` | Plugin loaded with Skript absent; console commands created and validated `pc_base_smoke`, saved `pc_base_loc`, spawned a frame, started playback, and later reported no active runs. |
| `build/reports/runtime/without-skript-restart.log` | Clean restart loaded one saved effect. `pc_base_smoke` still had one shape, one layer, and a 40-tick duration; its saved location resolved. Validate, spawn, and play succeeded; no active runs remained after expiry. No `ERROR` or exception appears in this restart log. |
| `build/reports/runtime/with-skript-first.log` | Skript 2.16.2 and particleCreater loaded; six functions registered. `sk reload pc-smoke` succeeded. The console smoke script ended with `[PC_TEST] COMPLETE passed=41 failed=0`. |
| `build/reports/runtime/with-skript-restart.log` | Six functions registered again. Skript parsed the script at startup without errors; the saved effect had three shapes, two layers, a 40-tick duration, and `mix=together`. The saved location resolved. The same script ended with `[PC_TEST] COMPLETE passed=41 failed=0`. No `ERROR` or exception appears in either Skript log. |

The Skript script exercised `pcLocation`, `pcSpawn`, `pcPlay`, `pcPause`, `pcResume`, and `pcStop`. It checked a run ID, pause across 80 ticks, resume and stop before the 40 active ticks expired, natural expiry after 50 active ticks, repeated stop, unknown effect/location/run IDs, missing/blank/unset arguments, omitted/unset locations, and a saved location in an unloaded world. Expected failures returned unset or false and produced English `[SKRIPT][INPUT]` or `[SKRIPT][NOT_FOUND]` warnings with the function and cause. Both Skript runs stopped cleanly, with no active effects reported before shutdown.

The persisted Skript fixture contained a flame circle layer and a dust layer on a 4D equation shape (`x^2+y^2+z^2+w^2=2.25`) with `slice=sin(t)` and rotation `t*45`. The logs confirm that the definition validated and the functions executed against it; they do not establish what a client rendered.

The first `without-skript.log` includes Paper's `No key layers in MapLike[{}]` message during disposable world creation, before particleCreater loaded. The test's flat-world generator setup was corrected to normal generation; the later `without-skript-restart.log` has no such message. This was a test-world setup issue, not an observed particleCreater failure. Local `run/` data and `build/` logs are Git-ignored and are evidence from this checkout, not files included in a release.

## Reproduce

Build the shaded JAR, then start each server mode from the repository root. Review the [Minecraft EULA](https://aka.ms/MinecraftEULA) and set `eula=true` in the relevant `run/<mode>/eula.txt` before running the server past its first-start prompt.

```sh
./gradlew clean test build
./gradlew runServer --no-configuration-cache
./gradlew runServer -PwithSkript=true --no-configuration-cache
```

Use one server mode at a time. In the **base** server console, enter these commands without a leading slash, then stop and restart the base server and repeat `info`, `location info`, `validate`, `spawn`, and `play`:

```text
pc create pc_base_smoke
pc layer add pc_base_smoke spark flame origin
pc set pc_base_smoke duration 40t
pc validate pc_base_smoke
pc location save pc_base_loc world 0 70 0
pc spawn pc_base_smoke at saved pc_base_loc
pc play pc_base_smoke at saved pc_base_loc
pc info pc_base_smoke
pc location info pc_base_loc
pc running
```

In the **Skript** server console, create the fixture below. `pc create` supplies the default `origin` point shape. Stop the server before editing its SQLite file.

```text
pc create pc_skript_smoke
pc shape add pc_skript_smoke ring basic circle radius=2
pc shape add pc_skript_smoke bubble equation4 "x^2+y^2+z^2+w^2=2.25"
pc shape set pc_skript_smoke bubble slice "sin(t)"
pc shape set pc_skript_smoke bubble step 0.5
pc layer add pc_skript_smoke spark flame ring
pc layer add pc_skript_smoke aura dust bubble
pc layer data pc_skript_smoke aura color #7B5CFF
pc motion set pc_skript_smoke rotate 0 "t*45" 0
pc set pc_skript_smoke duration 40t
pc validate pc_skript_smoke
pc location save pc_skript_loc world 0 70 0
```

Seed a deliberately unloaded-world location while that server is stopped. This fixture is required for the unloaded-world diagnostic check; a missing row also returns unset but tests a different path.

```sh
sqlite3 run/skript/plugins/particleCreater/effects.db <<'SQL'
INSERT OR REPLACE INTO saved_locations
  (name, world_uuid, world_name, x, y, z, updated_at)
VALUES
  ('pc_unloaded_loc', '00000000-0000-0000-0000-000000000001',
   'pc_unloaded_test_world', 0, 70, 0,
   CAST(strftime('%s', 'now') AS INTEGER) * 1000);
SQL
cp src/test/skript/pc-smoke.sk run/skript/plugins/Skript/scripts/pc-smoke.sk
./gradlew runServer -PwithSkript=true --no-configuration-cache
```

Run `sk reload pc-smoke` and then `pcskriptcheck` in the server console. Confirm `COMPLETE passed=41 failed=0` and the unloaded-world warning naming `pc_unloaded_test_world`. Stop and restart the Skript server, run `pc info pc_skript_smoke`, `pc location info pc_skript_loc`, `pc validate pc_skript_smoke`, and `pcskriptcheck` again. The script source is [`src/test/skript/pc-smoke.sk`](../src/test/skript/pc-smoke.sk).

## Limits

This was a console-only, local headless smoke test. It did not verify player or command-block command paths, client-visible particles, other Paper/Skript versions, concurrent load, or production server behavior. A true `pcSpawn` result confirms the server emission call completed, not that a player saw particles.
