package com.pratham.optimizeserver.analyzer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the auto-applicable {@code /os tune} suggestions into the server's own config files.
 *
 * <p>Safety rules:</p>
 * <ul>
 *     <li>Every touched file is copied to {@code plugins/OptimizeServer/backups/<timestamp>/} first,
 *     so {@link #restoreLatest(File, File)} can undo the whole batch.</li>
 *     <li>Edits are line-based, so comments, ordering, and formatting are preserved, and each file is
 *     written to a temp file and then moved into place.</li>
 *     <li>Only keys that already exist are changed; nothing is invented, and world data is never touched.</li>
 *     <li>Only suggestions produced by {@link ServerConfigAuditor} are applied, all of which are
 *     gameplay-neutral performance settings. Changes take effect after a server restart.</li>
 * </ul>
 * Must be called off the server thread (file I/O).
 */
public final class ServerConfigApplier {

    private static final Pattern YAML_KEY_LINE = Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+):(\\s*)([^#]*?)(\\s*#.*)?$");

    public static final class ApplyResult {
        private final List<String> applied;
        private final List<String> skipped;
        private final File backupDir;

        ApplyResult(List<String> applied, List<String> skipped, File backupDir) {
            this.applied = Collections.unmodifiableList(applied);
            this.skipped = Collections.unmodifiableList(skipped);
            this.backupDir = backupDir;
        }

        public List<String> getApplied() { return applied; }
        public List<String> getSkipped() { return skipped; }
        /** Backup folder for this batch, or {@code null} when nothing was written. */
        public File getBackupDir() { return backupDir; }
    }

    /**
     * The suggestions {@code /os tune apply} would write.
     */
    public static List<ServerConfigAuditor.TuningSuggestion> applicable(ServerConfigAuditor.AuditReport report) {
        List<ServerConfigAuditor.TuningSuggestion> out = new ArrayList<ServerConfigAuditor.TuningSuggestion>();
        if (report != null) {
            for (ServerConfigAuditor.TuningSuggestion s : report.getSuggestions()) {
                if (s.isAutoApplicable()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    public ApplyResult apply(File serverRoot,
                             List<ServerConfigAuditor.TuningSuggestion> suggestions,
                             File backupsRoot) {
        List<String> applied = new ArrayList<String>();
        List<String> skipped = new ArrayList<String>();
        Map<String, List<ServerConfigAuditor.TuningSuggestion>> byFile =
                new LinkedHashMap<String, List<ServerConfigAuditor.TuningSuggestion>>();
        for (ServerConfigAuditor.TuningSuggestion s : suggestions) {
            if (!s.isAutoApplicable()) {
                skipped.add(s.getFile() + " " + s.getKey() + ": not a config-file value (apply manually)");
                continue;
            }
            List<ServerConfigAuditor.TuningSuggestion> list = byFile.get(s.getFile());
            if (list == null) {
                list = new ArrayList<ServerConfigAuditor.TuningSuggestion>();
                byFile.put(s.getFile(), list);
            }
            list.add(s);
        }

        File backupDir = null;
        for (Map.Entry<String, List<ServerConfigAuditor.TuningSuggestion>> entry : byFile.entrySet()) {
            String relative = entry.getKey();
            File target = new File(serverRoot, relative.replace('/', File.separatorChar));
            if (!isInside(serverRoot, target) || !target.isFile()) {
                for (ServerConfigAuditor.TuningSuggestion s : entry.getValue()) {
                    skipped.add(relative + " " + s.getKey() + ": file not found");
                }
                continue;
            }
            try {
                String original = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
                String lineSeparator = original.contains("\r\n") ? "\r\n" : "\n";
                List<String> lines = Files.readAllLines(target.toPath(), StandardCharsets.UTF_8);
                List<String> changedKeys = new ArrayList<String>();
                for (ServerConfigAuditor.TuningSuggestion s : entry.getValue()) {
                    boolean ok = relative.endsWith(".properties")
                            ? setProperty(lines, s.getKey(), s.getApplyValue())
                            : setYamlValue(lines, s.getKey(), s.getApplyValue());
                    if (ok) {
                        changedKeys.add(s.getKey() + " = " + s.getApplyValue());
                    } else {
                        skipped.add(relative + " " + s.getKey() + ": key not present in file");
                    }
                }
                if (changedKeys.isEmpty()) {
                    continue;
                }
                if (backupDir == null) {
                    backupDir = new File(backupsRoot, new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()));
                }
                File backup = new File(backupDir, relative.replace('/', File.separatorChar));
                File parent = backup.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Cannot create backup folder " + parent);
                }
                Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                writeAtomically(target, lines, lineSeparator);
                for (String k : changedKeys) {
                    applied.add(relative + ": " + k);
                }
            } catch (IOException ex) {
                for (ServerConfigAuditor.TuningSuggestion s : entry.getValue()) {
                    skipped.add(relative + " " + s.getKey() + ": " + ex.getMessage());
                }
            }
        }
        return new ApplyResult(applied, skipped, backupDir);
    }

    /**
     * Copies every file from the most recent backup folder back into the server root.
     *
     * @return restored relative paths (empty when there is no backup)
     */
    public List<String> restoreLatest(File serverRoot, File backupsRoot) throws IOException {
        File latest = latestBackup(backupsRoot);
        List<String> restored = new ArrayList<String>();
        if (latest == null) {
            return restored;
        }
        restoreTree(latest, latest, serverRoot, restored);
        return restored;
    }

    public static File latestBackup(File backupsRoot) {
        File[] dirs = backupsRoot != null ? backupsRoot.listFiles() : null;
        if (dirs == null || dirs.length == 0) {
            return null;
        }
        Arrays.sort(dirs);
        for (int i = dirs.length - 1; i >= 0; i--) {
            if (dirs[i].isDirectory()) {
                return dirs[i];
            }
        }
        return null;
    }

    private void restoreTree(File base, File dir, File serverRoot, List<String> restored) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                restoreTree(base, child, serverRoot, restored);
                continue;
            }
            String relative = base.toPath().relativize(child.toPath()).toString();
            File target = new File(serverRoot, relative);
            if (!isInside(serverRoot, target)) {
                continue;
            }
            Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            restored.add(relative.replace(File.separatorChar, '/'));
        }
    }

    /**
     * Replaces {@code key=value} in a .properties file; keys that are absent are left alone.
     */
    static boolean setProperty(List<String> lines, String key, String value) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0 && line.substring(0, eq).trim().equals(key)) {
                lines.set(i, key + "=" + value);
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces the scalar value of a dotted YAML path (e.g. {@code chunk-gc.period-in-ticks}),
     * preserving indentation and trailing comments. Absent paths are left alone.
     */
    static boolean setYamlValue(List<String> lines, String dottedKey, String value) {
        String[] target = dottedKey.split("\\.");
        List<Integer> indents = new ArrayList<Integer>();
        List<String> path = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            Matcher m = YAML_KEY_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int indent = m.group(1).length();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            indents.add(Integer.valueOf(indent));
            path.add(m.group(2));
            if (path.size() == target.length && path.equals(Arrays.asList(target))) {
                String existing = m.group(4).trim();
                if (existing.isEmpty()) {
                    return false; // a section, not a scalar
                }
                String comment = m.group(5) != null ? m.group(5) : "";
                lines.set(i, m.group(1) + m.group(2) + ": " + value + comment);
                return true;
            }
        }
        return false;
    }

    private static void writeAtomically(File target, List<String> lines, String lineSeparator) throws IOException {
        File tmp = new File(target.getPath() + ".optimizeserver.tmp");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append(lineSeparator);
        }
        Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isInside(File root, File candidate) {
        try {
            return candidate.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
        } catch (IOException e) {
            return false;
        }
    }
}
