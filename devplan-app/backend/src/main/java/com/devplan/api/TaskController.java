package com.devplan.api;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.config.CurrentUser;
import com.devplan.config.DevplanProperties;
import com.devplan.data.Employees;
import com.devplan.engine.EngineClient;
import com.devplan.forms.FormContext;
import com.devplan.forms.FormContext.Mode;
import com.devplan.forms.FormLayer;
import com.devplan.onboarding.Checklists;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Human tasks with their forms; a thin layer over the engine that works like the CaseFabric Case UI. The form (the
 * task-model) and its data (the task input, mapped from the case file by the engine) both come from the engine task;
 * completing sends the whole form back, and the engine's output mappings write the output parameters to the case
 * file. Nothing here is task-specific: a parameter added to a human task in the Case Designer needs no change here.
 * <p>
 * Personal data goes through the FormLayer: properties the task-model marks with "x-pii" are read from and saved to
 * the app database (merged into the form on read, stripped off on write), so they never reach the case file.
 * Everything else is case-file content and passes through unchanged. Task-models without "x-pii" pass through
 * entirely unchanged.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private static final Set<String> OPEN = Set.of("Unassigned", "Assigned", "Delegated");

    private final DevplanProperties props;
    private final EngineClient engine;
    private final ObjectMapper json;
    private final FormLayer forms;
    private final Checklists checklists;
    private final Employees employees;

    public TaskController(DevplanProperties props, EngineClient engine, ObjectMapper json, FormLayer forms,
                          Checklists checklists, Employees employees) {
        this.props = props;
        this.engine = engine;
        this.json = json;
        this.forms = forms;
        this.checklists = checklists;
        this.employees = employees;
    }

    /** Open tasks for one of the user's case roles, each with the name of the employee whose plan it is. */
    @GetMapping
    public List<JsonNode> myTasks() {
        Set<String> roles = CurrentUser.get().caseRoles();
        String tenant = "?tenant=" + props.engine().tenant();
        Map<String, String> employeeNames = new HashMap<>(); // per case, read once
        JsonNode tasks = engine.get("/tasks" + tenant + "&numberOfResults=200");
        return StreamSupport.stream(tasks.spliterator(), false)
                .filter(t -> OPEN.contains(t.path("taskState").asText()))
                .filter(t -> roles.contains(t.path("role").asText()))
                .map(t -> (JsonNode) ((ObjectNode) t).put("employeeName", employeeNames.computeIfAbsent(
                        t.path("caseInstanceId").asText(), this::employeeName)))
                .toList();
    }

    /**
     * The task-model as is and its form data, as the CaseFabric Case UI takes it: the task output once something was
     * submitted, otherwise the task input. Empty values are left out, so the form starts them from the schema.
     */
    @GetMapping("/{taskId}")
    public Map<String, Object> form(@PathVariable String taskId) {
        JsonNode task = engine.get("/tasks/" + taskId);
        JsonNode output = task.path("output");
        JsonNode source = output.isObject() && !output.isEmpty() ? output : task.path("input");
        ObjectNode formData = json.createObjectNode();
        source.properties().forEach(p -> {
            if (!p.getValue().isNull()) {
                formData.set(p.getKey(), p.getValue());
            }
        });

        JsonNode model = taskModel(task);
        FormContext ctx = context(task);
        forms.merge(model, formData, ctx);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", ((ObjectNode) task.deepCopy()).without(List.of("taskModel", "input", "output")));
        String employeeName = formData.path("Employee").path("name").asText(null);
        if (employeeName == null && ctx.employeeId() != null) {
            employeeName = employees.byId(ctx.employeeId()).map(e -> e.name()).orElse(null);
        }
        result.put("employeeName", employeeName != null ? employeeName : employeeName(task.path("caseInstanceId").asText()));
        result.put("employeeId", ctx.employeeId());
        result.put("taskModel", forms.prepareModel(model, ctx, formData));
        result.put("formData", formData);
        result.put("editable", forms.editableAfterCompletion(model)); // its DB data can still be changed after completion
        // While the employee is onboarding, a task shows the checklist items of its role (e.g. IT's accounts).
        String owner = Checklists.ownerRoleOfCaseRole(task.path("role").asText());
        if (ctx.employeeId() != null && owner != null && !"HR".equals(owner) && employees.byId(ctx.employeeId())
                .map(e -> "ONBOARDING".equals(e.stage())).orElse(false)) {
            result.put("checklist", checklists.of(ctx.employeeId(), owner));
        }
        return result;
    }

    /** The case and the employee the task is about: Employee.employeeId of its input (the case file as fallback). */
    private FormContext context(JsonNode task) {
        String caseId = task.path("caseInstanceId").asText();
        String employeeId = task.path("input").path("Employee").path("employeeId").asText(null);
        if (employeeId == null || employeeId.isBlank()) {
            employeeId = engine.get("/cases/" + caseId).path("file").path("Employee").path("employeeId").asText(null);
        }
        return new FormContext(caseId, employeeId, CurrentUser.get(), task.path("id").asText(),
                OPEN.contains(task.path("taskState").asText()));
    }

    private String employeeName(String caseId) {
        return engine.get("/cases/" + caseId).path("file").path("Employee").path("name").asText(null);
    }

    /**
     * Completes the engine task with the whole form, as the Case UI does. The engine keeps the fields named after the
     * task's output parameters and its output mappings write them to the case file.
     */
    @PostMapping("/{taskId}/complete")
    public void complete(@PathVariable String taskId, @RequestBody JsonNode formData) {
        if (!formData.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The form data must be an object");
        }
        JsonNode task = engine.get("/tasks/" + taskId);
        ObjectNode output = forms.split(taskModel(task), (ObjectNode) formData, context(task), Mode.COMPLETE);
        claim(task);
        engine.post("/tasks/" + taskId + "/complete", output);
    }

    /**
     * Changes the DB data of a completed task (e.g. Step 1 opened again from the wizard). Only HR, and only the
     * x-store parts: the case file is not touched and no actions run.
     */
    @PostMapping("/{taskId}/business-data")
    public void editBusinessData(@PathVariable String taskId, @RequestBody JsonNode formData) {
        if (!CurrentUser.get().has("hr")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only HR can change this after the task is done");
        }
        if (!formData.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The form data must be an object");
        }
        JsonNode task = engine.get("/tasks/" + taskId);
        forms.split(taskModel(task), (ObjectNode) formData, context(task), Mode.EDIT);
    }

    private void claim(JsonNode task) {
        if ("Unassigned".equals(task.path("taskState").asText())) {
            engine.put("/tasks/" + task.path("id").asText() + "/claim", null);
        }
    }

    private JsonNode taskModel(JsonNode task) {
        JsonNode model = task.path("taskModel");
        if (model.isTextual()) {
            try {
                return json.readTree(model.asText());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Task has no valid form");
            }
        }
        return model;
    }
}
