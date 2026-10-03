# particleCreater

Create, save, and play animated particle effects on a Paper server. Define effects with named shapes and particle layers, then start them from `/pc`, a command block, the console, or another plugin.

**Compatibility:** Paper 26.2, Java 25. This is a server-side plugin; players do not need a client mod. Other server versions have not been verified.

**Release status:** 0.0.2 is a preview build. The build and automated tests pass, but in-game command and visual behavior still need server testing.

## Install

1. Run [Paper 26.2](https://papermc.io/downloads/paper) with [Java 25](https://docs.papermc.io/paper/getting-started/).
2. Copy `build/libs/particleCreater-0.0.2.jar` into the server's `plugins/` directory. If downloading a release, use the main JAR, not the `-plain.jar` file.
3. Start the server and run `/pc help` as an operator.

The distributable JAR includes SQLite JDBC. No separate database server or client-side installation is required. Only OP players, the console, and command blocks can use `/pc`. Console playback commands need `at <world> <x> <y> <z>`, `at saved <name>`, or `at player <player>`.

## Quick start

Enter these commands one line at a time as an OP player:

```text
/pc create ring
/pc shape add ring circle basic circle radius=2
/pc layer add ring red dust circle
/pc layer data ring red color #ff0000
/pc layer data ring red size 1.5
/pc set ring duration 5s
/pc validate ring
/pc play ring
```

`/pc spawn ring` emits one frame. `/pc preview ring` runs a two-second preview visible only to the player who issued it. Use `/pc running` to find a run ID, `/pc stop <run-id>` to stop one run, or `/pc stopall ring` to stop every run of that effect.

## Commands

| Task | Commands |
| --- | --- |
| Discover | `/pc help [shape|layer|formula|play|location]`, `/pc shapes`, `/pc types [page]`, `/pc type <particle>` |
| Manage effects | `/pc create <name>`, `/pc list [page]`, `/pc info <name>`, `/pc copy <name> <new-name>`, `/pc rename <name> <new-name>`, `/pc delete <name>`, `/pc validate <name>` |
| Build shapes | `/pc shape list|info|add|text|image|set|point|transform|group|remove ...` |
| Build layers | `/pc layer list|info|add|set|data|remove ...`, `/pc mix <name> together|weighted` |
| Animate | `/pc set <name> duration|interval|anchor <value>`, `/pc motion set|clear <name> move|rotate|scale ...`, `/pc param list|set|remove ...` |
| Save positions | `/pc location save <name> [world] x y z`, `/pc location list [page]`, `/pc location info <name>`, `/pc location delete <name>` |
| Play | `/pc preview|spawn|play <name> [at [world] x y z|saved <name>|player <player>] [follow <entity>] [for <player>] [with key=value...]` |
| Control runs | `/pc running [name]`, `/pc pause|resume|stop <run-id>`, `/pc stopall <name>` |
| Move definitions | `/pc export <name>`, `/pc import <file.json> [new-name]` |

Names use lowercase letters, digits, `_`, and `-`. Tab completion suggests effect and location names, online players, shapes, layers, particle types, data keys, and relevant options. Run `/pc help shape`, `/pc help layer`, `/pc help formula`, `/pc help play`, or `/pc help location` for syntax details.

## Play at specific coordinates

OP players and command blocks can omit the world name to use their current world. Coordinates may be absolute or use `~` relative to the command source:

```text
/pc play ring at 100 70 -30
/pc spawn ring at ~ ~2 ~
/pc preview ring at world 100 70 -30
```

The console must specify a loaded world and absolute coordinates, for example `pc play ring at world 100 70 -30`. The same `at` option works with `play`, `spawn`, and player-only `preview`. An explicit `at` location requires the `fixed` anchor. `caller` and `target` anchors follow their entities during playback when `at` is omitted.

Coordinates must be finite and within ±30,000,000 blocks. Use `world:<name>` when a world name looks like a coordinate or conflicts with `saved` or `player`, for example `/pc play ring at world:saved 100 70 -30`.

## Saved positions and player positions

Saved positions are shared server-wide and survive restarts. Saving the same name again updates it. The world UUID is stored with its name; playback reports an error if that world is unloaded rather than switching to another world with the same name.

```text
/pc location save spawn_gate world 100 70 -30
/pc location save upper_gate ~ ~2 ~
/pc location list
/pc location info spawn_gate
/pc play ring at saved spawn_gate
/pc spawn ring at player Steve
/pc preview ring at player @p
/pc location delete upper_gate
```

`at player` takes the selected online player's position when the command runs. It must select exactly one player. To keep the effect following an entity, use `/pc set ring anchor target` and `/pc play ring follow Steve` instead. Playback and preview responses include the run ID and resolved world and coordinates; one-shot spawn responses include the resolved position. Console and command blocks can use saved and player positions too. `preview` remains available only to OP players.

## Shapes and formulas

`/pc shapes` lists basic shapes. They include point, line, polyline, circle, arc, ellipse, rectangle, polygon, star, spiral, heart, Bezier and spline curves, sphere, hemisphere, ellipsoid, box, cylinder, cone, pyramid, torus, capsule, helix, orbit, wave, and parabola. Use `/pc shape set <effect> <shape> fill outline|surface|solid` on area shapes, or `step <number>` for density.

Create an effect before adding shapes to it. Formula kinds include:

```text
/pc shape add <effect> <id> curve "cos(u)" "u/5" "sin(u)" umin=0 umax=12.56
/pc shape add <effect> <id> surface "sin(v)*cos(u)" "cos(v)" "sin(v)*sin(u)"
/pc shape add <effect> <id> equation "x^2+y^2+z^2=4"
/pc shape add <effect> <id> solid "x^2+y^2+z^2<=4"
/pc shape add <effect> <id> system "x^2+y^2+z^2=4" "z=0"
```

`curve4`, `surface4`, `equation4`, `solid4`, and `system4` add a fourth spatial coordinate `w`. The default 4D view is `w=0` sliced into 3D. Set `slice "sin(t)"` to animate the cross-section, or `view project` and `projection <x> <y> <z>` to display a bounded projection. For example:

```text
/pc create hyper_core
/pc shape add hyper_core bubble equation4 x^2+y^2+z^2+w^2=2.25
/pc shape set hyper_core bubble slice 1.2*sin(t*2)
/pc shape set hyper_core bubble step 0.35
/pc layer add hyper_core aura dust bubble
/pc layer data hyper_core aura color #7B5CFF
/pc validate hyper_core
/pc play hyper_core
```

`t` is elapsed seconds. Parametric coordinate formulas use `u`, `v`, `t`, and parameters registered with `/pc param set`. Implicit equations also use `x`, `y`, `z`, and `w`. A 4D `slice` formula may use `x`, `y`, `z`, `t`, and parameters, plus `u` and `v` for parametric shapes; it cannot refer to `w`, which the slice defines. 4D dimensions and projection view require a 4D formula shape. `/pc help formula` lists supported functions and operators. The parser accepts bounded, real-valued expressions; it does not execute Java or scripts, and it does not represent every possible mathematical expression. Equations and inequalities are sampled numerically within their configured bounds, so results are approximate.

Text and PNG images can become point shapes:

```text
/pc shape text <effect> <id> "Hello" [pixelSize]
/pc shape image <effect> <id> logo.png [pixelSize]
```

Put PNG files in `plugins/particleCreater/imports/`. Imported points are saved in the effect, so the original PNG is not needed for playback.

## Layers, movement, and playback

An effect may contain multiple named layers. `together` emits every active layer; `weighted` chooses one particle type per shape point based on layer weights and requires shared shape and timing. Layer settings include `count`, `offset`, `extra`, `start`, `interval`, `duration`, and `weight`. `/pc type <particle>` reports the data fields available to that Paper particle.

Effect duration accepts ticks (`100t`), seconds (`5s`), or `infinite`; finite times are limited to 0..72,000 ticks before each field's own validation. Motion is set with three expressions for translation, rotation, or scale:

```text
/pc motion set ring rotate "0" "t*45" "0"
/pc set ring anchor fixed
/pc play ring at world 0 70 0 for @a
```

The default anchor is `fixed`. `anchor caller` follows the executing entity; `anchor target` requires `follow <entity>` on invocation. Use `with key=value` after playback options to override a parameter already saved with `/pc param set` for one run. Individual runs can be paused, resumed, or stopped by ID.

## Storage and API

Definitions and saved positions are stored in `plugins/particleCreater/effects.db` using SQLite. Edits are saved immediately. Running instances end on server restart; saved definitions and positions remain. Stop the server before copying the database for a simple backup.

`/pc export <name>` writes an effect definition as JSON to `plugins/particleCreater/exports/`. To import, place a `name.json` file in that same `exports/` directory and run `/pc import name.json [new-name]`. Saved positions are stored in SQLite and are not part of an effect's JSON export. JSON is an interchange format; SQLite remains the live store.

Other plugins can load the public `EffectService` from Bukkit's `ServicesManager` or the plugin's `Main#effects()` method. API calls that interact with playback must run on the server thread:

```java
EffectService service = Bukkit.getServicesManager().load(EffectService.class);
PlaybackManager.Handle run = service.play("ring", PlaybackManager.Context.at(player.getLocation()));
PlaybackManager.Handle savedRun = service.play("ring", PlaybackManager.Context.at(service.resolveLocation("spawn_gate")));
service.stop(run.id());
```

To play from another plugin at a fixed position, pass `PlaybackManager.Context.at(new Location(world, 100, 70, -30))`.

`EffectService#get`, `save`, `update`, `validate`, `spawn`, and `play` expose the same definitions and engine used by `/pc`. `saveLocation`, `getLocation`, `locationNames`, and `deleteLocation` manage shared positions; `resolveLocation` returns a Bukkit `Location` after confirming its world is loaded. All `EffectService` calls must run on the server thread. `ShapeUtils`, `ShapeEngine`, and `Expression` are also public utility entry points. A definition is validated before playback. Expected input errors explain the field or formula position; unexpected run errors stop only that run and are logged.

## Error diagnostics

All plugin command feedback and server log messages are in English. Command failures include a category, the `/pc` command path, and the failing value or constraint. Categories include `FORMULA`, `SHAPE`, `LAYER`, `PARTICLE_DATA`, `LOCATION`, `VARIABLE`, `MOTION`, `PLAYBACK`, `NUMERIC`, `FILE`, `SQLITE`, and `INTERNAL`. For example: `[LAYER] /pc layer set ring glow offset failed: Layer 'glow' offset.y=NaN must be finite and within +/-100`.

`/pc validate <effect>` reports each issue on its own numbered line. Shape errors identify the shape, formula index, transform axis, point index, or bound when available. Layer errors identify the layer, field, value, and accepted range. Automatic playback failures stop the affected run and write a `PLAYBACK` warning with its effect name, run ID, age in ticks, anchor, origin, cause, and stack trace. Startup failures identify the failed initialization stage. SQLite and file failures retain their command context and stack trace in the server log.

## Build and current limits

Build the distributable JAR with `./gradlew clean test build`. The resulting `build/libs/particleCreater-0.0.2.jar` is the shaded plugin JAR. Automated tests cover expressions, command coordinates, shape sampling and point limits, particle data types, and SQLite persistence for effects and positions.

Definitions are limited to 32 parameters, 64 shapes, and 32 layers. Playback is limited to 64 active runs, 2,048 sampled points per run per frame, and a shared budget of 10,000 particles per tick. Area and formula sampling grids are capped at 100,000 candidate cells or points. Layer offsets and `extra` are limited to ±100. Dense shapes or excessive particle counts can be rejected. A formula must produce finite values. Particle `count=0` retains Paper's directional single-particle behavior. Visual output also depends on client particle settings and distance from the effect. An in-game Paper server smoke test is still required before treating the JAR as production-ready.

For a server smoke test, run `play`, `spawn`, and `preview` with absolute, relative, saved, and player positions from an OP account; run the supported forms from the console and a command block; restart the server to check that effects and positions persist; then try an unloaded saved world, an offline player, a mixed effect, and a dense 4D shape to check errors and budgets. Review the returned run ID and position against the observed effect.
