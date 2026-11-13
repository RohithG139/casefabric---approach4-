package com.devplan.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.devplan.config.CurrentUser;
import com.devplan.config.DevplanProperties;
import com.devplan.data.Employees;
import com.devplan.data.Employees.Employee;
import com.devplan.engine.EngineClient;
import com.devplan.onboarding.Checklists;
import com.devplan.onboarding.Checklists.Item;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Onboarding of a new joiner (FRD-02, reduced): "+ New Employee" starts the development case with its Onboarding
 * stage; the steps themselves are the engine's human tasks (forms via TaskController). This controller serves what
 * is not a form: the wizard overview, the checklist (case file) and completing onboarding.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {
    /** The Onboarding stage and its user event in development.case. */
    static final String STAGE = "pi_onboarding";
    static final String COMPLETE_EVENT = "pi_complete_onboarding";

    private final DevplanProperties props;
    private final EngineClient engine;
    private final Employees employees;
    private final Checklists checklists;
    private final JdbcClient db;
    private final ObjectMapper json;

    public OnboardingController(DevplanProperties props, EngineClient engine, Employees employees,
                                Checklists checklists, JdbcClient db, ObjectMapper json) {
        this.props = props;
        this.engine = engine;
        this.employees = employees;
        this.checklists = checklists;
        this.db = db;
        this.json = json;
    }

    /**
     * "+ New Employee" (HR): a DRAFT employee row and its onboarding row, and the development case for them. The
     * case team is the HR tenant role (owners, so any HR user can later add the employee and manager) and the IT
     * admin tenant role. Creating the Onboarding case file item starts the Onboarding stage with Employee details.
     */
    @PostMapping
    public Map<String, String> newEmployee() {
        CurrentUser user = requireHr();
        String employeeId = UUID.randomUUID().toString();
        db.sql("INSERT INTO employee (employee_id, employee_code, stage) "
                + "VALUES (?, 'E-' || lpad(nextval('employee_code_seq')::text, 4, '0'), 'DRAFT')").param(employeeId).update();
        String caseId;
        try {
            Map<String, Object> body = Map.of(
                    "definition", props.engine().definition(),
                    "tenant", props.engine().tenant(),
                    "inputs", Map.of("Employee", Map.of("employeeId", employeeId, "trigger", "ONBOARDING")),
                    "caseTeam", Map.of(
                            "users", List.of(Map.of("userId", user.userId(), "caseRoles", List.of("HR"), "isOwner", true)),
                            "tenantRoles", List.of(
                                    Map.of("tenantRole", "HR", "caseRoles", List.of("HR"), "isOwner", true),
                                    Map.of("tenantRole", "ITAdmin", "caseRoles", List.of("ITAdmin"))),
                            "groups", List.of()));
            caseId = engine.post("/cases", body).path("caseInstanceId").asText();
            engine.post("/cases/" + caseId + "/casefile/create/Onboarding",
                    Map.of("itRequestSent", false, "mandatoryItemsDone", false, "completed", false, "status", "DRAFT"));
        } catch (RuntimeException e) {
            db.sql("DELETE FROM employee WHERE employee_id = ?").param(employeeId).update();
            throw e;
        }
        db.sql("UPDATE employee SET case_id = ? WHERE employee_id = ?").params(caseId, employeeId).update();
        return Map.of("employeeId", employeeId, "caseId", caseId);
    }

    /**
     * The wizard: the steps are the HR tasks of the Onboarding stage as the case definition lists them (so a step
     * added in the Case Designer shows up here), each with its engine task once it exists; then the checklist.
     */
    @GetMapping("/{employeeId}")
    public Map<String, Object> overview(@PathVariable String employeeId) {
        CurrentUser user = CurrentUser.get();
        if (!user.has("hr") && !user.has("it_admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only HR and the IT admin see onboarding");
        }
        Employee employee = employee(employeeId);
        JsonNode onboardingFile = employee.caseId() == null ? null
                : engine.get("/cases/" + employee.caseId()).path("file").path("Onboarding");
        Map<String, Object> onboarding = new LinkedHashMap<>();
        if (onboardingFile != null) {
            onboarding.put("status", onboardingFile.path("status").asText(null));
            onboarding.put("itStatus", onboardingFile.path("itStatus").asText(null));
            onboarding.put("itSentAt", onboardingFile.path("itSentAt").asText(null));
            onboarding.put("itCompletedAt", onboardingFile.path("itCompletedAt").asText(null));
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        if (employee.caseId() != null) {
            Document definition = CaseController.parseXml(engine.get("/cases/" + employee.caseId() + "/definition").asText());
            Map<String, JsonNode> tasks = new HashMap<>(); // latest task per name
            engine.get("/tasks/case/" + employee.caseId()).forEach(t -> tasks.put(t.path("taskName").asText(), t));
            for (String[] step : hrSteps(definition)) {
                JsonNode task = tasks.get(step[1]);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("planItemId", step[0]);
                s.put("name", step[1]);
                s.put("taskId", task == null ? null : task.path("id").asText());
                s.put("taskState", task == null ? null : task.path("taskState").asText());
                steps.add(s);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employee", employee);
        result.put("onboarding", onboarding);
        result.put("steps", steps);
        result.put("checklist", checklist(checklists.of(employeeId)));
        result.put("canComplete", "ONBOARDING".equals(employee.stage()) && checklists.openMandatory(employeeId) == 0);
        return result;
    }

    /** Cancel on a draft (HR): ends its case and removes the draft. */
    @DeleteMapping("/{employeeId}")
    public void cancelDraft(@PathVariable String employeeId) {
        requireHr();
        Employee employee = employee(employeeId);
        if (!"DRAFT".equals(employee.stage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft can be cancelled");
        }
        if (employee.caseId() != null) {
            for (JsonNode p : engine.get("/cases/" + employee.caseId()).path("planitems")) {
                if ("CasePlan".equals(p.path("type").asText()) && "Active".equals(p.path("currentState").asText())) {
                    engine.post("/cases/" + employee.caseId() + "/planitems/" + p.path("id").asText() + "/Terminate", null);
                }
            }
        }
        db.sql("DELETE FROM employee WHERE employee_id = ?").param(employeeId).update();
    }

    /** The form of "+ Add item", served like a task-model so the UI renders it the same way. */
    @GetMapping("/forms/checklist-item")
    public JsonNode checklistItemForm() throws java.io.IOException {
        return json.readTree(new ClassPathResource("forms/checklist-item.json").getInputStream());
    }

    public record NewItem(String name, String section, String ownerRole, Boolean mandatory) {
    }

    @PostMapping("/{employeeId}/checklist")
    public void addItem(@PathVariable String employeeId, @RequestBody NewItem item) {
        requireHr();
        employee(employeeId);
        if (item.name() == null || item.name().isBlank() || !Checklists.SECTIONS.containsKey(item.section())
                || !List.of("HR", "IT_ADMIN", "MANAGER", "EMPLOYEE").contains(item.ownerRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item, section and owner are required.");
        }
        checklists.add(employeeId, item.section(), item.name().trim(), item.ownerRole(), !Boolean.FALSE.equals(item.mandatory()));
    }

    /** ONB-04: tick an item. HR may tick any item; others only the items of their own role (and their own record). */
    @PostMapping("/checklist/{itemId}/done")
    public void tick(@PathVariable String itemId) {
        CurrentUser user = CurrentUser.get();
        if (!user.has("hr")) {
            boolean own = checklists.openFor(user, myEmployeeId(user)).stream().anyMatch(i -> i.id().equals(itemId));
            if (!own) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This is not your checklist item");
            }
        }
        checklists.markDone(itemId, user);
    }

    /**
     * "Complete onboarding" (HR), only when all mandatory items are done (ONB-05). The case file flag is set first;
     * the milestone "Employee onboarded" checks it too. The employee is then Active; nothing else starts by itself.
     */
    @PostMapping("/{employeeId}/complete")
    public void complete(@PathVariable String employeeId) {
        CurrentUser user = requireHr();
        Employee employee = employee(employeeId);
        if (!"ONBOARDING".equals(employee.stage())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This employee is not in onboarding");
        }
        if (checklists.openMandatory(employeeId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Complete all mandatory checklist items first.");
        }
        String caseId = employee.caseId();
        String event = null;
        for (JsonNode p : engine.get("/cases/" + caseId).path("planitems")) {
            if (COMPLETE_EVENT.equals(p.path("definitionId").asText()) && "Available".equals(p.path("currentState").asText())) {
                event = p.path("id").asText();
            }
        }
        if (event == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Onboarding cannot be completed in this case right now");
        }
        engine.put("/cases/" + caseId + "/casefile/update/Onboarding", Map.of("mandatoryItemsDone", true, "completed", true,
                "status", "COMPLETED", "completedAt", java.time.OffsetDateTime.now().toString(),
                "completedBy", user.userId())); // opaque id, not a name
        engine.post("/cases/" + caseId + "/planitems/" + event + "/Occur", null);
        db.sql("UPDATE employee SET stage = 'ACTIVE' WHERE employee_id = ?").param(employeeId).update();
    }

    /**
     * ONB-03: the user's own open checklist items, for My Tasks. The IT admin's items are never listed here, even
     * before the Equipment approval by IT task exists: they are only ever actioned from inside that one task (issuing an
     * asset ticks the equipment items; the accounts are ticked on its embedded checklist), so the IT admin always
     * sees exactly one task per new joiner, never loose rows.
     */
    @GetMapping("/items")
    public List<Item> myItems() {
        CurrentUser user = CurrentUser.get();
        if ("IT_ADMIN".equals(Checklists.ownerRole(user))) {
            return List.of();
        }
        return checklists.openFor(user, myEmployeeId(user));
    }

    /** The new joiner's dashboard (Screen 9): own items only (ONB-07), team and equipment. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser user = CurrentUser.get();
        Employee me = employees.byUserId(user.userId()).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("employee", me);
        if (me == null) {
            return result;
        }
        result.put("items", checklists.of(me.employeeId(), "EMPLOYEE"));
        result.put("team", db.sql("SELECT m.name AS manager, b.name AS buddy, e.department, e.job_title FROM employee e "
                + "LEFT JOIN employee m ON m.employee_id = e.manager_id LEFT JOIN employee b ON b.employee_id = e.buddy_id "
                + "WHERE e.employee_id = ?").param(me.employeeId()).query((rs, n) -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("manager", rs.getString(1));
                    t.put("buddy", rs.getString(2));
                    t.put("department", rs.getString(3));
                    t.put("jobTitle", rs.getString(4));
                    return t;
                }).single());
        List<Map<String, Object>> assets = new ArrayList<>();
        if (me.caseId() != null) {
            engine.get("/cases/" + me.caseId()).path("file").path("Onboarding").path("Assets").forEach(a -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("assetType", a.path("assetType").asText(null));
                m.put("model", a.path("model").asText(null));
                m.put("assetNumber", a.path("assetNumber").asText(null));
                m.put("issuedOn", a.path("issuedOn").asText(null));
                m.put("received", a.path("received").asBoolean(false));
                assets.add(m);
            });
        }
        result.put("assets", assets);
        return result;
    }

    /** Checklist by section, in checklist order, with the counts of the progress bar. */
    static Map<String, Object> checklist(List<Item> items) {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (String key : Checklists.SECTION_ORDER) {
            List<Item> inSection = items.stream().filter(i -> key.equals(i.section())).toList();
            if (!inSection.isEmpty()) {
                sections.add(Map.of("key", key, "name", Checklists.SECTIONS.get(key), "items", inSection));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sections", sections);
        result.put("total", items.size());
        result.put("done", items.stream().filter(Item::done).count());
        result.put("mandatoryTotal", items.stream().filter(Item::mandatory).count());
        result.put("mandatoryDone", items.stream().filter(i -> i.mandatory() && i.done()).count());
        return result;
    }

    /** {planItemId, name} of each human task in the Onboarding stage that the HR role performs, in model order. */
    private static List<String[]> hrSteps(Document definition) {
        Map<String, Element> byId = new HashMap<>();
        Map<String, String> roleNames = new HashMap<>();
        NodeList all = definition.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (!e.getAttribute("id").isEmpty()) {
                byId.putIfAbsent(e.getAttribute("id"), e);
            }
            if ("role".equals(e.getLocalName() == null ? e.getTagName() : e.getLocalName())) {
                roleNames.put(e.getAttribute("id"), e.getAttribute("name"));
            }
        }
        List<String[]> result = new ArrayList<>();
        Element stagePlanItem = byId.get(STAGE);
        Element stage = stagePlanItem == null ? null : byId.get(stagePlanItem.getAttribute("definitionRef"));
        if (stage == null) {
            return result;
        }
        for (Node n = stage.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element planItem && "planItem".equals(planItem.getTagName())) {
                Element definitionElement = byId.get(planItem.getAttribute("definitionRef"));
                if (definitionElement != null && "humanTask".equals(definitionElement.getTagName())
                        && "HR".equals(roleNames.get(definitionElement.getAttribute("performerRef")))) {
                    result.add(new String[] { planItem.getAttribute("id"), planItem.getAttribute("name") });
                }
            }
        }
        return result;
    }

    private Employee employee(String employeeId) {
        return employees.byId(employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    }

    private String myEmployeeId(CurrentUser user) {
        return employees.byUserId(user.userId()).map(Employee::employeeId).orElse(null);
    }

    private static CurrentUser requireHr() {
        CurrentUser user = CurrentUser.get();
        if (!user.has("hr")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only HR can do this");
        }
        return user;
    }
}
