# Development plan app

Browser app + thin Spring Boot backend for the CaseFabric `development` case.

```
Browser ──► frontend (nginx :8090) ──/api──► backend (Spring Boot) ──same user token──► CaseFabric engine (:2027)
   │        React + JSONForms build           │                                         │
   └── login: Keycloak (:8280) ───────────────┴── employee directory only (Postgres)    └── case file = the whole plan
```

- **Keycloak** (realm `devplan`) is the only login: for this app, the engine, the engine's Swagger UI and the CaseFabric Case UI.
- **Business data in the app database, case data in the case file.** For the development plan:

  | Case file item | In the case file | In the app DB (`devplan` schema) | Written by |
  |---|---|---|---|
  | `Employee` | id, name, job title, manager, reason | (employee directory) | case start |
  | `Plan` | roundId, roundNo, period, goals: title, type, target date | `dev_round.period`, `dev_goal` (all goal fields, plus description, success criteria) | Define / Revise plan |
  | `Approval` | decision, decidedBy | `dev_round.approval_decision`, `approval_comments` | Approve plan |
  | `Activities` (list) | activityId, type, related goal, status, due date | `dev_activity` (plus description, outcome) | the three activity tasks |
  | `ProgressReviews` (list) | date, on track, plan completed | `dev_progress_review` (plus notes, adjustments) | Review progress |
  | `SelfAssessment` | submitted, result per goal | `dev_goal.self_result`, `self_comments`, `dev_round.self_summary` | Self-assessment |
  | `Evaluation` | outcome, further development, result per goal | `dev_goal.eval_*`, `dev_round.eval_outcome`, `eval_summary`, `further_development` | Evaluate & document results |

  - The database keeps the full record of every round (`dev_round`, one row per round; earlier rounds are the `CLOSED` rows). `PreviousRounds` is gone from the case file.
  - A new round starts empty: *Define goals & plan* gets no earlier plan; activities and reviews shown are the round's own. Earlier rounds are only on the plan page's *Plan record* tab.
  - The case file keeps what the sentries need (`Approval.decision`, `ProgressReviews.planCompleted`, `Evaluation.furtherDevelopment`) and what the case history should show (goal titles, statuses, results).
  - Which field goes where is set in the task-model (`x-store`, `x-case`, `x-column`), see "The form layer" below.
  - Running cases from before this change were copied with `getting-started/copy-development-plans-to-db.py` and moved to the new model with `migrate-development-cases.py`. Their older case file entries still hold the full old data.
- **Thin backend**: no business data and no code per task.
  - Tasks work as in the CaseFabric Case UI: form = the task-model; form data = the engine task's `output` once something was submitted, otherwise its `input`; *Submit* sends the whole form to the engine, whose output mappings write the output parameters to the case file.
  - Case pages show the engine case (case file, plan items, tasks) as it is.
