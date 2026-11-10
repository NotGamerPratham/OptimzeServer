package com.pratham.optimizeserver.integrations;

import com.pratham.optimizeserver.compatibility.CompatibilityReport;
import com.pratham.optimizeserver.config.ConfigManager;
import com.pratham.optimizeserver.integrations.chunky.ChunkyIntegration;
import com.pratham.optimizeserver.integrations.spark.SparkIntegration;
import com.pratham.optimizeserver.scheduler.SchedulerAdapter;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatically downloads, verifies, and installs Spark and Chunky into the server's plugins directory
 * when they are not already present or bundled by the server platform.
 *
 * Design guarantees:
 * - Never bundles third-party plugin code inside OptimizeServer.jar.
 * - Detects Paper-bundled Spark (MC 1.21+) and existing plugin JARs to prevent duplicate installations.
 * - Performs all network I/O and cryptographic SHA-512 / JAR archive integrity checks asynchronously.
 * - Loads downloaded plugins on the primary/global server thread where supported.
 */
public final class DependencyAutoInstaller {

    public static final String DEFAULT_SPARK_DOWNLOAD_URL = "https://api.spiget.org/v2/resources/57242/download";
    public static final String DEFAULT_CHUNKY_MODRINTH_API =
            "https://api.modrinth.com/v2/project/chunky/version?loaders=%5B%22bukkit%22,%22paper%22,%22spigot%22,%22folia%22%5D";
    public static final String DEFAULT_CHUNKY_FALLBACK_URL = "https://api.spiget.org/v2/resources/81534/download";

