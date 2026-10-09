package org.example.sync;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Calls another hospital server when sync.peer-base-url is set.
 * The cloud copy leaves that blank and only answers pull and push.
 */
@ApplicationScoped
public class SyncExchangeJob {

    private static final Logger LOG = Logger.getLogger(SyncExchangeJob.class);

    @Inject
    SyncService syncService;

    @ConfigProperty(name = "sync.peer-base-url", defaultValue = "none")
    String peerBaseUrl;

    @ConfigProperty(name = "sync.device-id", defaultValue = "hospital-pc")
    String deviceId;

    @ConfigProperty(name = "sync.api-key", defaultValue = "none")
    String apiKey;

    @ConfigProperty(name = "sync.exchange-seconds", defaultValue = "300")
    long exchangeSeconds;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private ScheduledExecutorService executor;

    void onStart(@Observes StartupEvent event) {
        if (!configured(peerBaseUrl)) {
            LOG.info("Sync peer is not set. This copy answers /sync/pull and /sync/push only.");
            return;
        }
        long delay = Math.max(30L, exchangeSeconds);
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hospital-sync");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::exchange, 20, delay, TimeUnit.SECONDS);
        LOG.infof("Sync will call %s every %s seconds", peerBaseUrl, delay);
    }

    void exchange() {
        try {
            String base = peerBaseUrl.endsWith("/") ? peerBaseUrl.substring(0, peerBaseUrl.length() - 1) : peerBaseUrl;
            HttpResponse<String> health = http.send(request(base + "/health").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (health.statusCode() < 200 || health.statusCode() >= 300) {
                LOG.warnf("Sync peer health returned %s", health.statusCode());
                return;
            }
            long after = syncService.cursor(base);
            String pullUrl = base + "/sync/pull?deviceId=" + encode(deviceId) + "&afterId=" + after;
            HttpResponse<String> pulled = http.send(request(pullUrl).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (pulled.statusCode() == 200) {
                applyPulled(base, pulled.body());
            }
            List<Map<String, Object>> outgoing = syncService.unpushed(200);
            if (!outgoing.isEmpty()) {
                String json = toPushBody(outgoing);
                HttpResponse<String> pushed = http.send(
                        request(base + "/sync/push").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (pushed.statusCode() == 200) {
                    List<String> ids = new ArrayList<>();
                    for (Map<String, Object> event : outgoing) {
                        ids.add(String.valueOf(event.get("id")));
                    }
                    syncService.markPushed(ids);
                }
            }
        } catch (Exception ex) {
            LOG.warnf("Sync exchange skipped: %s", ex.getMessage());
        }
    }

    private void applyPulled(String peerKey, String body) {
        jakarta.json.JsonObject root;
        try (jakarta.json.JsonReader reader = jakarta.json.Json.createReader(new java.io.StringReader(body))) {
            root = reader.readObject();
        }
        List<Map<String, Object>> events = new ArrayList<>();
        if (root.containsKey("snapshot") && !root.isNull("snapshot")) {
            events.addAll(readEventArray(root.getString("snapshot", "[]")));
        }
        if (root.containsKey("events") && root.get("events").getValueType() == jakarta.json.JsonValue.ValueType.ARRAY) {
            events.addAll(readEventArray(root.getJsonArray("events")));
        }
        if (!events.isEmpty()) {
            syncService.push(deviceId, events);
        }
        long lastId = root.containsKey("lastId") && !root.isNull("lastId") ? root.getJsonNumber("lastId").longValue() : 0L;
        long snapshotThrough = root.containsKey("snapshotThrough") && !root.isNull("snapshotThrough")
                ? root.getJsonNumber("snapshotThrough").longValue() : 0L;
        syncService.saveCursor(peerKey, Math.max(lastId, snapshotThrough));
    }

    private static List<Map<String, Object>> readEventArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try (jakarta.json.JsonReader reader = jakarta.json.Json.createReader(new java.io.StringReader(json))) {
            return readEventArray(reader.readArray());
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static List<Map<String, Object>> readEventArray(jakarta.json.JsonArray json) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (jakarta.json.JsonValue value : json) {
            if (value.getValueType() != jakarta.json.JsonValue.ValueType.OBJECT) {
                continue;
            }
            jakarta.json.JsonObject object = value.asJsonObject();
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("id", text(object, "id"));
            event.put("deviceId", text(object, "deviceId"));
            event.put("entityType", text(object, "entityType"));
            event.put("entityKey", text(object, "entityKey"));
            event.put("operation", text(object, "operation"));
            event.put("payload", text(object, "payload"));
            events.add(event);
        }
        return events;
    }

    private HttpRequest.Builder request(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");
        if (configured(apiKey)) {
            builder.header("X-Sync-Key", apiKey.trim());
        }
        if (url.contains("/sync/push")) {
            builder.header("Content-Type", "application/json");
        }
        return builder;
    }

    private String toPushBody(List<Map<String, Object>> events) {
        StringBuilder json = new StringBuilder();
        json.append("{\"deviceId\":\"").append(escape(deviceId)).append("\",\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> event = events.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(escape(text(event.get("id"))))
                    .append("\",\"deviceId\":\"").append(escape(text(event.get("deviceId"))))
                    .append("\",\"entityType\":\"").append(escape(text(event.get("entityType"))))
                    .append("\",\"entityKey\":\"").append(escape(text(event.get("entityKey"))))
                    .append("\",\"operation\":\"").append(escape(text(event.get("operation"))))
                    .append("\",\"payload\":\"").append(escape(text(event.get("payload"))))
                    .append("\"}");
        }
        json.append("]}");
        return json.toString();
    }

    private static String text(jakarta.json.JsonObject object, String name) {
        if (!object.containsKey(name) || object.isNull(name)) {
            return "";
        }
        jakarta.json.JsonValue value = object.get(name);
        if (value.getValueType() == jakarta.json.JsonValue.ValueType.STRING) {
            return object.getString(name);
        }
        return value.toString();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank() && !"none".equalsIgnoreCase(value.trim());
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
