package com.destroystokyo.paper.event.server;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Test-only stand-in for Paper's ServerTickEndEvent (same name and accessor), so the
 * reflective PaperTickEventHook can be exercised without the Paper API on the classpath.
 */
public class ServerTickEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final double tickDuration;

    public ServerTickEndEvent(double tickDuration) {
        this.tickDuration = tickDuration;
    }

    public double getTickDuration() {
        return tickDuration;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
