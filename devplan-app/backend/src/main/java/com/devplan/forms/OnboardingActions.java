package com.devplan.forms;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.config.CurrentUser;
import com.devplan.config.DevplanProperties;
import com.devplan.data.Employees;
import com.devplan.data.Employees.Employee;
import com.devplan.engine.EngineClient;
import com.devplan.engine.KeycloakAdminClient;
import com.devplan.engine.TenantRegistrar;
import com.devplan.onboarding.Checklists;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The "x-on-complete" actions of the onboarding forms: what happens besides what the form itself writes to the case
 * file when a task is completed. Equipment (Onboarding.Assets) and the checklist (Onboarding.Checklist) are case-file
 * content now, not DB rows - an action changes them with ordinary case-file reads/writes, the same way it reaches the
 * employee's own directory row for personal data. Each action returns the extra case-file outputs it derives (ids,
 * names, flags), which the form layer merges into what it sends to the engine.
 */
@Component
public class OnboardingActions implements FormActions {
    private static final Set<String> ACTIONS = Set.of("createEmployee", "sendToIt", "itSetupDone");

    private static final Logger log = LoggerFactory.getLogger(OnboardingActions.class);

    private final JdbcClient db;
    private final ObjectMapper json;
    private final Employees employees;
    private final KeycloakAdminClient keycloak;
    private final TenantRegistrar tenant;
    private final EngineClient engine;
    private final Checklists checklists;
    private final DevplanProperties props;

    public OnboardingActions(JdbcClient db, ObjectMapper json, Employees employees, KeycloakAdminClient keycloak,
                             TenantRegistrar tenant, EngineClient engine, Checklists checklists, DevplanProperties props) {
        this.db = db;
        this.json = json;
        this.employees = employees;
        this.keycloak = keycloak;
        this.tenant = tenant;
        this.engine = engine;
        this.checklists = checklists;
        this.props = props;
    }

    @Override
    public boolean handles(String action) {
        return ACTIONS.contains(action);
    }

    @Override
    public ObjectNode run(String action, FormContext ctx, ObjectNode caseData) {
        return switch (action) {
            case "createEmployee" -> createEmployee(ctx);
            case "sendToIt" -> sendToIt(ctx, caseData);
            case "itSetupDone" -> itSetupDone(ctx, caseData);
            default -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unknown form action " + action);
        };
    }

    /**
     * Step 1 done: the Keycloak account (Keycloak e-mails the set-password link to the private address), stage
     * ONBOARDING, the checklist, the employee and manager in the case team; returns the Employee case item.
     */
    private ObjectNode createEmployee(FormContext ctx) {
        Map<String, Object> row = db.sql("SELECT first_name, last_name, private_email, role, manager_id, "
                        + "user_id, username FROM employee WHERE employee_id = ?").param(ctx.employeeId())
                .query((rs, n) -> TableStore.row(rs)).single();
        String name = (row.get("first_name") + " " + row.get("last_name")).trim();
        String role = (String) row.get("role");
        Employee manager = employees.byId((String) row.get("manager_id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manager: This field is required."));
        if (manager.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The manager has no account yet");
        }

        String userId = (String) row.get("user_id");
        if (userId == null) { // the account is made once; completing again (after an engine error) reuses it
            String username = uniqueUsername((String) row.get("first_name"), (String) row.get("last_name"));
            userId = keycloak.createUser(name, username, (String) row.get("private_email"));
            try {
                keycloak.assignRealmRole(userId, role);
                db.sql("UPDATE employee SET username = ?, user_id = ? WHERE employee_id = ?")
                        .params(username, userId, ctx.employeeId()).update();
                tenant.register(userId, name, (String) row.get("private_email"), Set.of(CurrentUser.caseRole(role)));
                keycloak.sendSetPasswordEmail(userId, props.app().baseUrl());
            } catch (RuntimeException e) {
                try {
                    keycloak.deleteUser(userId);
                    db.sql("UPDATE employee SET username = NULL, user_id = NULL WHERE employee_id = ?")
                            .param(ctx.employeeId()).update();
                } catch (RuntimeException cleanupFailed) {
                    log.error("Could not clean up Keycloak user {} after a failed onboarding: {}", userId,
                            cleanupFailed.getMessage());
                }
                throw e;
            }
            log.info("Onboarding: created account {} for employee {}", username, ctx.employeeId());
        }
        db.sql("UPDATE employee SET name = ?, stage = 'ONBOARDING' WHERE employee_id = ?")
                .params(name, ctx.employeeId()).update();
        checklists.createDefaults(ctx.employeeId(), ctx.caseId());

        addToTeam(ctx.caseId(), userId, "Employee");
        addToTeam(ctx.caseId(), manager.userId(), "Manager");

        // No names or e-mail addresses go into the case file: the mail process tasks (Notify IT, Send onboarding
        // complete e-mail) trigger with just Employee.employeeId, and the devplan backend's mail endpoints
        // (MailRelayController) resolve real recipients from the employee directory at send time.
        ObjectNode output = json.createObjectNode();
        ObjectNode employee = output.putObject("Employee");
        employee.put("employeeId", ctx.employeeId());
        employee.put("managerId", manager.employeeId());
        employee.put("trigger", "ONBOARDING");
        output.putObject("Onboarding").put("status", "IN_PROGRESS");
        return output;
    }

