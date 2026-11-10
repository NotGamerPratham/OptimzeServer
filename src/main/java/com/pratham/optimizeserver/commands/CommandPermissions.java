package com.pratham.optimizeserver.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

/**
 * Defines all OptimizeServer permission nodes and enforces strict authorization checks.
 */
public final class CommandPermissions {

    public static final String ADMIN = "optimizeserver.admin";
    public static final String STATUS = "optimizeserver.status";
    public static final String DIAGNOSE = "optimizeserver.diagnose";
    public static final String OPTIMIZE = "optimizeserver.optimize";
    public static final String PROFILE = "optimizeserver.profile";
    public static final String CHUNKS = "optimizeserver.chunks";
    public static final String REPORT = "optimizeserver.report";
    public static final String RELOAD = "optimizeserver.reload";

    private CommandPermissions() {
    }

    public static boolean hasPermission(CommandSender sender, String node) {
        if (sender == null) {
            return false;
        }
        if (sender instanceof ConsoleCommandSender || sender.isOp()) {
            return true;
        }
        if (sender.hasPermission(ADMIN)) {
            return true;
        }
        return node != null && sender.hasPermission(node);
    }
}
