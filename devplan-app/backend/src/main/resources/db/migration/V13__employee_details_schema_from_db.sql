-- Employee details (dv_employee_details.humantask's "Details" object, x-pii "employee") no longer lists its fields
-- in the case model: the backend now builds that part of the form's JSON schema from this table's own columns, so a
-- new personal-data field is a column + a comment, no case-model or Java change. See forms/TableStore.java.
--
-- Each column's comment is a small JSON blob read by TableStore.pii*(): title (label), required (form validation,
-- not a DB constraint - the employee row exists as a DRAFT before Step 1 fills these in, so columns stay nullable),
-- format ("email" | "date"), options (an x-options lookup name for a dropdown), group + row + order (the same
-- "Personal details" / "Role & reporting" layout the task-model used to hardcode).

-- grade and role were an inline oneOf in the case model; lookup tables make their options DB-driven too, the same
-- way department/job_title already are (Lookups.java).
CREATE TABLE grade (
    code       TEXT PRIMARY KEY,
    title      TEXT NOT NULL,
    sort_order INT NOT NULL
);
INSERT INTO grade (code, title, sort_order) VALUES
    ('JUNIOR', 'Junior', 10), ('MEDIOR', 'Medior', 20), ('SENIOR', 'Senior', 30);

CREATE TABLE app_role (
    code       TEXT PRIMARY KEY,
    title      TEXT NOT NULL,
    sort_order INT NOT NULL
);
INSERT INTO app_role (code, title, sort_order) VALUES
    ('employee', 'Employee', 10), ('manager', 'Manager', 20), ('hr', 'HR', 30), ('it_admin', 'IT admin', 40);

ALTER TABLE employee
    ADD CONSTRAINT employee_grade_fkey FOREIGN KEY (grade) REFERENCES grade (code),
    ADD CONSTRAINT employee_role_fkey FOREIGN KEY (role) REFERENCES app_role (code);

COMMENT ON COLUMN employee.first_name IS
    '{"title":"First name","required":true,"group":"Personal details","row":1,"order":10}';
COMMENT ON COLUMN employee.last_name IS
    '{"title":"Last name","required":true,"group":"Personal details","row":1,"order":20}';
COMMENT ON COLUMN employee.private_email IS
    '{"title":"Private e-mail","required":true,"format":"email","group":"Personal details","row":2,"order":30}';
COMMENT ON COLUMN employee.mobile_phone IS
    '{"title":"Mobile phone","required":true,"group":"Personal details","row":2,"order":40}';
COMMENT ON COLUMN employee.date_of_birth IS
    '{"title":"Date of birth","required":true,"format":"date","group":"Personal details","row":3,"order":50}';
COMMENT ON COLUMN employee.nationality IS
    '{"title":"Nationality","required":false,"group":"Personal details","row":3,"order":60}';
COMMENT ON COLUMN employee.street IS
    '{"title":"Street + number","required":false,"group":"Personal details","row":4,"order":70}';
COMMENT ON COLUMN employee.postcode IS
    '{"title":"Postcode","required":false,"group":"Personal details","row":5,"order":80}';
COMMENT ON COLUMN employee.city IS
    '{"title":"City","required":false,"group":"Personal details","row":5,"order":90}';

COMMENT ON COLUMN employee.job_title IS
    '{"title":"Job title","required":true,"options":"jobTitles","group":"Role & reporting","row":1,"order":100}';
COMMENT ON COLUMN employee.department IS
    '{"title":"Department","required":true,"options":"departments","group":"Role & reporting","row":1,"order":110}';
COMMENT ON COLUMN employee.manager_id IS
    '{"title":"Manager","required":true,"options":"managers","group":"Role & reporting","row":2,"order":120}';
COMMENT ON COLUMN employee.grade IS
    '{"title":"Grade","required":true,"options":"grades","group":"Role & reporting","row":2,"order":130}';
COMMENT ON COLUMN employee.role IS
    '{"title":"Application role","required":true,"options":"roles","group":"Role & reporting","row":3,"order":140}';
COMMENT ON COLUMN employee.buddy_id IS
    '{"title":"Buddy","required":false,"options":"buddies","group":"Role & reporting","row":3,"order":150}';
