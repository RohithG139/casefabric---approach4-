package com.devplan.forms;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Generic mapping between a form's "x-pii" object and one row of an app table, by key (e.g. employee_id): a schema
 * property "firstName" is the column "first_name", or the column its "x-column" names. Only properties declared in
 * the task-model schema AND present as a column are read or written, so a new DB field needs a column (Flyway) and a
 * field in the task-model, no Java. System columns (keys, account linkage, status) are never written from a form.
 * The row always exists already (the employee record is created when onboarding starts); a form never inserts one.
 */
@Component
public class TableStore {
    private static final Set<String> SYSTEM = Set.of("id", "employee_id", "employee_code", "user_id", "username",
            "stage", "case_id", "name", "created_by", "created_at");

    /** Which row: column = value. */
    public record Key(String column, Object value) {
    }

    private final JdbcClient db;
    private final ObjectMapper json;
    private final Map<String, Map<String, String>> columnTypes = new ConcurrentHashMap<>();

    public TableStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    /** column name -> SQL data type of a devplan table, read once; an unknown table is refused. */
    Map<String, String> columns(String table) {
        if (!table.matches("[a-z_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown x-pii table " + table + " in the form");
        }
        Map<String, String> result = columnTypes.computeIfAbsent(table, t -> {
            Map<String, String> found = new HashMap<>();
            db.sql("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = 'devplan' "
                    + "AND table_name = ?").param(t)
                    .query((rs, n) -> found.put(rs.getString(1), rs.getString(2))).list();
            return found;
        });
        if (result.isEmpty()) {
            columnTypes.remove(table);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown x-pii table " + table + " in the form");
        }
        return result;
    }

    public static String column(String property) {
        return property.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /** The column of a schema property: its "x-column", else its name in snake_case. */
    static String columnOf(String property, JsonNode propertySchema) {
        return propertySchema.path("x-column").asText(column(property));
    }

    /** The form object of the row with this key: the schema's properties filled from it (missing values left out). */
    public ObjectNode load(String table, Key key, JsonNode objectSchema) {
        List<Map<String, Object>> rows = db.sql("SELECT * FROM " + table + " WHERE " + key.column() + " = ?")
                .param(key.value()).query((rs, n) -> row(rs)).list();
        return rows.isEmpty() ? json.createObjectNode() : toObject(table, rows.get(0), objectSchema);
    }

    private ObjectNode toObject(String table, Map<String, Object> row, JsonNode objectSchema) {
        Map<String, String> columns = columns(table);
        ObjectNode result = json.createObjectNode();
        objectSchema.path("properties").properties().forEach(p -> {
            String column = columnOf(p.getKey(), p.getValue());
            Object value = row.get(column);
            if (columns.containsKey(column) && value != null) {
                result.set(p.getKey(), toJson(value, columns.get(column)));
            }
        });
        return result;
    }

    /** Writes the declared, writable properties of value to the existing row with this key. */
    public void save(String table, Key key, ObjectNode value, JsonNode objectSchema) {
        Map<String, Object> values = writable(table, value, objectSchema);
        if (values.isEmpty()) {
            return;
        }
        if (update(table, key, values) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + table + " record for this form");
        }
    }

    /** column -> SQL parameter of the declared, writable, non-system properties present in value. */
    private Map<String, Object> writable(String table, ObjectNode value, JsonNode objectSchema) {
        Map<String, String> columns = columns(table);
        Map<String, Object> result = new LinkedHashMap<>();
        objectSchema.path("properties").properties().forEach(p -> {
            JsonNode schema = p.getValue();
            String column = columnOf(p.getKey(), schema);
            if (value.has(p.getKey()) && !schema.path("readOnly").asBoolean(false) && columns.containsKey(column)
                    && !SYSTEM.contains(column)) {
                result.put(column, toSql(value.get(p.getKey())));
            }
        });
        return result;
    }

    private int update(String table, Key key, Map<String, Object> values) {
        Map<String, String> columns = columns(table);
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        values.forEach((column, param) -> {
            sets.add(column + " = " + cast(columns.get(column)));
            params.add(param);
        });
        params.add(key.value());
        return db.sql("UPDATE " + table + " SET " + String.join(", ", sets) + " WHERE " + key.column() + " = ?")
                .params(params).update();
    }

    static String cast(String dataType) {
        return switch (dataType) {
            case "date", "jsonb", "boolean", "integer", "bigint", "smallint", "numeric" -> "CAST(? AS " + dataType + ")";
            case "timestamp with time zone" -> "CAST(? AS timestamptz)";
            default -> "?";
        };
    }

    /** JSON value -> SQL parameter (as text; the CAST in the statement gives it its type). Blank text is NULL. */
    Object toSql(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isContainerNode()) {
            return value.toString();
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    /** DB value -> JSON; jsonb arrives as the driver's PGobject, whose toString() is the JSON text. */
    JsonNode toJson(Object value, String dataType) {
        if ("jsonb".equals(dataType) || "json".equals(dataType)) {
            try {
                return json.readTree(value.toString());
            } catch (Exception e) {
                return json.getNodeFactory().textNode(value.toString());
            }
        }
        if (value instanceof Boolean b) {
            return json.getNodeFactory().booleanNode(b);
        }
        if (value instanceof Number n) {
            return json.getNodeFactory().numberNode(n.longValue());
        }
        return json.getNodeFactory().textNode(value.toString()); // java.sql.Date prints as yyyy-MM-dd
    }

    static Map<String, Object> row(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new HashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnName(i), rs.getObject(i));
        }
        return row;
    }
}