- **JSON forms**: each task's form is the `task-model` in its `.humantask` file (`getting-started/src/definitions/dv_*.humantask`): `schema` + `uiSchema`, and an optional `submitLabel` for the button.
  - Rendered as the Case UI does: with [RJSF](https://rjsf-team.github.io/react-jsonschema-form/) (MUI theme) by default, or with [JSONForms](https://jsonforms.io) when the `uiSchema` is a JSONForms layout (`type` / `elements`). `$CURRENT_USER` in the schema becomes the signed-in user's id.
  - Each task parameter has a `typeRef` to its `dv_*.type` file, so the Designer can generate the schema.
- **Engine permissions**: the engine checks case roles itself, because Spring forwards the user's token. Spring adds new Keycloak users to the engine tenant on their first call, using the `devplan-backend` service account.

## Run

Two compose projects: the CaseFabric stack (Keycloak, case engine, Postgres, Case UI, Designer) and this app.

```bash
cd getting-started && docker compose up -d           # 1. CaseFabric stack; creates the network casefabric_network
cd ../devplan-app  && docker compose up -d --build    # 2. this app: frontend + backend images
```

`docker-compose.yml` (this folder) builds two images:

| Image / container | Built from | What |
|---|---|---|
| `devplan-frontend` | `frontend/Dockerfile` | Vite builds the React app; nginx serves it on http://localhost:8090 and forwards `/api` to the backend (`frontend/nginx.conf`), so the browser uses one origin (no CORS). |
| `devplan-backend` | `backend/Dockerfile` | The Spring Boot API. Not published on the host (uncomment `ports` in the compose file to call it directly). |

- The frontend and backend share the network `devplan`. The backend also joins the external network `casefabric_network` to reach `keycloak`, `case-engine` and `case-engine-db-postgres` by name. Start the CaseFabric stack first; otherwise compose stops with "network casefabric_network declared as external, but could not be found".
- The frontend starts once the backend is healthy (it serves `/api/config`). The backend restarts until Postgres, Keycloak and the engine answer (`restart: on-failure`).
- Settings (port, database, Keycloak and engine URLs, service account secret) have defaults in the compose file and can be overridden in a `.env` file here, e.g. `DEVPLAN_PORT=9090` (then also add that address to the redirect URIs of Keycloak client `devplan-web`).
- Rebuild one part: `docker compose up -d --build frontend` or `... backend`. Stop the app: `docker compose down` (the CaseFabric stack keeps running).
- Frontend development without Docker: `cd frontend && npm install && npm run dev` (http://localhost:5173, `/api` proxied to :8090).

| What | URL | Login |
|---|---|---|
| Development plan app | http://localhost:8090 | shanmuk_emp / shanmuk_emp, kaustubh_emp / kaustubh_emp, rahul_mngr / rahul_mngr, sridhar_hr / sridhar_hr |
| Keycloak admin | http://localhost:8280 | admin / admin |
| CaseFabric Case UI | http://localhost:3317 | same users |
| Case Designer | http://localhost:2081 | – |
| Engine API (Swagger) | http://localhost:2027 | – |

The same realm also holds `lana`, `suzy`, `hank` (password = user name) for the `world` tenant demos (travelrequest, helloworld), and `admin` / `admin`, the engine platform owner. Their Keycloak ids are the ids Dex used to issue, so existing `world` cases still belong to them.

These are demo passwords for local use only.

## Test the engine API with Keycloak

**Swagger UI** (http://localhost:2027):

1. Click **Authorize**.
2. Client id `swagger-ui`, tick the `openid` scope, click **Authorize**.
3. Log in on the Keycloak page as any user above.
4. Try the endpoints, e.g. `GET /platform/user` or `GET /cases`.

**curl**, with a token from Keycloak (password grant on the `devplan-web` client):

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=devplan-web -d username=rahul_mngr -d password=rahul_mngr \n  http://localhost:8280/realms/devplan/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:2027/platform/user
curl -H "Authorization: Bearer $TOKEN" "http://localhost:2027/cases?tenant=devplan"
```

After changing `src/keycloak/devplan-realm.json`, run `docker compose up -d --force-recreate keycloak`. Keycloak keeps no volume, so it imports the realm again.

## Flow in the app

1. **rahul_mngr** (or **sridhar_hr**): *Plans* → choose Shanmuk and a reason → **Set up**.
2. **rahul_mngr** (or **sridhar_hr**): open Shanmuk's plan → *You can* → **Start development plan**.
3. **shanmuk_emp**: *My tasks* → **Define goals & plan**: plan period plus goals (title, type, success criteria, target date).
4. **rahul_mngr**: **Approve plan**. *Rejected* needs comments, and sends the plan back to *Revise plan*.
5. **Perform plan**:
   - While *2 · Perform plan* is active, the employee or manager adds activities on the plan page: *You can* → *Add an activity to the plan* (hidden in the other phases).
   - Every 14 days the manager gets **Review progress**. The manager can also start it at once with **Request early review** (plan page, *You can*; only users with the Manager case role see it); that also restarts the 14-day clock.
   - Ticking *The plan has been carried out* starts the evaluation; otherwise the next check follows.
6. **shanmuk_emp**: **Self-assessment**: a result per goal (rows pre-filled) and a summary.
7. **rahul_mngr**: **Evaluate & document results**.
   - *Further development needed* starts a new round, which starts from the last plan; the finished round is kept under *Earlier rounds*.
   - Otherwise the plan is closed, and a new one can be started.

## Onboarding a new joiner (FRD-02, reduced)

The `development` case has a stage **0 · Onboarding** before everything else. After onboarding, nothing starts by
itself: the case page shows the **Development plan cycle** with its *Start development plan* button. (A stage added
at the top level of the case model shows up there as its own card with its start events, without UI changes.)

Left out on purpose: probation, contract (incl. start date), verification and contract upload, onboarding templates
(one fixed default checklist instead), due dates, timers, e-mails other than Keycloak's set-password mail, the policy
pop-up (policies are plain ticks).

1. **sridhar_hr**: *Employees* → **+ New Employee**. The backend makes a DRAFT employee row and starts the case; the
   case file item `Onboarding` is created, which starts the Onboarding stage.
2. **Step 1 · Employee details** (HR task): personal details (address optional), role and reporting. *Save &
   continue* creates the Keycloak account (set-password mail to the private address; Keycloak sends it through the CaseFabric mailcatcher, `mailcatcher:1025`, so it shows up at http://localhost:1080), the
   checklist, and adds the employee and manager to the case team. *Cancel* on a draft ends the case and deletes it.
3. **Step 2 · IT & equipment request** (HR task) → *Send to IT*. IT's checklist then becomes exactly the request: the
   laptop, each selected accessory and each selected account (all mandatory); unselected default IT items are removed.
4. **kevin_it**: *My Tasks* shows one **Equipment request** per new joiner; IT's checklist items are checkboxes inside
   it, not separate rows. Record issued equipment (asset register; ticks the matching item), tick the accounts →
   *Mark setup done* (refused while a mandatory IT item is open).
5. **New joiner**: *Dashboard* with their own items only.
6. HR can still tick or add items on the wizard's checklist (the manager and HR have no default items).
7. **sridhar_hr**: wizard → *Checklist* → **Complete onboarding** (enabled when every mandatory item is done) →
   milestone *Employee onboarded*, employee stage *Active*.

### Mails (process tasks in the case model)

| Process task | When | To | Content |
|---|---|---|---|
| **Notify IT** (`dv_mail_it_request.process`) | IT & equipment request completed | the IT admin | a new equipment request is waiting |
| **Send onboarding complete e-mail** (`dv_mail_onboarded.process`) | the Onboarding stage ends | HR, manager in cc | the joiner is onboarded |

Recipients come from the `Onboarding` case file item (`employeeEmail`, `username`, `managerEmail`, `hrEmail`,
`itEmail`), which the backend fills when Employee details is completed. Engine 1.1.35 has no mail process
implementation, so these are HTTP process tasks: the engine posts the mail (subject and body are in the `.process`
file) to the backend's `/internal/mail` relay (docker network only, shared `X-Mail-Token`), which sends it through the
mailcatcher (http://localhost:1080).

**Username**: `firstname.lastname` in lower-case ASCII (accents removed), with a number added when it is taken
(`priya.sharma`, `priya.sharma2`). It is shown in the wizard header.

### Where the data lives

| App database (`devplan` schema) — business and personal data | Case file — only what the engine's sentries need |
|---|---|
| `employee`: personal details, role, manager, buddy, `stage` (DRAFT / ONBOARDING / ACTIVE), `case_id` | `Employee`: employeeId, name, job title, manager (as before; the development stage needs it) |
| `onboarding`: IT request (`it_*`), IT status, equipment note, completion | `Onboarding`: itRequestSent, mandatoryItemsDone, completed |
| `checklist_default_item` (the one default list), `checklist_item` (per joiner), `asset` | |
| `department`, `job_title` (dropdown lookups) | |

### The form layer: forms from the engine, data from the database

Every onboarding form is still the `task-model` of its `.humantask` (`dv_employee_details`, `dv_it_request`,
`dv_equipment_request`); the app has no hand-coded forms. Three extra keys in the schema tell
the backend (`forms/FormLayer.java`) which data is not case data:

| Key | On | Meaning |
|---|---|---|
| `"x-store": "<table>"` (`employee`, `onboarding`, `dev_round`, `dev_activity`, ...; `assets` is special) | a top-level property, or a list inside a stored object (`Plan.goals` → `dev_goal`) | Its value lives in that app table. Read: merged into the form data. Write: saved to the DB; only its `x-case` fields go to the engine. Property names map to columns: `privateEmail` → `private_email`. The row: per task (`task_id` column), the plan round (`dev_round`), or the employee. Lists: the round's rows, in `seq` order; `"x-rows": "update"` only updates existing rows (per-goal results). |
| `"x-case": true` | a field inside a stored property | The field is also sent to the case file. |
| `"x-column": "<column>"` | a field | Its column, when it is not the field name in snake_case (`result` → `self_result`). |
| `"x-options": "managers"` / `"buddies"` / `"departments"` / `"jobTitles"` / `"goals"` | a field | The backend fills `oneOf` from the DB (`forms/Lookups.java`). |
| `"x-on-complete": "createEmployee"` / `"sendToIt"` / `"itSetupDone"` / `"submitPlan"` / `"decidePlan"` / `"addActivity"` / `"addReview"` / `"submitSelfAssessment"` / `"closeRound"` | a stored property | Backend action run on complete (`forms/OnboardingActions.java`, `forms/DevelopmentActions.java`); it may return case outputs (e.g. `Plan.roundId`). |

Plus the uiSchema option `"table": true` on a list of objects (an
editable table instead of expandable rows; used for issued equipment, goals, activities). A task-model without these keys passes through unchanged. There is no
Save draft: a form is saved when its task is completed.

What changes without UI code: fields, labels, layout and required rules in a task-model; a new DB field (add the
column with Flyway, add the property to the task-model — no Java); a new human task (My Tasks, generic form); a new
HR task in the Onboarding stage (becomes a wizard step); a new top-level stage with a start event (becomes a stage card
on the case page). Hand-built: the checklist, the employee dashboard and the employees list.

## Change the model or the forms

The Case Designer (http://localhost:2081) is the source of truth for `getting-started/src/definitions`. A change to a human task shows up in this app without code changes, as in the CaseFabric Case UI:

1. Open the human task, add the input or output parameter and pick its type (a `.type` file, e.g. `dv_plan.type`; add a new `.type` for new data and add it to `development.type`).
2. Click **Generate schema from type information** for a starting form, or edit the `schema` / `uiSchema` JSON by hand. Generating replaces the whole task-model, so the hand-made titles, enums, widgets and `submitLabel` are lost; it also types a list parameter as a single object, so fix those by hand.
3. In the case model, bind the parameter to the case file item (input or output mapping).
4. Deploy:

```bash
cd getting-started
python deploy-case.py development                # compile, validate and deploy through the engine API (or Deploy in the Designer)
python migrate-development-cases.py              # optional: move running cases onto the new definition
```

- Every top-level property in `schema` is one task parameter. Output fields must have the same name as an output parameter; the engine ignores the other fields of a submitted form. When the `uiSchema` has a root `ui:order`, add the new field to it (or end it with `"*"`), otherwise the form does not render.
- A task keeps the task-model of the definition its case was started or migrated with: try a change on a new case.
- `generate-development.py` is retired: it wrote the first version of these files and would overwrite every Designer change.

- `compile-case.py` compiles the source files into the single definitions XML that the engine deploys (what the Designer's Deploy button does). It runs on its own too: `python compile-case.py src/definitions development -o development.xml`. It supports stages, human tasks, milestones, timers, user events and planning tables; a model with process or case tasks (travelrequest) still has to be deployed from the Designer.
- `deploy-case.py` posts the XML to the engine: `POST /repository/validate`, then `POST /repository/deploy/development.xml?tenant=devplan`. The engine writes it to `target/definitions/development.xml`. It logs in as the `devplan-backend` service account; `--user sridhar_hr` deploys as a user. Only owners of the devplan tenant may deploy (sridhar_hr, the service account); other users get 401. `--validate-only` only validates.
- New cases use the new definition; `migrate-development-cases.py` moves running ones. Check that on a test case first when the case file changed.

## Code

| Part | Path |
|---|---|
| API | `backend/src/main/java/com/devplan/api`: `UserController` (config, me, employees), `CaseController`, `TaskController` |
| Engine calls | `backend/.../engine/EngineClient.java` (token forwarding, read-your-writes header), `TenantRegistrar.java` |
| Database | `backend/src/main/resources/db/migration` (Flyway; V3 drops the old form tables, the employee directory stays) |
| Containers | `docker-compose.yml`, `frontend/Dockerfile` + `frontend/nginx.conf`, `backend/Dockerfile` |
| Form layer | `backend/.../forms` (`FormLayer`, `TableStore`, `AssetStore`, `Lookups`, `StoreRules`, `OnboardingActions`) |
| Onboarding | `backend/.../api/OnboardingController.java`, `backend/.../onboarding/Checklists.java`, migration `V6__onboarding.sql` |
| Browser app | `frontend/src/App.jsx` (WF-02 shell, My Tasks, cases, case page with stage cards), `TaskForm.jsx` (generic engine form), `Onboarding.jsx` (employees, wizard, dashboard), `Checklist.jsx`, `shared.jsx`, `styles.css` (WF-02 design tokens), `api.js`, `main.jsx` (Keycloak login) |
