package com.moralityengine.analytics;

import com.moralityengine.MoralityEngine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Tracks every morality score change tagged with a {@link MoralityChangeReason}.
 *
 * <p>Persistence: each change is appended as a JSON line to {@code analytics.json}
 * inside the plugin's data folder. On startup the file is read back and aggregates
 * are rebuilt in memory, so data survives restarts. The file can be cleared with
 * {@link #reset()}, and a full CSV export is available via {@link #exportCsv()}.
 */
public class AnalyticsManager {

    private final MoralityEngine plugin;
    private final Path logFile;

    // Server-wide totals
    private final Map<MoralityChangeReason, Long>   serverCount  = new EnumMap<>(MoralityChangeReason.class);
    private final Map<MoralityChangeReason, Double> serverPoints = new EnumMap<>(MoralityChangeReason.class);

    // Per-player totals: UUID → reason → count / totalPoints
    private final Map<UUID, Map<MoralityChangeReason, Long>>   playerCount  = new HashMap<>();
    private final Map<UUID, Map<MoralityChangeReason, Double>> playerPoints = new HashMap<>();

    public AnalyticsManager(MoralityEngine plugin) {
        this.plugin  = plugin;
        this.logFile = plugin.getDataFolder().toPath().resolve("analytics.json");
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    /** Read existing log and rebuild in-memory aggregates. */
    public void load() {
        if (!Files.exists(logFile)) return;
        int loaded = 0, skipped = 0;
        try (BufferedReader reader = Files.newBufferedReader(logFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                MoralityChangeRecord record = MoralityChangeRecord.fromJsonLine(line);
                if (record != null) { ingest(record); loaded++; }
                else                skipped++;
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load analytics log: " + e.getMessage());
        }
        plugin.getLogger().info("Analytics: loaded " + loaded + " records"
                + (skipped > 0 ? " (" + skipped + " skipped)" : "") + ".");
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    public void record(UUID uuid, MoralityChangeReason reason, double delta, double newScore) {
        MoralityChangeRecord rec = new MoralityChangeRecord(uuid, reason, delta, newScore, System.currentTimeMillis());
        ingest(rec);
        append(rec);
    }

    private void ingest(MoralityChangeRecord rec) {
        serverCount .merge(rec.reason(), 1L,        Long::sum);
        serverPoints.merge(rec.reason(), rec.delta(), Double::sum);

        playerCount .computeIfAbsent(rec.uuid(), k -> new EnumMap<>(MoralityChangeReason.class))
                    .merge(rec.reason(), 1L,        Long::sum);
        playerPoints.computeIfAbsent(rec.uuid(), k -> new EnumMap<>(MoralityChangeReason.class))
                    .merge(rec.reason(), rec.delta(), Double::sum);
    }

    private void append(MoralityChangeRecord rec) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, rec.toJsonLine() + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to append analytics record: " + e.getMessage());
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Returns server-wide stats for every reason that has at least one event,
     * sorted by absolute total points descending.
     */
    public List<ReasonStat> getServerStats() {
        return buildStats(serverCount, serverPoints);
    }

    /**
     * Returns per-player stats for every reason that has at least one event for that player,
     * sorted by absolute total points descending.
     */
    public List<ReasonStat> getPlayerStats(UUID uuid) {
        return buildStats(
                playerCount .getOrDefault(uuid, Collections.emptyMap()),
                playerPoints.getOrDefault(uuid, Collections.emptyMap())
        );
    }

    private List<ReasonStat> buildStats(
            Map<MoralityChangeReason, Long>   counts,
            Map<MoralityChangeReason, Double> points
    ) {
        List<ReasonStat> stats = new ArrayList<>();
        for (MoralityChangeReason reason : MoralityChangeReason.values()) {
            long count = counts.getOrDefault(reason, 0L);
            if (count == 0) continue;
            stats.add(new ReasonStat(reason, count, points.getOrDefault(reason, 0.0)));
        }
        stats.sort(Comparator.comparingDouble(s -> -Math.abs(s.totalPoints())));
        return stats;
    }

    /** Net sum of all recorded morality point changes across all players and reasons. */
    public double getNetServerDrift() {
        return serverPoints.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    // ── Maintenance ───────────────────────────────────────────────────────────

    /** Clear all in-memory aggregates and delete the log file. */
    public void reset() {
        serverCount.clear();
        serverPoints.clear();
        playerCount.clear();
        playerPoints.clear();
        try {
            Files.deleteIfExists(logFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to delete analytics log: " + e.getMessage());
        }
    }

    /**
     * Write a CSV of every individual change record to the plugin data folder.
     * @return path of the written file
     */
    public Path exportCsv() throws IOException {
        Path export = plugin.getDataFolder().toPath()
                .resolve("analytics-export-" + System.currentTimeMillis() + ".csv");
        Files.createDirectories(export.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(export, StandardOpenOption.CREATE_NEW)) {
            writer.write("uuid,reason,delta,new_score,timestamp_ms");
            writer.newLine();
            if (Files.exists(logFile)) {
                try (BufferedReader reader = Files.newBufferedReader(logFile)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.strip();
                        if (line.isEmpty()) continue;
                        MoralityChangeRecord rec = MoralityChangeRecord.fromJsonLine(line);
                        if (rec == null) continue;
                        writer.write(String.format("%s,%s,%.4f,%.4f,%d",
                                rec.uuid(), rec.reason().name(), rec.delta(), rec.newScore(), rec.timestamp()));
                        writer.newLine();
                    }
                }
            }
        }
        return export;
    }

    // ── Data class ────────────────────────────────────────────────────────────

    public record ReasonStat(MoralityChangeReason reason, long count, double totalPoints) {
        public double avg() { return count == 0 ? 0.0 : totalPoints / count; }
    }
}
