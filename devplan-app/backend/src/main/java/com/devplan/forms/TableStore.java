package com.devplan.forms;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
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
    private final Map<String, List<Column>> schemaColumns = new ConcurrentHashMap<>();

    /** One column of a devplan table, with the UI metadata from its own comment (migration V13): "title",
     * "required", "format" ("email"/"date"), "options" (an x-options lookup name), "group", "row" (fields sharing a
     * group+row pair up in one HorizontalLayout). No comment = an empty object, so every lookup below just falls
     * back to a sensible default. */
    record Column(String name, String dataType, JsonNode meta) {
    }

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

    /** The schema property name of a column: the inverse of column() ("date_of_birth" -> "dateOfBirth"). */
    static String propertyOf(String column) {
        StringBuilder result = new StringBuilder();
        boolean upperNext = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                upperNext = true;
            } else {
                result.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            }
        }
        return result.toString();
    }

    /** Every column of a devplan table with its metadata, in the table's own column order; read once. */
    private List<Column> columnList(String table) {
        if (!table.matches("[a-z_]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown x-pii table " + table + " in the form");
        }
        List<Column> result = schemaColumns.computeIfAbsent(table, t -> db.sql(
                "SELECT c.column_name, c.data_type, col_description(format('%I.%I', c.table_schema, "
                        + "c.table_name)::regclass::oid, c.ordinal_position) FROM information_schema.columns c "
                        + "WHERE c.table_schema = 'devplan' AND c.table_name = ? ORDER BY c.ordinal_position")
                .param(t).query((rs, n) -> new Column(rs.getString(1), rs.getString(2), readMeta(rs.getString(3)))).list());
        if (result.isEmpty()) {
            schemaColumns.remove(table);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown x-pii table " + table + " in the form");
        }
        return result;
    }

    private JsonNode readMeta(String comment) {
        if (comment == null || comment.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(comment);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    /** The table's non-system columns, in display order: by their metadata's "order" when given, else in column
     * order after everything that does declare one - so a new column with no comment yet still shows up, at the
     * end, rather than being silently dropped. */
    private List<Column> ordered(String table) {
        List<Column> columns = new ArrayList<>();
        for (Column c : columnList(table)) {
            if (!SYSTEM.contains(c.name())) {
                columns.add(c);
            }
        }
        columns.sort(Comparator.comparingInt(c -> c.meta().path("order").asInt(Integer.MAX_VALUE)));
        return columns;
    }

    private static String jsonType(String sqlType) {
        return switch (sqlType) {
            case "boolean" -> "boolean";
            case "integer", "bigint", "smallint", "numeric" -> "number";
            default -> "string";
        };
    }

    /** "date_of_birth" -> "Date of birth": the default title for a column with no "title" in its comment. */
    private static String titleCase(String column) {
        String[] words = column.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            result.append(i == 0 ? Character.toUpperCase(words[i].charAt(0)) : words[i].charAt(0))
                    .append(words[i].substring(1)).append(' ');
        }
        return result.toString().strip();
    }

    /** The JSON Schema object (properties + required) for an "x-pii" table, built entirely from its own columns and
     * their comments - see migration V13 for the comment format. Nothing here is table-specific: a new column with a
     * matching comment is a new form field, no Java change. */
    ObjectNode schemaOf(String table) {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (Column c : ordered(table)) {
            String property = propertyOf(c.name());
            ObjectNode field = properties.putObject(property);
            field.put("type", jsonType(c.dataType()));
            field.put("title", c.meta().path("title").asText(titleCase(c.name())));
            if (c.meta().hasNonNull("format")) {
                field.put("format", c.meta().path("format").asText());
            }
            if (c.meta().hasNonNull("options")) {
                field.put("x-options", c.meta().path("options").asText());
            }
            if (c.meta().path("required").asBoolean(false)) {
                required.add(property);
            }
        }
        return schema;
    }

    /** The generated layout for an "x-pii" object at schema path "properties"."property": one Group per distinct
     * "group" in its columns' metadata (ungrouped columns share one unlabeled group), in the order each group first
     * appears; fields sharing a "group" + "row" pair up in a HorizontalLayout, a field with no "row" stands alone. */
    ArrayNode uiGroupsOf(String property, String table) {
        ArrayNode groups = json.createArrayNode();
        ObjectNode currentGroup = null;
        String currentGroupKey = null;
        String pendingRowKey = null;
        List<ObjectNode> pendingRow = new ArrayList<>();
        for (Column c : ordered(table)) {
            String groupLabel = c.meta().path("group").asText(null);
            String groupKey = groupLabel == null ? "" : groupLabel;
            String rowKey = groupKey + "/" + c.meta().path("row").asText(propertyOf(c.name()));
            if (!groupKey.equals(currentGroupKey)) {
                flushRow(currentGroup, pendingRow);
                pendingRow = new ArrayList<>();
                pendingRowKey = null;
                currentGroup = groups.addObject();
                currentGroup.put("type", "Group");
                if (!groupKey.isEmpty()) {
                    currentGroup.put("label", groupLabel);
                }
                currentGroup.putArray("elements");
                currentGroupKey = groupKey;
            }
            if (!rowKey.equals(pendingRowKey)) {
                flushRow(currentGroup, pendingRow);
                pendingRow = new ArrayList<>();
                pendingRowKey = rowKey;
            }
            ObjectNode control = json.createObjectNode();
            control.put("type", "Control");
            control.put("scope", "#/properties/" + property + "/properties/" + propertyOf(c.name()));
            pendingRow.add(control);
        }
        flushRow(currentGroup, pendingRow);
        return groups;
    }

    private static void flushRow(ObjectNode group, List<ObjectNode> pendingRow) {
        if (group == null || pendingRow.isEmpty()) {
            return;
        }
        ArrayNode elements = (ArrayNode) group.get("elements");
        if (pendingRow.size() == 1) {
            elements.add(pendingRow.get(0));
        } else {
            ObjectNode horizontal = elements.addObject();
            horizontal.put("type", "HorizontalLayout");
            ArrayNode inner = horizontal.putArray("elements");
            pendingRow.forEach(inner::add);
        }
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
