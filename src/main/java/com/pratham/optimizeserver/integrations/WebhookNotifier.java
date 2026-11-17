package com.pratham.optimizeserver.integrations;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Posts OptimizeServer alerts to an admin-configured webhook (Discord-compatible {"content": ...} JSON).
 *
 * <p>Opt-in only ({@code alerts.webhook.enabled}), disabled by default, and sends nothing but the alert
 * text the admin would see in the console. HTTPS is required except for loopback addresses. Messages are
 * rate-limited and must be posted from an async worker, never the server thread.</p>
 */
public final class WebhookNotifier {

    private static final int MAX_CONTENT_CHARS = 1900; // Discord's limit is 2000

    private final Logger logger;
    private final AtomicLong lastSentMillis = new AtomicLong(0L);
    private final AtomicLong sentCount = new AtomicLong(0L);
    private final AtomicLong failedCount = new AtomicLong(0L);
    private volatile boolean failureLogged = false;

    public WebhookNotifier(Logger logger) {
        this.logger = logger != null ? logger : Logger.getLogger("OptimizeServer");
    }

    /**
     * True when the URL is acceptable: https, or http to a loopback host (local relays and tests).
     */
    public static boolean isAllowedUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return false;
        }
        try {
            URL url = new URL(rawUrl.trim());
            if ("https".equalsIgnoreCase(url.getProtocol())) {
                return url.getHost() != null && !url.getHost().isEmpty();
            }
            if ("http".equalsIgnoreCase(url.getProtocol())) {
                String host = url.getHost();
                return "localhost".equalsIgnoreCase(host) || InetAddress.getByName(host).isLoopbackAddress();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Sends one message unless another was sent less than {@code minIntervalMillis} ago.
     *
     * @return true when the webhook accepted the message
     */
    public boolean send(String rawUrl, String message, long minIntervalMillis, int timeoutMillis) {
        if (message == null || message.isEmpty() || !isAllowedUrl(rawUrl)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long last = lastSentMillis.get();
        if (last > 0L && (now - last) < Math.max(0L, minIntervalMillis)) {
            return false;
        }
        if (!lastSentMillis.compareAndSet(last, now)) {
            return false;
        }
        String content = message.length() > MAX_CONTENT_CHARS ? message.substring(0, MAX_CONTENT_CHARS) + "..." : message;
        byte[] body = ("{\"content\":" + jsonString(content) + "}").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(rawUrl.trim()).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(Math.max(1000, timeoutMillis));
            conn.setReadTimeout(Math.max(1000, timeoutMillis));
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "OptimizeServer-Webhook");
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                sentCount.incrementAndGet();
                failureLogged = false;
                return true;
            }
            recordFailure("HTTP " + code, null);
        } catch (IOException ex) {
            recordFailure(ex.getClass().getSimpleName(), ex);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return false;
    }

    private void recordFailure(String reason, Throwable t) {
        failedCount.incrementAndGet();
        if (!failureLogged) {
            failureLogged = true;
            logger.log(Level.WARNING, "[OptimizeServer] Alert webhook delivery failed (" + reason
                    + "); further failures are suppressed until one succeeds.", t);
        }
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    public long getSentCount() {
        return sentCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }
}
