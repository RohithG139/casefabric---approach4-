package com.devplan.forms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Option lists for task-model fields marked "x-options": the field gets a "oneOf" of {const, title} so dropdowns
 * follow the data without changing the form. A new list = one entry here. "goals" reads the plan's own goal titles
 * from the form's current data (the case file); the others are the app's employee directory (not case content).
 */
@Component
public class Lookups {
    public record Option(String value, String title) {
    }

    private final JdbcClient db;

    public Lookups(JdbcClient db) {
        this.db = db;
    }

    /** The options of a lookup for a form: buddies leave out the employee the form is about; goals are the titles of
     * the goals already in this form's Plan. */
    public List<Option> options(String lookup, FormContext ctx, JsonNode formData) {
        if ("goals".equals(lookup)) {
            List<Option> result = new ArrayList<>();
            formData.path("Plan").path("goals").forEach(g -> {
                String title = g.path("title").asText("");
                if (!title.isBlank()) {
                    result.add(new Option(title, title));
                }
            });
            return result;
        }
        String employeeId = ctx.employeeId();
        Map<String, String> sql = Map.of(
                "managers", "SELECT employee_id, name FROM employee WHERE role = 'manager' AND stage = 'ACTIVE' ORDER BY name",
                "buddies", "SELECT employee_id, name FROM employee WHERE stage = 'ACTIVE' AND user_id IS NOT NULL "
                        + "AND role IN ('employee', 'manager') AND employee_id <> coalesce(?, '') ORDER BY name",
                "departments", "SELECT name, name FROM department ORDER BY name",
                "jobTitles", "SELECT name, name FROM job_title ORDER BY name");
        String query = sql.get(lookup);
        if (query == null) {
            return List.of();
        }
        var statement = db.sql(query);
        if (query.contains("?")) {
            statement = statement.param(employeeId);
        }
        return statement.query((rs, n) -> new Option(rs.getString(1), rs.getString(2))).list();
    }
}
