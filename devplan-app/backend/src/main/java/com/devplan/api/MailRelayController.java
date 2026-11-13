package com.devplan.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.devplan.data.Employees;
import com.devplan.data.Employees.Employee;
import com.devplan.engine.KeycloakAdminClient;

import jakarta.mail.internet.MimeMessage;

/**
 * Sends the mails the case model's mail process tasks (Notify IT, Send onboarding complete e-mail) trigger. The
 * engine only ever hands these endpoints an employeeId - never a name or e-mail address, so
 * none of that travels through (and is permanently kept in) the case file's event log. Every recipient and every
 * word of the message is resolved and composed here, from the employee directory (DB) and Keycloak, at send time.
 * Only reachable inside the docker network (not under /api, which nginx forwards) and only with the shared
 * X-Mail-Token.
 */
@RestController
public class MailRelayController {
    private static final Logger log = LoggerFactory.getLogger(MailRelayController.class);

    public record EmployeeRef(String employeeId) {
    }

    private final JavaMailSender mailSender;
    private final JdbcClient db;
    private final Employees employees;
    private final KeycloakAdminClient keycloak;
    private final String from;
    private final String token;

    public MailRelayController(JavaMailSender mailSender, JdbcClient db, Employees employees, KeycloakAdminClient keycloak,
                               @Value("${devplan.mail.from}") String from, @Value("${devplan.mail.relay-token}") String token) {
        this.mailSender = mailSender;
        this.db = db;
        this.employees = employees;
        this.keycloak = keycloak;
        this.from = from;
        this.token = token;
    }

    /** Tells the IT admin that a new equipment request is waiting. */
    @PostMapping("/internal/mail/it-request")
    public void itRequest(@RequestHeader(value = "X-Mail-Token", required = false) String given, @RequestBody EmployeeRef body) {
        checkToken(given);
        Employee employee = employee(body.employeeId());
        String itEmail = anyWithRole("it_admin");
        send(itEmail, "", "New equipment request: " + employee.name(), "<div style='font-family: Arial, sans-serif; "
                + "color: #111827'><p>HR has sent the equipment request for <b>" + employee.name() + "</b>.</p>"
                + "<p>Open <i>My Tasks</i>, <b>Equipment approval by IT</b> at <a href='http://localhost:8090'>"
                + "http://localhost:8090</a> to issue the equipment and set up the accounts.</p></div>");
    }

    /** Tells HR (and the manager, in cc) that the new joiner is onboarded. */
    @PostMapping("/internal/mail/onboarded")
    public void onboarded(@RequestHeader(value = "X-Mail-Token", required = false) String given, @RequestBody EmployeeRef body) {
        checkToken(given);
        Employee employee = employee(body.employeeId());
        String hrEmail = anyWithRole("hr");
        String managerEmail = employees.byId(employee.managerId()).map(m -> keycloak.email(m.userId())).orElse("");
        send(hrEmail, managerEmail, "Onboarding complete: " + employee.name(), "<div style='font-family: Arial, sans-serif; "
                + "color: #111827'><p><b>" + employee.name() + "</b> is onboarded: every mandatory checklist item is "
                + "done.</p><p>The development plan can now be started from the employee's case at "
                + "<a href='http://localhost:8090'>http://localhost:8090</a>.</p></div>");
    }

    private void checkToken(String given) {
        if (!token.equals(given)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wrong mail token");
        }
    }

    private Employee employee(String employeeId) {
        return employees.byId(employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown employee"));
    }

    /** One active account holder of a realm role, e.g. the IT admin or an HR user, to notify. */
    private String anyWithRole(String role) {
        return db.sql("SELECT user_id FROM employee WHERE role = ? AND user_id IS NOT NULL ORDER BY employee_id LIMIT 1")
                .param(role).query(String.class).optional().map(keycloak::email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No " + role + " account to notify"));
    }

    private void send(String to, String cc, String subject, String html) {
        if (to == null || to.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The mail has no recipient");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            if (cc != null && !cc.isBlank()) {
                helper.setCc(cc);
            }
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Mail sent to {}: {}", to, subject);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not send mail: " + e.getMessage());
        }
    }
}
