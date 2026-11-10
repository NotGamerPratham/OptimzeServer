package com.pratham.optimizeserver.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Formats paginated command outputs for both in-game administrators and server console.
 */
public final class CommandPagination {

    private CommandPagination() {
    }

    public static void sendPaginated(CommandSender sender,
                                     String title,
                                     List<String> lines,
                                     int requestedPage,
                                     int pageSize,
                                     String pageCommandHint) {
        if (sender == null || lines == null) {
            return;
        }
        int safePageSize = Math.max(1, pageSize);
        int totalItems = lines.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalItems / (double) safePageSize));
        int page = Math.max(1, Math.min(totalPages, requestedPage));

        sender.sendMessage(ChatColor.DARK_AQUA + "=== " + ChatColor.AQUA + title
                + ChatColor.GRAY + " (Page " + page + "/" + totalPages + ")" + ChatColor.DARK_AQUA + " ===");

        if (totalItems == 0) {
            sender.sendMessage(ChatColor.GRAY + "  (No entries to display)");
            return;
        }

        int startIdx = (page - 1) * safePageSize;
        int endIdx = Math.min(totalItems, startIdx + safePageSize);
        for (int i = startIdx; i < endIdx; i++) {
            sender.sendMessage(lines.get(i));
        }

        if (totalPages > 1 && page < totalPages && pageCommandHint != null) {
            sender.sendMessage(ChatColor.GRAY + "Use " + ChatColor.YELLOW + pageCommandHint + " " + (page + 1)
                    + ChatColor.GRAY + " for the next page.");
        }
    }
}
