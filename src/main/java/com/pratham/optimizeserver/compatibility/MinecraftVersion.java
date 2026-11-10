package com.pratham.optimizeserver.compatibility;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable representation of a Minecraft Java Edition version.
 * Supports historical (1.8.8+), current (1.20.x, 1.21.x), and future versioning formats.
 */
public final class MinecraftVersion implements Comparable<MinecraftVersion> {

    private static final Pattern MC_PATTERN = Pattern.compile("\\(MC:\\s*(\\d+)\\.(\\d+)(?:\\.(\\d+))?[^)]*\\)");
    private static final Pattern BUKKIT_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-R\\d+\\.\\d+)?");

    private final int major;
    private final int minor;
    private final int patch;
    private final String rawVersion;

    public MinecraftVersion(int major, int minor, int patch, String rawVersion) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.rawVersion = rawVersion != null ? rawVersion : (major + "." + minor + "." + patch);
    }

    public static MinecraftVersion parse(String serverVersion, String bukkitVersion) {
        if (serverVersion != null) {
            Matcher mcMatcher = MC_PATTERN.matcher(serverVersion);
            if (mcMatcher.find()) {
                int maj = parseSafe(mcMatcher.group(1), 1);
                int min = parseSafe(mcMatcher.group(2), 0);
                int pat = parseSafe(mcMatcher.group(3), 0);
                return new MinecraftVersion(maj, min, pat, serverVersion);
            }
        }
        String candidate = bukkitVersion != null && !bukkitVersion.isEmpty() ? bukkitVersion : serverVersion;
        if (candidate != null) {
            Matcher bukkitMatcher = BUKKIT_PATTERN.matcher(candidate);
            if (bukkitMatcher.find()) {
                int maj = parseSafe(bukkitMatcher.group(1), 1);
                int min = parseSafe(bukkitMatcher.group(2), 0);
                int pat = parseSafe(bukkitMatcher.group(3), 0);
                return new MinecraftVersion(maj, min, pat, candidate);
            }
        }
        return new MinecraftVersion(1, 0, 0, "unknown");
    }

    private static int parseSafe(String text, int fallback) {
        if (text == null || text.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getPatch() {
        return patch;
    }

    public String getRawVersion() {
        return rawVersion;
    }

    public boolean isAtLeast(int targetMajor, int targetMinor, int targetPatch) {
        if (this.major != targetMajor) {
            return this.major > targetMajor;
        }
        if (this.minor != targetMinor) {
            return this.minor > targetMinor;
        }
        return this.patch >= targetPatch;
    }

    public boolean isLegacyPre113() {
        return this.major == 1 && this.minor < 13;
    }

    /**
     * Returns the minimum Java feature release required by vanilla Minecraft for this version.
     */
    public int getMinimumRequiredJavaRelease() {
        if (this.major > 1) {
            return 21;
        }
        if (this.minor > 20 || (this.minor == 20 && this.patch >= 5)) {
            return 21;
        }
        if (this.minor >= 18) {
            return 17;
        }
        if (this.minor == 17) {
            return 16;
        }
        return 8;
    }

    public String toShortString() {
        return patch > 0 ? (major + "." + minor + "." + patch) : (major + "." + minor);
    }

    @Override
    public int compareTo(MinecraftVersion other) {
        if (this.major != other.major) {
            return Integer.compare(this.major, other.major);
        }
        if (this.minor != other.minor) {
            return Integer.compare(this.minor, other.minor);
        }
        return Integer.compare(this.patch, other.patch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MinecraftVersion)) return false;
        MinecraftVersion that = (MinecraftVersion) o;
        return major == that.major && minor == that.minor && patch == that.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    @Override
    public String toString() {
        return toShortString() + " (" + rawVersion + ")";
    }
}
