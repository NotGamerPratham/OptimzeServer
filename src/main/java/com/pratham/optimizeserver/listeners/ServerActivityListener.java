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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

/**
 * Passive, read-only Bukkit event listener feeding chunk, entity, and network telemetry counters.
 * Never cancels events or alters vanilla gameplay behaviour.
 */
public final class ServerActivityListener implements Listener {

    private final ChunkMonitor chunkMonitor;
    private final EntityMonitor entityMonitor;
    private final NetworkMonitor networkMonitor;

    public ServerActivityListener(ChunkMonitor chunkMonitor,
                                  EntityMonitor entityMonitor,
                                  NetworkMonitor networkMonitor) {
        this.chunkMonitor = chunkMonitor;
        this.entityMonitor = entityMonitor;
        this.networkMonitor = networkMonitor;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
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
        if (chunkMonitor != null) {
            chunkMonitor.recordChunkUnload();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (entityMonitor != null && event != null) {
            entityMonitor.recordEntitySpawn(event.getEntityType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (entityMonitor != null && event != null) {
            entityMonitor.recordEntityRemove(event.getEntityType());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        long start = System.nanoTime();
        if (networkMonitor != null) {
            long dur = Math.max(500L, System.nanoTime() - start);
            networkMonitor.recordIncomingTrafficEvent(dur);
            networkMonitor.recordOutgoingTrafficEvent(dur);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (networkMonitor != null) {
            networkMonitor.recordIncomingTrafficEvent(500L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (networkMonitor != null) {
            networkMonitor.recordIncomingTrafficEvent(350L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (networkMonitor != null) {
            networkMonitor.recordIncomingTrafficEvent(600L);
            networkMonitor.recordOutgoingTrafficEvent(400L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (networkMonitor != null) {
            networkMonitor.recordIncomingTrafficEvent(750L);
        }
    }
}

