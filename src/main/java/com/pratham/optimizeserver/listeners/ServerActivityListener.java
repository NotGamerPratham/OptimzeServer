package com.pratham.optimizeserver.listeners;

import com.pratham.optimizeserver.monitoring.ChunkMonitor;
import com.pratham.optimizeserver.monitoring.EntityMonitor;
import com.pratham.optimizeserver.monitoring.NetworkMonitor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * Passive, read-only Bukkit event listener feeding chunk, entity, and network telemetry counters.
 * Intentionally excludes high-frequency PlayerMoveEvent and PlayerInteractEvent dispatches so
 * 50+ active players generate near-zero listener overhead on the primary server thread.
 * Never cancels events or alters vanilla gameplay behaviour.
 */
public final class ServerActivityListener implements Listener {

    private final ChunkMonitor chunkMonitor;
    private final EntityMonitor entityMonitor;
    private final NetworkMonitor networkMonitor;
    private volatile boolean active = true;

    public ServerActivityListener(ChunkMonitor chunkMonitor,
                                  EntityMonitor entityMonitor,
                                  NetworkMonitor networkMonitor) {
        this.chunkMonitor = chunkMonitor;
        this.entityMonitor = entityMonitor;
        this.networkMonitor = networkMonitor;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return active;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!active) return;
        long start = System.nanoTime();
        if (chunkMonitor != null && event != null) {
            chunkMonitor.recordChunkLoad(event.isNewChunk());
        }
        if (networkMonitor != null) {
            networkMonitor.recordOutgoingTrafficEvent(System.nanoTime() - start);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (!active) return;
        if (chunkMonitor != null) {
            chunkMonitor.recordChunkUnload();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!active) return;
        if (entityMonitor != null && event != null) {
            entityMonitor.recordEntitySpawn(event.getEntityType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!active) return;
        if (entityMonitor != null && event != null) {
            entityMonitor.recordEntityRemove(event.getEntityType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!active || networkMonitor == null) return;
        long dur = System.nanoTime();
        networkMonitor.recordIncomingTrafficEvent(Math.max(1L, System.nanoTime() - dur));
        networkMonitor.recordOutgoingTrafficEvent(1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!active || networkMonitor == null) return;
        long start = System.nanoTime();
        networkMonitor.recordIncomingTrafficEvent(Math.max(1L, System.nanoTime() - start));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!active || networkMonitor == null) return;
        long start = System.nanoTime();
        networkMonitor.recordIncomingTrafficEvent(Math.max(1L, System.nanoTime() - start));
    }
}

