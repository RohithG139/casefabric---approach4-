package com.devplan.forms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.forms.FormContext.Mode;
import com.devplan.forms.TableStore.Key;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The layer between a task form and the app's personal-data store. The case file is the system of record for
 * business content: a task-model with no special markers passes through to the engine unchanged, exactly like a form
 * with no forms at all. Only data the task-model marks is treated differently:
 * <ul>
 * <li>{@code "x-pii": "<table>"} on a top-level schema property: its value is personal data and is read from and
 * written to that app table (TableStore), never sent to the engine's case file. The row already exists (the employee
 * record is created when onboarding starts); the form only ever updates it.</li>
 * <li>{@code "x-column": "<column>"} on a field inside an "x-pii" property: its column, when it is not the field's
 * name in snake_case.</li>
 * <li>{@code "x-options": "managers" | "goals" | ...} on a field: its choices come from the app's directory or from
 * the form's own current data (Lookups).</li>
 * <li>{@code "x-on-complete": "<action>"} on any top-level property: runs that backend action when the task is
 * completed (DevelopmentActions, OnboardingActions); it may return extra case outputs (ids, flags) that a plain form
 * field can't compute itself.</li>
 * </ul>
 * On read, the PII fields are merged into the engine's form data; on write, they are saved and stripped out, so they
 * never reach the case file, and only then do x-on-complete actions run. A task-model without "x-pii" passes through
 * unchanged other than running its x-on-complete actions.
 */
@Component
public class FormLayer {
    private final TableStore tables;
    private final Lookups lookups;
    private final StoreRules rules;
    private final List<FormActions> actions;

    public FormLayer(TableStore tables, Lookups lookups, StoreRules rules, List<FormActions> actions) {
        this.tables = tables;
        this.lookups = lookups;
        this.rules = rules;
        this.actions = actions;
    }

    /** A copy of the task-model with every "x-options" field given its "oneOf" choices. */
    public JsonNode prepareModel(JsonNode taskModel, FormContext ctx, JsonNode formData) {
        JsonNode copy = taskModel.deepCopy();
        resolveOptions(copy.path("schema"), ctx, formData);
        return copy;
    }

    private void resolveOptions(JsonNode node, FormContext ctx, JsonNode formData) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if (object.has("x-options")) {
                List<Lookups.Option> options = lookups.options(object.path("x-options").asText(), ctx, formData);
                if (!options.isEmpty()) {
                    ArrayNode oneOf = object.putArray("oneOf");
                    options.forEach(o -> oneOf.addObject().put("const", o.value()).put("title", o.title()));
                }
            }
            object.elements().forEachRemaining(child -> resolveOptions(child, ctx, formData));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> resolveOptions(child, ctx, formData));
        }
    }

    public boolean hasPersonalData(JsonNode taskModel) {
        return !personalData(taskModel).isEmpty();
    }

    /** True when the form's personal data can still be changed after completion (it never reaches the case file,
     * so there is nothing final about it once the task is done). */
    public boolean editableAfterCompletion(JsonNode taskModel) {
        return hasPersonalData(taskModel);
    }

    /** Read: puts the DB value of every "x-pii" property into the form data. */
    public void merge(JsonNode taskModel, ObjectNode formData, FormContext ctx) {
        for (var p : personalData(taskModel)) {
            String store = p.getValue().path("x-pii").asText();
            requireEmployee(ctx);
            ObjectNode merged = formData.path(p.getKey()).isObject()
                    ? ((ObjectNode) formData.get(p.getKey())).deepCopy() : formData.objectNode();
            merged.setAll(tables.load(store, new Key("employee_id", ctx.employeeId()), p.getValue()));
            formData.set(p.getKey(), merged);
        }
    }

    /**
     * Write: saves the "x-pii" properties to the DB (stripped out of what is returned), runs every x-on-complete
     * action (COMPLETE only) and merges their outputs in. Everything else in formData - the business content - passes
     * through unchanged to become the engine task's output.
     */
    public ObjectNode split(JsonNode taskModel, ObjectNode formData, FormContext ctx, Mode mode) {
        ObjectNode caseData = formData.deepCopy();
        for (var p : personalData(taskModel)) {
            String property = p.getKey();
            JsonNode schema = p.getValue();
            String store = schema.path("x-pii").asText();
            JsonNode value = caseData.remove(property);
            if (schema.path("readOnly").asBoolean(false) || value == null || value.isNull()) {
                continue;
            }
            if (!value.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, property + " must be an object");
            }
            requireEmployee(ctx);
            requireFields(schema, value);
            rules.check(store, ctx.employeeId(), value);
            tables.save(store, new Key("employee_id", ctx.employeeId()), (ObjectNode) value, schema);
        }
        if (mode == Mode.COMPLETE) {
            for (String action : onCompleteActions(taskModel.path("schema"))) {
                FormActions handler = actions.stream().filter(a -> a.handles(action)).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown form action " + action));
                deepMerge(caseData, handler.run(action, ctx, caseData));
            }
        }
        return caseData;
    }

    private static List<String> onCompleteActions(JsonNode schema) {
        List<String> result = new ArrayList<>();
        schema.path("properties").properties().forEach(p -> {
            if (p.getValue().hasNonNull("x-on-complete")) {
                result.add(p.getValue().path("x-on-complete").asText());
            }
        });
        return result;
    }

    /** Objects are merged field by field (an action adds a flag next to form fields already in Onboarding), other
     * values replaced. */
    private static void deepMerge(ObjectNode target, ObjectNode source) {
        source.properties().forEach(p -> {
            JsonNode existing = target.get(p.getKey());
            if (existing != null && existing.isObject() && p.getValue().isObject()) {
                deepMerge((ObjectNode) existing, (ObjectNode) p.getValue());
            } else {
                target.set(p.getKey(), p.getValue());
            }
        });
    }

    private static List<Map.Entry<String, JsonNode>> personalData(JsonNode taskModel) {
        List<Map.Entry<String, JsonNode>> result = new ArrayList<>();
        taskModel.path("schema").path("properties").properties().forEach(p -> {
            if (p.getValue().hasNonNull("x-pii")) {
                result.add(p);
            }
        });
        return result;
    }

    private static void requireEmployee(FormContext ctx) {
        if (ctx.employeeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This task has no Employee.employeeId input");
        }
    }

    /** The FRD's "This field is required." for every required field of the object that is empty. */
    private static void requireFields(JsonNode objectSchema, JsonNode value) {
        for (JsonNode name : objectSchema.path("required")) {
            JsonNode field = value.path(name.asText());
            boolean empty = field.isMissingNode() || field.isNull() || (field.isTextual() && field.asText().isBlank())
                    || (field.isContainerNode() && field.isEmpty());
            if (empty) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, title(objectSchema, name.asText()) + ": This field is required.");
            }
        }
    }

    private static String title(JsonNode objectSchema, String name) {
        return objectSchema.path("properties").path(name).path("title").asText(name);
    }
}