    private void addToTeam(String caseId, String userId, String caseRole) {
        engine.post("/cases/" + caseId + "/caseteam/users", Map.of("userId", userId, "caseRoles", List.of(caseRole)));
    }

    /** firstname.lastname in plain ASCII, with a number added when taken. */
    private String uniqueUsername(String first, String last) {
        String base = Normalizer.normalize((first + "." + last).toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9.]", "");
        String candidate = base;
        for (int i = 2; employees.byUsername(candidate).isPresent(); i++) {
            candidate = base + i;
        }
        return candidate;
    }

    /** Step 2 done: the request is sent; IT's checklist becomes what was requested, and the case flag starts the IT
     * admin's Equipment approval by IT. */
    private ObjectNode sendToIt(FormContext ctx, ObjectNode caseData) {
        JsonNode request = caseData.path("Onboarding").path("ITRequest");
        checklists.syncItItems(ctx.employeeId(), ctx.caseId(), names(request.path("itAccessories")), names(request.path("itAccounts")));
        ObjectNode output = json.createObjectNode();
        output.putObject("Onboarding").put("itRequestSent", true).put("itStatus", "SENT").put("itSentAt", now());
        return output;
    }

    private static List<String> names(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(n -> result.add(n.asText()));
        return result;
    }

    /** IT is done when every mandatory IT item (equipment, accounts) is ticked; the task shows them as checkboxes. */
    private ObjectNode itSetupDone(FormContext ctx, ObjectNode caseData) {
        JsonNode assets = caseData.path("Onboarding").path("Assets");
        Set<String> numbers = new java.util.HashSet<>();
        for (JsonNode asset : assets) {
            String number = asset.path("assetNumber").asText("").trim();
            if (number.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Asset no.: This field is required.");
            }
            if (!numbers.add(number)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This asset number is already in use.");
            }
            checklists.markAssetIssued(ctx.employeeId(), ctx.caseId(), asset.path("assetType").asText(), ctx.user());
        }
        List<String> open = checklists.of(ctx.employeeId(), "IT_ADMIN").stream()
                .filter(i -> i.mandatory() && !i.done()).map(Checklists.Item::name).toList();
        if (!open.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tick all mandatory checklist items first: " + String.join(", ", open) + ".");
        }
        // This task's Onboarding input/output is the whole case-file item (dv_onboarding.type), so caseData still
        // carries the Checklist exactly as it was when the task was opened - stale by now, since the checklist is
        // ticked live, directly in the case file (the embedded checklist above, and markAssetIssued just above).
        // Letting that stale copy flow through as this task's output would overwrite every one of those ticks the
        // moment "Mark setup done" completes the task, which is why they never reached HR's checklist tab. Only the
        // fields this task's own schema declares (ITRequest, Assets) go back out.
        JsonNode onboardingIn = caseData.path("Onboarding");
        ObjectNode onboardingOut = json.createObjectNode();
        if (onboardingIn.has("ITRequest")) {
            onboardingOut.set("ITRequest", onboardingIn.get("ITRequest"));
        }
        onboardingOut.set("Assets", assets.isMissingNode() ? json.createArrayNode() : assets);
        caseData.set("Onboarding", onboardingOut);

        ObjectNode output = json.createObjectNode();
        output.putObject("Onboarding").put("itStatus", "COMPLETED").put("itCompletedAt", now());
        return output;
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }
}
