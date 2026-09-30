package dev.quire.stats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.event.Event;
import org.bukkit.plugin.RegisteredListener;

/**
 * Optional (-Dquire.eventStats=true) statistics on Bukkit event dispatch: how often each event is
 * fired, how often it actually has listeners, whether it fired inside world ticking, and time spent in
 * listeners. Used to measure how often parallel world ticking would have to hand work back to the
 * main thread for plugins.
 */
public final class EventStats {
    public static final boolean ENABLED = Boolean.getBoolean("quire.eventStats");

    /** Set by the server while a world is being ticked. */
    public static volatile boolean inLevelTick;

    private static final Map<Class<?>, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static volatile long startNanos = System.nanoTime();
    private static final LongAdder TICKS = new LongAdder();

    private EventStats() {
    }

    private static final class Entry {
        final LongAdder calls = new LongAdder();
        final LongAdder callsWithListeners = new LongAdder();
        final LongAdder callsWithListenersInLevelTick = new LongAdder();
        final LongAdder listenerNanos = new LongAdder();
        final Map<String, LongAdder> byPlugin = new ConcurrentHashMap<>();
    }

    public static void tick() {
        TICKS.increment();
    }

    public static void record(final Event event, final RegisteredListener[] listeners, final long listenerNanos) {
        final Entry e = ENTRIES.computeIfAbsent(event.getClass(), k -> new Entry());
        e.calls.increment();
        if (listeners.length > 0) {
            e.callsWithListeners.increment();
            if (inLevelTick) {
                e.callsWithListenersInLevelTick.increment();
            }
            e.listenerNanos.add(listenerNanos);
            for (final RegisteredListener l : listeners) {
                e.byPlugin.computeIfAbsent(l.getPlugin().getName(), k -> new LongAdder()).increment();
            }
        }
    }

    public static void reset() {
        ENTRIES.clear();
        TICKS.reset();
        startNanos = System.nanoTime();
    }

    public static void dump(final Path file) throws IOException {
        final long ticks = Math.max(1, TICKS.sum());
        final double seconds = (System.nanoTime() - startNanos) / 1e9;
        final List<Map.Entry<Class<?>, Entry>> list = new ArrayList<>(ENTRIES.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue().callsWithListeners.sum(), a.getValue().callsWithListeners.sum()));
        final StringBuilder sb = new StringBuilder();
        sb.append(String.format("ticks=%d seconds=%.1f%n", ticks, seconds));
        sb.append(String.format("%-48s %12s %14s %18s %12s  %s%n", "event", "calls/tick", "listened/tick", "inLevelTick/tick", "us/tick", "plugins"));
        for (final Map.Entry<Class<?>, Entry> me : list) {
            final Entry e = me.getValue();
            sb.append(String.format("%-48s %12.2f %14.2f %18.2f %12.1f  %s%n",
                me.getKey().getSimpleName(),
                e.calls.sum() / (double) ticks,
                e.callsWithListeners.sum() / (double) ticks,
                e.callsWithListenersInLevelTick.sum() / (double) ticks,
                e.listenerNanos.sum() / 1000.0 / ticks,
                e.byPlugin.keySet()));
        }
        Files.writeString(file, sb.toString());
    }
}
