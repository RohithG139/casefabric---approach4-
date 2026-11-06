package com.devplan.forms;

import com.devplan.config.CurrentUser;

/**
 * What a form is about: the case, the employee whose personal-data row its "x-pii" fields belong to
 * (Employee.employeeId of the task input), the user filling it in, and the task (open or done).
 */
public record FormContext(String caseId, String employeeId, CurrentUser user, String taskId, boolean taskOpen) {

    public FormContext(String caseId, String employeeId, CurrentUser user) {
        this(caseId, employeeId, user, null, true);
    }

    /** COMPLETE saves "x-pii" fields and runs x-on-complete actions; EDIT changes the PII fields of a completed task
     * (nothing sent to the engine, no actions run). */
    public enum Mode { COMPLETE, EDIT }
}
