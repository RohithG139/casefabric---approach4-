package com.devplan.forms;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Backend actions a task-model names with "x-on-complete": side effects beyond what the form itself writes to the
 * case file (Keycloak accounts, mail recipients, round bookkeeping) when a task is completed. An action sees the
 * whole form's current submission (caseData, keyed by the task-model's top-level properties - "Plan", "Approval",
 * "Onboarding", ...) and returns case-file outputs (ids, flags) that the form layer merges into what it sends to the
 * engine.
 */
public interface FormActions {
    boolean handles(String action);

    ObjectNode run(String action, FormContext ctx, ObjectNode caseData);
}
