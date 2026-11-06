package com.devplan.onboarding;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.config.CurrentUser;
import com.devplan.config.DevplanProperties;
import com.devplan.data.Employees;
import com.devplan.data.Employees.Employee;
import com.devplan.engine.EngineClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A new joiner's onboarding checklist: lives in the case file, at Onboarding.Checklist (an array, none of it personal
 * data). An item's id is "employeeId:sortOrder" - stable for its lifetime, and self-describing so a bare item id
 * (as the UI uses, with no case in the URL) resolves straight to its case without a search. Every write reads the
 * current array, changes it in Java, and replaces the whole array in one call - there is no partial-array update in
 * the case file API. checklist_default_item (the shared template, same for every onboarding) stays in the DB: it is
 * configuration used by many cases, not one case's content.
 */
@Repository
public class Checklists {

    public record Item(String id, String employeeId, String employeeName, String section, String name, String ownerRole,
                       String itemKind, String assetType, boolean mandatory, int sortOrder, String status,
                       String doneAt, String doneBy) {
        public boolean done() {
            return "DONE".equals(status);
        }
    }

    /** Sections in checklist order, with their WF-02 headings. */
    public static final Map<String, String> SECTIONS = Map.of(
            "DOCUMENTS_ADMIN", "Documents & admin",
            "EQUIPMENT_ISSUED", "Equipment issued",
            "ACCOUNTS_INSTALLATIONS", "Accounts & installations",
            "ORIENTATION", "Orientation");
    public static final List<String> SECTION_ORDER =
            List.of("DOCUMENTS_ADMIN", "EQUIPMENT_ISSUED", "ACCOUNTS_INSTALLATIONS", "ORIENTATION");

    /** Request names of the IT form's accessories -> asset type, so issuing such an asset ticks its item (ONB-06). */
    private static final Map<String, String> ACCESSORY_ASSET_TYPE = Map.of("Monitor", "MONITOR",
            "Keyboard & mouse", "KEYBOARD_MOUSE", "Headset", "HEADSET", "Docking station", "DOCKING_STATION",
            "Phone", "PHONE", "Access badge", "ACCESS_BADGE");

    /** Realm role -> the checklist owner role it may tick. */
    public static String ownerRole(CurrentUser user) {
        if (user.has("hr")) return "HR";
        if (user.has("it_admin")) return "IT_ADMIN";
        if (user.has("manager")) return "MANAGER";
        return "EMPLOYEE";
    }

    /** Engine case role (task role) -> checklist owner role. */
    public static String ownerRoleOfCaseRole(String caseRole) {
        return switch (caseRole == null ? "" : caseRole) {
            case "HR" -> "HR";
            case "ITAdmin" -> "IT_ADMIN";
            case "Manager" -> "MANAGER";
            case "Employee" -> "EMPLOYEE";
            default -> null;
        };
    }

    private final JdbcClient db;
    private final EngineClient engine;
    private final ObjectMapper json;
    private final DevplanProperties props;
    private final Employees employees;

    public Checklists(JdbcClient db, EngineClient engine, ObjectMapper json, DevplanProperties props, Employees employees) {
        this.db = db;
        this.engine = engine;
        this.json = json;
        this.props = props;
        this.employees = employees;
    }

    private static String id(String employeeId, int sortOrder) {
        return employeeId + ":" + sortOrder;
    }

