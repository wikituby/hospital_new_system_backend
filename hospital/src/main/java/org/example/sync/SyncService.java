package org.example.sync;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.Hibernate;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SyncService {

    private static final Logger LOG = Logger.getLogger(SyncService.class);
    private static final int PAGE = 200;

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "sync.snapshot-limit", defaultValue = "50000")
    int snapshotLimit;

    @ConfigProperty(name = "sync.device-id", defaultValue = "hospital-pc")
    String deviceId;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean recordLocal(Object entity, String operation) {
        if (entity == null || SyncGuard.isApplying()) {
            return false;
        }
        Class<?> type = Hibernate.getClass(entity);
        if (type.getName().startsWith("org.example.sync.")) {
            return false;
        }
        try {
            String payload = toPayload(entity);
            String key = readId(entity);
            SyncEvent row = new SyncEvent();
            row.eventUid = UUID.randomUUID().toString();
            row.deviceId = deviceId;
            row.entityType = type.getName();
            row.entityKey = key;
            row.operation = operation;
            row.payload = payload;
            row.occurredAt = LocalDateTime.now();
            row.pushed = false;
            row.persist();
            foldIfNeeded();
            return true;
        } catch (Exception ex) {
            LOG.warnf(ex, "Sync log skipped for %s", type.getName());
            return false;
        }
    }

    @Transactional
    public Map<String, Object> pull(String callerDeviceId, Long afterId) {
        SyncSnapshot snapshot = SyncSnapshot.latest();
        long through = snapshot == null ? 0L : snapshot.throughSequence;
        long cursor = afterId == null ? 0L : afterId;
        String mode;
        boolean includeSnapshot = false;
        if (cursor <= 0L) {
            mode = "INITIAL";
            includeSnapshot = snapshot != null;
            cursor = through;
        } else if (cursor < through) {
            mode = "RECOVERY";
            includeSnapshot = snapshot != null;
            cursor = through;
        } else {
            mode = "INCREMENTAL";
        }

        List<SyncEvent> rows = SyncEvent.find("id > ?1 order by id", cursor).page(0, PAGE).list();
        List<Map<String, Object>> events = new ArrayList<>();
        long lastId = cursor;
        for (SyncEvent row : rows) {
            events.add(toEventMap(row));
            lastId = row.id;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("deviceId", callerDeviceId);
        body.put("afterId", afterId);
        body.put("lastId", lastId);
        body.put("hasMore", rows.size() == PAGE);
        body.put("snapshotThrough", includeSnapshot ? through : 0L);
        body.put("snapshot", includeSnapshot ? snapshot.payload : null);
        body.put("events", events);
        return body;
    }

    @Transactional
    public Map<String, Object> push(String callerDeviceId, List<Map<String, Object>> events) {
        SyncGuard.markApplying();
        int accepted = 0;
        int skipped = 0;
        try {
            if (events != null) {
                for (Map<String, Object> event : events) {
                    String uid = text(event.get("id"));
                    if (uid == null || uid.isBlank()) {
                        uid = text(event.get("eventUid"));
                    }
                    if (uid == null || uid.isBlank() || SyncEvent.findByUid(uid) != null) {
                        skipped++;
                        continue;
                    }
                    String entityType = text(event.get("entityType"));
                    String operation = text(event.get("operation"));
                    String payload = text(event.get("payload"));
                    if (entityType == null || operation == null || payload == null) {
                        skipped++;
                        continue;
                    }
                    applyPayload(entityType, operation, payload);
                    SyncEvent row = new SyncEvent();
                    row.eventUid = uid;
                    row.deviceId = callerDeviceId == null ? text(event.get("deviceId")) : callerDeviceId;
                    row.entityType = entityType;
                    row.entityKey = text(event.get("entityKey"));
                    row.operation = operation;
                    row.payload = payload;
                    row.occurredAt = LocalDateTime.now();
                    row.pushed = true;
                    row.persist();
                    accepted++;
                }
            }
            foldIfNeeded();
        } finally {
            SyncGuard.clear();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", accepted);
        body.put("skipped", skipped);
        return body;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Map<String, Object>> pendingFor(String peerKey, int limit) {
        List<SyncEvent> rows = entityManager.createQuery(
                        "select e from SyncEvent e where not exists ("
                                + "select d.id from SyncDelivery d where d.eventUid = e.eventUid and d.peerKey = :peer"
                                + ") order by e.id",
                        SyncEvent.class)
                .setParameter("peer", peerKey)
                .setMaxResults(Math.max(1, limit))
                .getResultList();
        List<Map<String, Object>> events = new ArrayList<>();
        for (SyncEvent row : rows) {
            events.add(toEventMap(row));
        }
        return events;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markDelivered(String peerKey, List<String> eventUids) {
        if (peerKey == null || peerKey.isBlank() || eventUids == null) {
            return;
        }
        for (String uid : eventUids) {
            if (uid == null || uid.isBlank()) {
                continue;
            }
            long existing = SyncDelivery.count("eventUid = ?1 and peerKey = ?2", uid, peerKey);
            if (existing > 0) {
                continue;
            }
            SyncDelivery delivery = new SyncDelivery();
            delivery.eventUid = uid;
            delivery.peerKey = peerKey;
            delivery.persist();
        }
    }

    /** Older rows were marked pushed before each peer was tracked separately. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void ackPushedWithoutDelivery(String peerKey) {
        if (peerKey == null || peerKey.isBlank()) {
            return;
        }
        List<SyncEvent> rows = SyncEvent.list("pushed", true);
        for (SyncEvent row : rows) {
            if (row.eventUid == null) {
                continue;
            }
            if (SyncDelivery.count("eventUid", row.eventUid) > 0) {
                continue;
            }
            SyncDelivery delivery = new SyncDelivery();
            delivery.eventUid = row.eventUid;
            delivery.peerKey = peerKey;
            delivery.persist();
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Map<String, Object>> unpushed(int limit) {
        List<SyncEvent> rows = SyncEvent.find("pushed = false order by id").page(0, limit).list();
        List<Map<String, Object>> events = new ArrayList<>();
        for (SyncEvent row : rows) {
            events.add(toEventMap(row));
        }
        return events;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markPushed(List<String> eventUids) {
        if (eventUids == null) {
            return;
        }
        for (String uid : eventUids) {
            SyncEvent row = SyncEvent.findByUid(uid);
            if (row != null) {
                row.pushed = true;
            }
        }
    }

    @Transactional
    public long cursor(String peerKey) {
        SyncCursor cursor = SyncCursor.findByPeer(peerKey);
        return cursor == null ? 0L : cursor.lastAppliedSequence;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void saveCursor(String peerKey, long sequence) {
        if (peerKey == null || peerKey.isBlank() || sequence <= 0L) {
            return;
        }
        SyncCursor cursor = SyncCursor.findByPeer(peerKey);
        if (cursor == null) {
            cursor = new SyncCursor();
            cursor.peerKey = peerKey;
            cursor.lastAppliedSequence = sequence;
            cursor.persist();
            return;
        }
        if (sequence > cursor.lastAppliedSequence) {
            cursor.lastAppliedSequence = sequence;
        }
    }

    private Map<String, Object> toEventMap(SyncEvent row) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sequence", row.id);
        event.put("id", row.eventUid);
        event.put("deviceId", row.deviceId);
        event.put("entityType", row.entityType);
        event.put("entityKey", row.entityKey);
        event.put("operation", row.operation);
        event.put("payload", row.payload);
        event.put("occurredAt", row.occurredAt == null ? null : row.occurredAt.toString());
        return event;
    }

    private void foldIfNeeded() {
        long count = SyncEvent.count();
        if (count <= snapshotLimit) {
            return;
        }
        int extra = (int) Math.min(count - snapshotLimit, 1000);
        List<SyncEvent> oldest = SyncEvent.find("order by id").page(0, extra).list();
        if (oldest.isEmpty()) {
            return;
        }
        SyncSnapshot snapshot = SyncSnapshot.latest();
        if (snapshot == null) {
            snapshot = new SyncSnapshot();
            snapshot.payload = "[]";
            snapshot.throughSequence = 0L;
        }
        StringBuilder folded = new StringBuilder(snapshot.payload == null ? "[" : snapshot.payload);
        if (folded.length() > 0 && folded.charAt(folded.length() - 1) == ']') {
            folded.setLength(folded.length() - 1);
        }
        boolean hadItems = folded.length() > 1;
        long through = snapshot.throughSequence;
        for (SyncEvent row : oldest) {
            if (hadItems) {
                folded.append(',');
            }
            hadItems = true;
            folded.append(Json.createObjectBuilder()
                    .add("id", row.eventUid)
                    .add("entityType", row.entityType)
                    .add("entityKey", row.entityKey == null ? "" : row.entityKey)
                    .add("operation", row.operation)
                    .add("payload", row.payload)
                    .build()
                    .toString());
            through = row.id;
            row.delete();
        }
        folded.append(']');
        snapshot.payload = folded.toString();
        snapshot.throughSequence = through;
        if (!snapshot.isPersistent()) {
            snapshot.persist();
        }
    }

    private void applyPayload(String entityType, String operation, String payload) {
        try {
            Class<?> type = Class.forName(entityType);
            JsonObject json;
            try (JsonReader reader = Json.createReader(new StringReader(payload))) {
                json = reader.readObject();
            }
            Object id = coerceId(json.get("id"));
            if (id == null) {
                return;
            }
            Object existing = entityManager.find(type, id);
            if ("DELETE".equalsIgnoreCase(operation)) {
                if (existing != null) {
                    entityManager.remove(existing);
                }
                return;
            }
            Object target = existing;
            boolean created = false;
            if (target == null) {
                target = type.getDeclaredConstructor().newInstance();
                writeField(target, "id", id);
                created = true;
            }
            for (String name : json.keySet()) {
                if ("id".equals(name) || name.startsWith("$")) {
                    continue;
                }
                Field field = findField(target.getClass(), name);
                if (field != null && !isSimple(field.getType())) {
                    writeAssociation(target, field, json.get(name));
                    continue;
                }
                writeSimple(target, name, json.get(name));
            }
            if (created) {
                insertAssigned(target);
            }
        } catch (Exception ex) {
            LOG.warnf("Sync apply failed %s %s: %s", operation, entityType, ex.getMessage());
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Stores a new row under the id it already has on the other server.
     * A normal persist() refuses that because this app generates ids itself.
     */
    private void insertAssigned(Object target) throws IllegalAccessException {
        org.hibernate.Session session = entityManager.unwrap(org.hibernate.Session.class);
        org.hibernate.engine.spi.SessionFactoryImplementor factory = session.getSessionFactory()
                .unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class);
        org.hibernate.persister.entity.AbstractEntityPersister persister =
                (org.hibernate.persister.entity.AbstractEntityPersister) factory.getRuntimeMetamodels()
                        .getMappingMetamodel()
                        .getEntityDescriptor(Hibernate.getClass(target));
        org.hibernate.dialect.Dialect dialect = factory.getJdbcServices().getDialect();
        boolean postgres = dialect instanceof org.hibernate.dialect.PostgreSQLDialect;
        char open = dialect.openQuote();
        char close = dialect.closeQuote();
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        addAssignedColumn(target, persister.getIdentifierPropertyName(), persister.getIdentifierColumnNames()[0], columns, values, postgres, open, close);
        for (String name : persister.getPropertyNames()) {
            String[] propertyColumns = persister.getPropertyColumnNames(name);
            if (propertyColumns.length == 0 || propertyColumns[0] == null || propertyColumns[0].isBlank()) {
                continue;
            }
            addAssignedColumn(target, name, propertyColumns[0], columns, values, postgres, open, close);
        }
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(sqlIdent(persister.getTableName(), postgres, open, close))
                .append(" (");
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(',');
                marks.append(',');
            }
            sql.append(columns.get(i));
            marks.append('?');
        }
        sql.append(") VALUES (").append(marks).append(')');
        var query = entityManager.createNativeQuery(sql.toString());
        for (int i = 0; i < values.size(); i++) {
            query.setParameter(i + 1, values.get(i));
        }
        query.executeUpdate();
    }

    private static void addAssignedColumn(
            Object target,
            String fieldName,
            String column,
            List<String> columns,
            List<Object> values,
            boolean postgres,
            char open,
            char close) throws IllegalAccessException {
        Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        Object value = field.get(target);
        if (!isSimple(field.getType())) {
            value = associationId(value);
        }
        if (value == null) {
            return;
        }
        columns.add(sqlIdent(column, postgres, open, close));
        values.add(value);
    }

    private static String sqlIdent(String name, boolean postgres, char open, char close) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.charAt(0) == open || trimmed.charAt(0) == '"') {
            return trimmed;
        }
        if (postgres) {
            String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
            if ("user".equals(lower) || "order".equals(lower) || "group".equals(lower)) {
                return "\"" + lower + "\"";
            }
            return lower;
        }
        return open + trimmed + close;
    }

    private void writeAssociation(Object target, Field field, JsonValue value) {
        Object id = coerceId(value);
        if (id == null) {
            return;
        }
        try {
            Object ref = entityManager.getReference(field.getType(), id);
            field.setAccessible(true);
            field.set(target, ref);
        } catch (Exception ignored) {
            // A link the model cannot store is left empty.
        }
    }

    private static String toPayload(Object entity) throws IllegalAccessException {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        Class<?> type = entity.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || field.getName().startsWith("$")) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(entity);
                if (!isSimple(field.getType())) {
                    value = associationId(value);
                }
                if (value == null) {
                    continue;
                }
                addJson(builder, field.getName(), value);
            }
            type = type.getSuperclass();
        }
        return builder.build().toString();
    }

    private static boolean isSimple(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type == Character.class
                || type.isEnum()
                || type == LocalDate.class
                || type == LocalDateTime.class
                || type == LocalTime.class
                || type == Instant.class
                || type == java.util.Date.class
                || type == java.sql.Date.class
                || type == java.sql.Timestamp.class
                || type == UUID.class
                || type == BigDecimal.class;
    }

    private static void addJson(JsonObjectBuilder builder, String name, Object value) {
        if (value instanceof Boolean bool) {
            builder.add(name, bool);
        } else if (value instanceof Integer number) {
            builder.add(name, number);
        } else if (value instanceof Long number) {
            builder.add(name, number);
        } else if (value instanceof Double number) {
            builder.add(name, number);
        } else if (value instanceof Float number) {
            builder.add(name, number.doubleValue());
        } else if (value instanceof BigDecimal number) {
            builder.add(name, number);
        } else if (value instanceof Number number) {
            builder.add(name, number.doubleValue());
        } else if (value instanceof Enum<?> en) {
            builder.add(name, en.name());
        } else {
            builder.add(name, String.valueOf(value));
        }
    }

    private static Object associationId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof org.hibernate.proxy.HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getIdentifier();
        }
        if (isSimple(value.getClass()) || value.getClass().isArray() || value instanceof java.util.Collection<?>) {
            return null;
        }
        try {
            Field idField = findField(Hibernate.getClass(value), "id");
            if (idField == null) {
                return null;
            }
            idField.setAccessible(true);
            return idField.get(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String readId(Object entity) {
        try {
            Field field = findField(entity.getClass(), "id");
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(entity);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Object coerceId(JsonValue value) {
        if (value == null || value.getValueType() == JsonValue.ValueType.NULL) {
            return null;
        }
        if (value.getValueType() == JsonValue.ValueType.NUMBER) {
            return ((jakarta.json.JsonNumber) value).longValue();
        }
        if (value.getValueType() == JsonValue.ValueType.STRING) {
            String text = ((JsonString) value).getString();
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ex) {
                return text;
            }
        }
        return null;
    }

    private static void writeSimple(Object target, String name, JsonValue value) {
        Field field = findField(target.getClass(), name);
        if (field == null || !isSimple(field.getType()) || value == null || value.getValueType() == JsonValue.ValueType.NULL) {
            return;
        }
        try {
            field.setAccessible(true);
            field.set(target, convert(field.getType(), value));
        } catch (Exception ignored) {
            // A field the local model cannot accept is left unchanged.
        }
    }

    private static void writeField(Object target, String name, Object value) throws IllegalAccessException {
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return;
        }
        field.setAccessible(true);
        if (value instanceof Long number && field.getType() == Integer.class) {
            field.set(target, number.intValue());
            return;
        }
        field.set(target, value);
    }

    private static Object convert(Class<?> type, JsonValue value) {
        String text = value.getValueType() == JsonValue.ValueType.STRING ? ((JsonString) value).getString() : value.toString().replace("\"", "");
        if (type == String.class) {
            return value.getValueType() == JsonValue.ValueType.STRING ? ((JsonString) value).getString() : text;
        }
        if (type == Long.class || type == long.class) {
            return ((jakarta.json.JsonNumber) value).longValue();
        }
        if (type == Integer.class || type == int.class) {
            return ((jakarta.json.JsonNumber) value).intValue();
        }
        if (type == Double.class || type == double.class) {
            return ((jakarta.json.JsonNumber) value).doubleValue();
        }
        if (type == Float.class || type == float.class) {
            return (float) ((jakarta.json.JsonNumber) value).doubleValue();
        }
        if (type == Boolean.class || type == boolean.class) {
            return value.getValueType() == JsonValue.ValueType.TRUE;
        }
        if (type == BigDecimal.class) {
            return ((jakarta.json.JsonNumber) value).bigDecimalValue();
        }
        if (type == LocalDate.class) {
            return LocalDate.parse(text);
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.parse(text);
        }
        if (type == LocalTime.class) {
            return LocalTime.parse(text);
        }
        if (type == Instant.class) {
            return Instant.parse(text);
        }
        if (type.isEnum()) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object en = Enum.valueOf((Class<? extends Enum>) type, text);
            return en;
        }
        return text;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ex) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
