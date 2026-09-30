# Quire

Quire is a fork of [Paper](https://github.com/PaperMC/Paper) (Minecraft 26.3) focused on parallelism and on
algorithmic reductions of work, without changing vanilla game logic and while keeping unmodified
Bukkit/Spigot/Paper plugins working.

Status: experimental.

## What is in it

- Parallel ticking (`-Dquire.parallel=true`, experimental): tile-coloured parallel ticking of entities,
  block entities and chunk random ticks. Workers escalate to exclusive execution when a task needs state
  outside its tile; plugin events and synchronous chunk loads run on the main thread in task order. A
  measured per-stage controller (serial or parallel) picks what is actually faster.
- Work elimination: events are not built when nothing listens, waypoint connections are indexed, hot tag
  lookups and chunk lookups are cached, block-stepping scratch sets are pooled.
- Memory:
  - collision face shapes, single-value section data, empty/uniform light and small list indexes are shared
    or allocated lazily;
  - chunk section block storage and idle light data can be kept in a random-access sparse encoding
    (`-Dquire.compressSections=sparse`), re-encoded off-thread after writes;
  - per-search pathfinding state and drained tick queues are released.

## Building

Requires JDK 25.

```
./gradlew applyAllPatches
./gradlew createPaperclipJar
```

The server jar is written to `quire-server/build/libs/`.

## Layout

This is a standard paperweight fork:

- `quire-server/minecraft-patches/features` - patches to the Minecraft sources (on top of Paper's).
- `quire-server/paper-patches/features` - patches to Paper's own server sources.
- `quire-server/src/main/java` - Quire's own sources (`dev.quire.*`).
- `quire-server/build.gradle.kts.patch`, `quire-api/build.gradle.kts.patch` - build script changes.

After editing the materialized sources, run `./gradlew rebuildAllServerPatches` (or the specific
`rebuild*Patches` task) to update the patch files.

## License

Patches are licensed under the same terms as the upstream files they modify (see Paper's licensing);
Quire's own sources are MIT unless stated otherwise.