    private Employee employeeOf(String itemId) {
        String employeeId = itemId.substring(0, itemId.lastIndexOf(':'));
        return employees.byId(employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Checklist item not found"));
    }

    private JsonNode checklistOf(String caseId) {
        return engine.get("/cases/" + caseId).path("file").path("Onboarding").path("Checklist");
    }

    // One lock per case: two ticks (or a tick racing an asset issue) read-then-replace the same array, so without
    // serializing them per case the second write can silently overwrite the first (ONB-04/ONB-06 lost updates).
    private final Map<String, Object> checklistLocks = new ConcurrentHashMap<>();

    /** Reads the array, lets transform compute the new one, and replaces it in the case file in one call - never
     * concurrently with another update of the same case's checklist. */
    private void updateChecklist(String caseId, UnaryOperator<ArrayNode> transform) {
        synchronized (checklistLocks.computeIfAbsent(caseId, c -> new Object())) {
            ArrayNode current = checklistOf(caseId).deepCopy();
            ArrayNode updated = transform.apply(current);
            engine.put("/cases/" + caseId + "/casefile/replace/Onboarding/Checklist", updated);
        }
    }

    private static Item toItem(String employeeId, String employeeName, JsonNode n) {
        int sortOrder = n.path("sortOrder").asInt(0);
        return new Item(id(employeeId, sortOrder), employeeId, employeeName, n.path("section").asText(), n.path("name").asText(),
                n.path("ownerRole").asText(), n.path("itemKind").asText("GENERIC"),
                n.hasNonNull("assetType") ? n.path("assetType").asText() : null, n.path("mandatory").asBoolean(false),
                sortOrder, n.path("status").asText("OPEN"), n.hasNonNull("doneAt") ? n.path("doneAt").asText() : null,
                n.hasNonNull("doneBy") ? n.path("doneBy").asText() : null);
    }

    /** Copies the default items once, into the case file (ONB-02, without templates and due dates). */
    public void createDefaults(String employeeId, String caseId) {
        if (checklistOf(caseId).size() > 0) {
            return;
        }
        List<Map<String, Object>> defaults = db.sql("SELECT section, name, owner_role, item_kind, asset_type, mandatory, "
                + "sort_order FROM checklist_default_item ORDER BY sort_order").query().listOfRows();
        ArrayNode items = json.createArrayNode();
        for (Map<String, Object> d : defaults) {
            ObjectNode item = items.addObject();
            item.put("section", (String) d.get("section"));
            item.put("name", (String) d.get("name"));
            item.put("ownerRole", (String) d.get("owner_role"));
            item.put("itemKind", (String) d.get("item_kind"));
            if (d.get("asset_type") != null) {
                item.put("assetType", (String) d.get("asset_type"));
            }
            item.put("mandatory", (Boolean) d.get("mandatory"));
            item.put("sortOrder", ((Number) d.get("sort_order")).intValue());
            item.put("status", "OPEN");
        }
        engine.put("/cases/" + caseId + "/casefile/replace/Onboarding/Checklist", items);
    }

    /**
     * After Send to IT: the IT admin's open items become exactly what HR requested - the laptop, each requested
     * accessory and each requested account, all mandatory. Default IT items that were not requested are removed;
     * items already ticked stay.
     */
    public void syncItItems(String employeeId, String caseId, List<String> accessories, List<String> accounts) {
        List<String> wanted = new ArrayList<>(List.of("Laptop"));
        wanted.addAll(accessories);
        wanted.addAll(accounts);
        updateChecklist(caseId, items -> {
            ArrayNode kept = json.createArrayNode();
            Set<String> present = new HashSet<>();
            int maxSort = 0;
            for (JsonNode n : items) {
                maxSort = Math.max(maxSort, n.path("sortOrder").asInt(0));
                if (!"IT_ADMIN".equals(n.path("ownerRole").asText())) {
                    kept.add(n);
                    continue;
                }
                String name = n.path("name").asText();
                boolean done = "DONE".equals(n.path("status").asText());
                if (wanted.contains(name) || done) {
                    if (wanted.contains(name)) {
                        ((ObjectNode) n).put("mandatory", true);
                    }
                    kept.add(n);
                    present.add(name);
                }
            }
            for (String name : wanted) {
                if (present.contains(name)) {
                    continue;
                }
                boolean equipment = "Laptop".equals(name) || ACCESSORY_ASSET_TYPE.containsKey(name);
                ObjectNode item = kept.addObject();
                item.put("section", equipment ? "EQUIPMENT_ISSUED" : "ACCOUNTS_INSTALLATIONS");
                item.put("name", name);
                item.put("ownerRole", "IT_ADMIN");
                item.put("itemKind", equipment ? "EQUIPMENT" : "ACCOUNT");
                if (equipment) {
                    item.put("assetType", "Laptop".equals(name) ? "LAPTOP" : ACCESSORY_ASSET_TYPE.get(name));
                }
                item.put("mandatory", true);
                item.put("sortOrder", ++maxSort);
                item.put("status", "OPEN");
            }
            return kept;
        });
    }

    public List<Item> of(String employeeId) {
        Employee e = employees.byId(employeeId).orElse(null);
        if (e == null || e.caseId() == null) {
            return List.of();
        }
        List<Item> result = new ArrayList<>();
        checklistOf(e.caseId()).forEach(n -> result.add(toItem(employeeId, e.name(), n)));
        result.sort((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()));
        return result;
    }

    public List<Item> of(String employeeId, String ownerRole) {
        return of(employeeId).stream().filter(i -> ownerRole.equals(i.ownerRole())).toList();
    }

    /** ONB-03: the open items this user owns, over every onboarding case. A manager owns the MANAGER items of their
     * direct reports; an employee their own EMPLOYEE items; HR and the IT admin all items of their role. */
    public List<Item> openFor(CurrentUser user, String userEmployeeId) {
        String owner = ownerRole(user);
        List<Item> result = new ArrayList<>();
        JsonNode cases = engine.get("/cases?tenant=" + props.engine().tenant()
                + "&definition=development&state=Active&numberOfResults=500");
        for (JsonNode summary : cases) {
            JsonNode file = engine.get("/cases/" + summary.path("id").asText()).path("file");
            String employeeId = file.path("Employee").path("employeeId").asText(null);
            Employee e = employeeId == null ? null : employees.byId(employeeId).orElse(null);
            if (e == null || !"ONBOARDING".equals(e.stage())) {
                continue;
            }
            boolean visible = switch (owner) {
                case "MANAGER" -> userEmployeeId != null && userEmployeeId.equals(e.managerId());
                case "EMPLOYEE" -> userEmployeeId != null && userEmployeeId.equals(employeeId);
                default -> true; // HR and IT_ADMIN see every employee's items of their role
            };
            if (!visible) {
                continue;
            }
            file.path("Onboarding").path("Checklist").forEach(n -> {
                if ("OPEN".equals(n.path("status").asText()) && owner.equals(n.path("ownerRole").asText())) {
                    result.add(toItem(employeeId, e.name(), n));
                }
            });
        }
        result.sort((a, b) -> {
            int byName = String.valueOf(a.employeeName()).compareTo(String.valueOf(b.employeeName()));
            return byName != 0 ? byName : Integer.compare(a.sortOrder(), b.sortOrder());
        });
        return result;
    }

    /** ONB-04: marks the item Done at once. */
    public void markDone(String itemId, CurrentUser user) {
        Employee e = employeeOf(itemId);
        int sortOrder = Integer.parseInt(itemId.substring(itemId.lastIndexOf(':') + 1));
        updateChecklist(e.caseId(), items -> {
            items.forEach(n -> {
                if (n.path("sortOrder").asInt(-1) == sortOrder && "OPEN".equals(n.path("status").asText())) {
                    ((ObjectNode) n).put("status", "DONE").put("doneAt", now()).put("doneBy", doneBy(user));
                }
            });
            return items;
        });
    }

    /** ONB-06: issuing an asset ticks the open EQUIPMENT item for its type. */
    public void markAssetIssued(String employeeId, String caseId, String assetType, CurrentUser user) {
        updateChecklist(caseId, items -> {
            items.forEach(n -> {
                if ("EQUIPMENT".equals(n.path("itemKind").asText()) && assetType.equals(n.path("assetType").asText())
                        && "OPEN".equals(n.path("status").asText())) {
                    ((ObjectNode) n).put("status", "DONE").put("doneAt", now()).put("doneBy", doneBy(user));
                }
            });
            return items;
        });
    }

    public void add(String employeeId, String section, String name, String ownerRole, boolean mandatory) {
        Employee e = employees.byId(employeeId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
        updateChecklist(e.caseId(), items -> {
            int nextSort = 0;
            for (JsonNode n : items) {
                nextSort = Math.max(nextSort, n.path("sortOrder").asInt(0));
            }
            ObjectNode item = items.addObject();
            item.put("section", section);
            item.put("name", name);
            item.put("ownerRole", ownerRole);
            item.put("itemKind", "GENERIC");
            item.put("mandatory", mandatory);
            item.put("sortOrder", nextSort + 1);
            item.put("status", "OPEN");
            return items;
        });
    }

    public int openMandatory(String employeeId) {
        return (int) of(employeeId).stream().filter(i -> i.mandatory() && !i.done()).count();
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }

    /** The opaque Keycloak user id, not the person's name or username - this goes into the case file. */
    private static String doneBy(CurrentUser user) {
        return user.userId();
    }
}