    private static final String USER_AGENT = "OptimizeServer/1.0.0 (by NotGamerPratham; Minecraft Server Plugin)";
    private static final Pattern MODRINTH_URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"(https://cdn\\.modrinth\\.com/[^\"]+\\.jar)\"");
    private static final Pattern MODRINTH_SHA512_PATTERN = Pattern.compile("\"sha512\"\\s*:\\s*\"([0-9a-fA-F]{128})\"");

    public static final class InstallResult {
        private final String dependencyName;
        private final boolean alreadyInstalled;
        private final boolean downloadedNow;
        private final File targetJarFile;
        private final String message;

        public InstallResult(String dependencyName,
                             boolean alreadyInstalled,
                             boolean downloadedNow,
                             File targetJarFile,
                             String message) {
            this.dependencyName = dependencyName;
            this.alreadyInstalled = alreadyInstalled;
            this.downloadedNow = downloadedNow;
            this.targetJarFile = targetJarFile;
            this.message = message;
        }

        public String getDependencyName() {
            return dependencyName;
        }

        public boolean isAlreadyInstalled() {
            return alreadyInstalled;
        }

        public boolean isDownloadedNow() {
            return downloadedNow;
        }

        public File getTargetJarFile() {
            return targetJarFile;
        }

        public String getMessage() {
            return message;
        }
    }

    private final Server server;
    private final File pluginsDirectory;
    private final ConfigManager configManager;
    private final CompatibilityReport compatibilityReport;
    private final SchedulerAdapter scheduler;
    private final SparkIntegration sparkIntegration;
    private final ChunkyIntegration chunkyIntegration;
    private final Logger logger;
    private final List<InstallResult> lastResults = new CopyOnWriteArrayList<InstallResult>();

    public DependencyAutoInstaller(Server server,
                                   File pluginsDirectory,
                                   ConfigManager configManager,
                                   CompatibilityReport compatibilityReport,
                                   SchedulerAdapter scheduler,
                                   SparkIntegration sparkIntegration,
                                   ChunkyIntegration chunkyIntegration,
                                   Logger logger) {
        this.server = server;
        this.pluginsDirectory = pluginsDirectory != null ? pluginsDirectory : new File("plugins");
        this.configManager = configManager;
        this.compatibilityReport = compatibilityReport;
        this.scheduler = scheduler;
        this.sparkIntegration = sparkIntegration;
        this.chunkyIntegration = chunkyIntegration;
        this.logger = logger != null ? logger : Logger.getLogger("OptimizeServer");
    }

    /**
     * Triggers non-blocking asynchronous check and installation of Spark and Chunky.
     */
    public void checkAndInstallMissingAsync() {
        if (configManager != null && !configManager.getBoolean("integrations.auto-install.enabled", true)) {
            logger.info("[OptimizeServer] Automatic installation of Spark/Chunky is disabled in config.yml.");
            return;
        }
        if (scheduler != null) {
            scheduler.runAsync(new Runnable() {
                @Override
                public void run() {
                    checkAndInstallNow();
                }
            });
        } else {
            checkAndInstallNow();
        }
    }

    /**
     * Checks and installs missing Spark and Chunky dependencies.
     * Safe to invoke from an asynchronous worker thread or directly in tests.
     */
    public synchronized List<InstallResult> checkAndInstallNow() {
        List<InstallResult> results = new ArrayList<InstallResult>();

        boolean autoSpark = configManager == null || configManager.getBoolean("integrations.spark.auto-install", true);
        if (autoSpark) {
            results.add(ensureSparkInstalled());
        }

        boolean autoChunky = configManager == null || configManager.getBoolean("integrations.chunky.auto-install", true);
        if (autoChunky) {
            results.add(ensureChunkyInstalled());
        }

        lastResults.clear();
        lastResults.addAll(results);
        return Collections.unmodifiableList(results);
    }

    private InstallResult ensureSparkInstalled() {
        if (sparkIntegration != null) {
            sparkIntegration.detect();
            if (sparkIntegration.isAvailable() || sparkIntegration.isBundledByPaper()) {
                return new InstallResult("spark", true, false, null,
                        "Spark is already active or bundled by the server platform.");
            }
        }
        if (compatibilityReport != null && compatibilityReport.getCapabilities().isSparkBundledByServer()) {
            return new InstallResult("spark", true, false, null,
                    "Spark is bundled natively by Paper 1.21+; skipping standalone download.");
        }
        File existingJar = findExistingJar("spark");
        if (existingJar != null) {
            tryLoadAndEnableOnMainThread(existingJar, "spark");
            return new InstallResult("spark", true, false, existingJar,
                    "Spark JAR already present at " + existingJar.getName() + ".");
        }

        String downloadUrl = configManager != null
                ? configManager.getString("integrations.spark.download-url", DEFAULT_SPARK_DOWNLOAD_URL)
                : DEFAULT_SPARK_DOWNLOAD_URL;
        File targetJar = new File(pluginsDirectory, "spark.jar");

        logger.info("[OptimizeServer] Spark profiler not found; auto-installing Spark to " + targetJar.getPath() + "...");
        boolean ok = downloadAndVerifyJar(downloadUrl, null, targetJar);
        if (ok) {
            logger.info("[OptimizeServer] Successfully downloaded and verified Spark (" + (targetJar.length() / 1024L) + " KB).");
            tryLoadAndEnableOnMainThread(targetJar, "spark");
            return new InstallResult("spark", false, true, targetJar,
                    "Auto-installed Spark to " + targetJar.getName() + ".");
        } else {
            String msg = "Could not auto-download Spark from " + downloadUrl + "; plugin will continue without Spark.";
            logger.warning("[OptimizeServer] " + msg);
            return new InstallResult("spark", false, false, null, msg);
        }
    }

    private InstallResult ensureChunkyInstalled() {
        if (chunkyIntegration != null) {
            chunkyIntegration.detect();
            if (chunkyIntegration.isAvailable()) {
                return new InstallResult("Chunky", true, false, null,
                        "Chunky is already loaded and active on this server.");
            }
        }
        File existingJar = findExistingJar("chunky");
        if (existingJar != null) {
            tryLoadAndEnableOnMainThread(existingJar, "Chunky");
            return new InstallResult("Chunky", true, false, existingJar,
                    "Chunky JAR already present at " + existingJar.getName() + ".");
        }

        File targetJar = new File(pluginsDirectory, "Chunky.jar");
        String configuredUrl = configManager != null
                ? configManager.getString("integrations.chunky.download-url", "")
                : "";

        String downloadUrl = configuredUrl;
        String expectedSha512 = null;

        if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
            ModrinthRelease release = resolveLatestModrinthJar(DEFAULT_CHUNKY_MODRINTH_API);
            if (release != null) {
                downloadUrl = release.downloadUrl;
                expectedSha512 = release.sha512;
            } else {
                downloadUrl = DEFAULT_CHUNKY_FALLBACK_URL;
            }
        }

        logger.info("[OptimizeServer] Chunky not found; auto-installing Chunky to " + targetJar.getPath() + "...");
        boolean ok = downloadAndVerifyJar(downloadUrl, expectedSha512, targetJar);
        if (!ok && !DEFAULT_CHUNKY_FALLBACK_URL.equals(downloadUrl) && (configuredUrl == null || configuredUrl.isEmpty())) {
            logger.info("[OptimizeServer] Retrying Chunky download via fallback endpoint...");
            ok = downloadAndVerifyJar(DEFAULT_CHUNKY_FALLBACK_URL, null, targetJar);
        }

        if (ok) {
            logger.info("[OptimizeServer] Successfully downloaded and verified Chunky (" + (targetJar.length() / 1024L) + " KB).");
            tryLoadAndEnableOnMainThread(targetJar, "Chunky");
            return new InstallResult("Chunky", false, true, targetJar,
                    "Auto-installed Chunky to " + targetJar.getName() + ".");
        } else {
            String msg = "Could not auto-download Chunky; plugin will continue without Chunky.";
            logger.warning("[OptimizeServer] " + msg);
            return new InstallResult("Chunky", false, false, null, msg);
        }
    }

    private File findExistingJar(String prefixLower) {
        if (!pluginsDirectory.exists() || !pluginsDirectory.isDirectory()) {
            return null;
        }
        File[] files = pluginsDirectory.listFiles();
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (f != null && f.isFile()) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith(prefixLower) && name.endsWith(".jar") && f.length() >= 256L) {
                    return f;
                }
            }
        }
        return null;
    }

    private ModrinthRelease resolveLatestModrinthJar(String apiEndpoint) {
        HttpURLConnection conn = null;
        try {
            int connectTimeout = configManager != null
                    ? configManager.getInt("integrations.auto-install.connect-timeout-ms", 10000)
                    : 10000;
            int readTimeout = configManager != null
                    ? configManager.getInt("integrations.auto-install.read-timeout-ms", 15000)
                    : 15000;

            URL url = new URL(apiEndpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);

            if (conn.getResponseCode() != 200) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            } finally {
                reader.close();
            }

            String json = sb.toString();
            Matcher urlMatcher = MODRINTH_URL_PATTERN.matcher(json);
            if (!urlMatcher.find()) {
                return null;
            }
            String jarUrl = urlMatcher.group(1);

            String sha512 = null;
            Matcher shaMatcher = MODRINTH_SHA512_PATTERN.matcher(json);
            if (shaMatcher.find()) {
                sha512 = shaMatcher.group(1);
            }
            return new ModrinthRelease(jarUrl, sha512);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private boolean downloadAndVerifyJar(String downloadUrl, String expectedSha512, File targetJar) {
        if (downloadUrl == null || downloadUrl.trim().isEmpty() || targetJar == null) {
            return false;
        }
        if (!pluginsDirectory.exists()) {
            pluginsDirectory.mkdirs();
        }
        File tempFile = new File(pluginsDirectory, targetJar.getName() + ".part");
        HttpURLConnection conn = null;
        try {
            int connectTimeout = configManager != null
                    ? configManager.getInt("integrations.auto-install.connect-timeout-ms", 10000)
                    : 10000;
            int readTimeout = configManager != null
                    ? configManager.getInt("integrations.auto-install.read-timeout-ms", 15000)
                    : 15000;

            URL url = new URL(downloadUrl);
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                copyStreamToFile(url.openStream(), tempFile);
            } else {
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setConnectTimeout(connectTimeout);
                conn.setReadTimeout(readTimeout);

                int status = conn.getResponseCode();
                if (status != 200) {
                    return false;
                }
                copyStreamToFile(conn.getInputStream(), tempFile);
            }

            boolean verifyHash = configManager == null
                    || configManager.getBoolean("integrations.auto-install.verify-sha512-checksum", true);
            if (!validateJarArchive(tempFile, verifyHash ? expectedSha512 : null)) {
                tempFile.delete();
                return false;
            }

            if (targetJar.exists()) {
                targetJar.delete();
            }
            if (!tempFile.renameTo(targetJar)) {
                copyStreamToFile(new FileInputStream(tempFile), targetJar);
                tempFile.delete();
            }
            return targetJar.exists() && targetJar.length() > 0L;
        } catch (Throwable t) {
            tempFile.delete();
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void copyStreamToFile(InputStream in, File dest) throws IOException {
        try {
            FileOutputStream out = new FileOutputStream(dest, false);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            if (in != null) {
                in.close();
            }
        }
    }

    public static boolean validateJarArchive(File file, String expectedSha512) {
        if (file == null || !file.exists() || file.length() < 256L) {
            return false;
        }
        if (expectedSha512 != null && !expectedSha512.trim().isEmpty()) {
            try {
                String actualSha512 = computeSha512Hex(file);
                if (!expectedSha512.trim().equalsIgnoreCase(actualSha512)) {
                    return false;
                }
            } catch (Throwable t) {
                return false;
            }
        }
        JarFile jar = null;
        try {
            jar = new JarFile(file);
            boolean hasDescriptor = jar.getEntry("plugin.yml") != null
                    || jar.getEntry("paper-plugin.yml") != null
                    || jar.getEntry("META-INF/MANIFEST.MF") != null;
            return hasDescriptor;
        } catch (Throwable t) {
            return false;
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static String computeSha512Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        FileInputStream fis = new FileInputStream(file);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        } finally {
            fis.close();
        }
        byte[] hashBytes = digest.digest();
        StringBuilder hex = new StringBuilder(hashBytes.length * 2);
        for (byte b : hashBytes) {
            hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return hex.toString();
    }

    private void tryLoadAndEnableOnMainThread(final File jarFile, final String pluginName) {
        boolean loadImmediately = configManager == null
                || configManager.getBoolean("integrations.auto-install.load-immediately-after-download", true);
        if (!loadImmediately || server == null || jarFile == null) {
            return;
        }
        Runnable loadTask = new Runnable() {
            @Override
            public void run() {
                try {
                    PluginManager pm = server.getPluginManager();
                    if (pm != null && pm.getPlugin(pluginName) == null) {
                        Plugin loaded = pm.loadPlugin(jarFile);
                        if (loaded != null) {
                            loaded.onLoad();
                            pm.enablePlugin(loaded);
                            logger.info("[OptimizeServer] Dynamically loaded and enabled auto-installed plugin: " + loaded.getName());
                        }
                    }
                } catch (Throwable t) {
                    logger.log(Level.INFO, "[OptimizeServer] " + pluginName + " JAR installed at "
                            + jarFile.getName() + " and will activate on next server restart (" + t.getMessage() + ").");
                } finally {
                    if (sparkIntegration != null) sparkIntegration.detect();
                    if (chunkyIntegration != null) chunkyIntegration.detect();
                }
            }
        };

        if (scheduler != null) {
            scheduler.runGlobal(loadTask);
        } else if (server.isPrimaryThread()) {
            loadTask.run();
        }
    }

    public List<InstallResult> getLastResults() {
        return Collections.unmodifiableList(new ArrayList<InstallResult>(lastResults));
    }

    private static final class ModrinthRelease {
        final String downloadUrl;
        final String sha512;

        ModrinthRelease(String downloadUrl, String sha512) {
            this.downloadUrl = downloadUrl;
            this.sha512 = sha512;
        }
    }
}
