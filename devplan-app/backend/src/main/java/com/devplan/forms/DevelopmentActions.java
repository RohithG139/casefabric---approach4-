package com.devplan.forms;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.devplan.engine.EngineClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The "x-on-complete" actions of the development plan forms. The plan's round-by-round lifecycle (submitted ->
 * approved/rejected -> evaluating -> closed) is tracked by the case plan's own stages and milestones, driven directly
 * by Approval.decision, ProgressReviews.planCompleted and Evaluation.furtherDevelopment in the case file - there is no
 * separate status to maintain here. The only real work left is archiving a closed round into PreviousRounds, since
 * Plan/Approval/SelfAssessment/Evaluation are each replaced fresh every round.
 */
@Component
public class DevelopmentActions implements FormActions {
    private static final Set<String> ACTIONS =
            Set.of("submitPlan", "decidePlan", "addActivity", "addReview", "submitSelfAssessment", "closeRound");

    private final EngineClient engine;
    private final ObjectMapper json;

    public DevelopmentActions(EngineClient engine, ObjectMapper json) {
        this.engine = engine;
        this.json = json;
    }

    @Override
    public boolean handles(String action) {
        return ACTIONS.contains(action);
    }

    @Override
    public ObjectNode run(String action, FormContext ctx, ObjectNode caseData) {
        ObjectNode output = json.createObjectNode();
        switch (action) {
            case "submitPlan" -> {
                int previousRounds = engine.get("/cases/" + ctx.caseId()).path("file").path("PreviousRounds").size();
                output.putObject("Plan").put("roundNo", previousRounds + 1);
            }
            case "decidePlan" -> output.putObject("Approval").put("decidedBy", ctx.user().userId()); // opaque id, not a name
            case "addActivity" -> output.putObject("Activity").put("activityId", UUID.randomUUID().toString());
            case "addReview" -> { /* the plan-performed milestone reacts to ProgressReviews.planCompleted itself */ }
            case "submitSelfAssessment" -> output.putObject("SelfAssessment").put("submitted", true);
            case "closeRound" -> archiveRound(ctx, caseData);
            default -> { /* unreachable: handles() already filtered to the known actions above */ }
        }
        return output;
    }

    /** Snapshots the round that just evaluated (Plan, Approval, Activities, ProgressReviews, SelfAssessment,
     * Evaluation - all already present in the task's own data) onto the case file's PreviousRounds. */
    private void archiveRound(FormContext ctx, ObjectNode caseData) {
        JsonNode plan = caseData.path("Plan");
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("roundNo", plan.path("roundNo").asInt(0));
        snapshot.set("period", plan.path("period"));
        ObjectNode snapshotPlan = snapshot.putObject("plan");
        snapshotPlan.set("period", plan.path("period"));
        snapshotPlan.set("goals", plan.path("goals"));
        snapshot.set("approval", caseData.path("Approval"));
        snapshot.set("activities", caseData.path("Activities"));
        snapshot.set("progressReviews", caseData.path("ProgressReviews"));
        snapshot.set("selfAssessment", caseData.path("SelfAssessment"));
        snapshot.set("evaluation", caseData.path("Evaluation"));
        engine.post("/cases/" + ctx.caseId() + "/casefile/create/PreviousRounds", snapshot);
    }
}
