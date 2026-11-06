import React, { useEffect, useState } from 'react';
import { api } from './api.js';
import {
  Badge, Flash, Initials, OPEN_TASK, Problem, ROLE_LABEL, StateBadge, TASK_STATE, ago, caseRolesOf, day,
  setFlash, useLoad,
} from './shared.jsx';
import TaskForm from './TaskForm.jsx';
import { EmployeeDashboard, Employees, OnboardingWizard } from './Onboarding.jsx';
import DevelopmentPlan from './DevelopmentPlan.jsx';

// The three steps of one plan round, in order (the case's stages 1 · Plan, 2 · Perform plan, 3 · Evaluate plan).
const PHASES = [
  { key: 'plan', name: 'Plan', hint: 'Goals are written and approved.' },
  { key: 'perform', name: 'Perform', hint: 'Activities are carried out and progress is reviewed.' },
  { key: 'evaluate', name: 'Evaluate', hint: 'The employee and manager look back on the results.' },
];
const PHASE_LABEL = {
  plan: 'Planning', perform: 'In progress', evaluate: 'Evaluating', waiting: 'No plan running', onboarding: 'Onboarding',
};
// The case's development stage gets the plan-round strip and summary under its card.
const DEVELOPMENT_STAGE = 'pi_development_cycle';

const LABEL = {
  ANNUAL_REVIEW: 'Annual review', NEW_ROLE: 'New role', CAREER_GROWTH: 'Career growth',
  PERFORMANCE_IMPROVEMENT: 'Performance improvement', ONBOARDING: 'Onboarding',
};
const label = (value) => LABEL[value] || value || '';
const TRIGGERS = ['ANNUAL_REVIEW', 'NEW_ROLE', 'CAREER_GROWTH', 'PERFORMANCE_IMPROVEMENT', 'ONBOARDING'];

const EVENT_HELP = {
  'Start development plan': 'Begin a new plan round. The employee is asked to write their goals.',
  'Request early review': 'Review progress now instead of waiting for the next 14-day check.',
};

// Sidebar per role, as in WF-02. Icons are monochrome strokes.
const ICON = {
  dash: <path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />,
  tasks: <><polyline points="9 11 12 14 21 5" /><path d="M20 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h11" /></>,
  users: <><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M23 21v-2a4 4 0 0 0-3-3.87" /><path d="M16 3.13a4 4 0 0 1 0 7.75" /></>,
  folder: <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />,
};
function navFor(me) {
  const roles = me?.roles || [];
  const onboarding = me?.employee?.stage === 'ONBOARDING';
  if (roles.includes('hr')) return [['tasks', 'My Tasks', 'tasks'], ['employees', 'Employees', 'users'], ['cases', 'Employee cases', 'folder']];
  if (roles.includes('it_admin')) return [['tasks', 'My Tasks', 'tasks'], ['employees', 'Employees', 'users']];
  if (roles.includes('manager')) return [['tasks', 'My Tasks', 'tasks'], ['cases', 'My Team', 'folder']];
  return [...(onboarding ? [['dashboard', 'Dashboard', 'dash']] : []), ['tasks', 'My Tasks', 'tasks'], ['cases', 'My Cases', 'folder']];
}

/** Hash routes: #/dashboard, #/tasks[/:id], #/employees, #/onboarding/:employeeId, #/cases[/:id], #/plan/:caseId */
function useRoute() {
  const current = () => (window.location.hash.startsWith('#/') ? window.location.hash : '');
  const [hash, setHash] = useState(current);
  useEffect(() => {
    const onChange = () => setHash(current());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return hash.slice(2).split('/');
}

export default function App({ keycloak }) {
  const [page, id] = useRoute();
  const [me] = useLoad('/me');
  const [menu, setMenu] = useState(false);
  const [taskCount, setTaskCount] = useState(null);
  const roles = me?.roles || [];
  const nav = navFor(me);
  const home = nav[0][0];
  useEffect(() => { if (me && !page) window.location.replace(`#/${home}`); }, [me, page, home]);
  useEffect(() => {
    if (!me) return;
    Promise.all([api('GET', '/tasks'), api('GET', '/onboarding/items')])
      .then(([tasks, items]) => setTaskCount(tasks.length + items.length)).catch(() => {});
  }, [me, page, id]);
  const allowed = (key) => nav.some(([k]) => k === key);
  const current = page === 'onboarding' ? 'employees' : page === 'plan' ? 'cases' : page;

  return (
    <div className="app-frame">
      <header className="topbar">
        <a className="brand" href={`#/${home}`}><span className="brand-mark">CW</span>CubeWorks HR</a>
        <div className="topbar-right">
          {me && <span className="role-pill">{roles.map((r) => ROLE_LABEL[r]).filter(Boolean).join(', ')}</span>}
          {me && <span className="user-name">{me.name}</span>}
          <button className="profile-avatar" onClick={() => setMenu(!menu)} aria-label="Account menu"><Initials name={me?.name} /></button>
          {menu && (
            <div className="dropdown usermenu">
              <div className="um-hd"><b>{me?.name}</b><small>{me?.username}</small></div>
              <button className="um-item" onClick={() => keycloak.logout({ redirectUri: window.location.origin })}>Log out</button>
            </div>
          )}
        </div>
      </header>
      <div className="app">
        <nav className="sidebar" aria-label="Main">
          {nav.map(([key, text, icon]) => (
            <a key={key} href={`#/${key}`} className={`nav-item${current === key ? ' active' : ''}`}>
              <span className="nav-icon"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{ICON[icon]}</svg></span>
              {text}
              {key === 'tasks' && taskCount !== null && <span className="nav-count">{taskCount}</span>}
            </a>
          ))}
        </nav>
        <main className="content" onClick={() => menu && setMenu(false)}>
          {!me && <p className="muted">Loading…</p>}
          {me && page === 'dashboard' && <EmployeeDashboard me={me} />}
          {me && page === 'tasks' && !id && <TaskList />}
          {me && page === 'tasks' && id && <TaskForm taskId={id} me={me} />}
          {me && page === 'employees' && allowed('employees') && <Employees me={me} />}
          {me && page === 'onboarding' && id && allowed('employees') && <OnboardingWizard employeeId={id} me={me} />}
          {me && page === 'cases' && !id && <CaseList me={me} />}
          {me && page === 'cases' && id && <CaseDetail caseId={id} me={me} />}
          {me && page === 'plan' && id && <DevelopmentPlan caseId={id} me={me} />}
        </main>
      </div>
    </div>
  );
}

/** My Tasks: the engine's human tasks for the user's case roles plus their open onboarding checklist items (ONB-03). */
function TaskList() {
  const [tasks, error, reload] = useLoad('/tasks');
  const [items, itemsError, reloadItems] = useLoad('/onboarding/items');
  const [tickError, setTickError] = useState(null);
  const refresh = () => { reload(); reloadItems(); };
  const tick = (item) => {
    setTickError(null);
    api('POST', `/onboarding/checklist/${item.id}/done`).then(() => { setFlash(`${item.name}: done.`); refresh(); })
      .catch((e) => setTickError(e.message));
  };
  const loading = (!tasks && !error) || (!items && !itemsError);
  const empty = tasks && items && tasks.length + items.length === 0;
  return (
    <section>
      <Flash />
      <div className="page-header">
        <div><div className="page-title">My Tasks</div><div className="page-subtitle">Open tasks</div></div>
        <div className="header-actions"><button className="btn btn-secondary btn-sm" onClick={refresh}>Refresh</button></div>
      </div>
      <Problem message={error || itemsError || tickError} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>Type</th><th>Task</th><th>Employee</th><th>Since</th><th className="col-center">Action</th></tr></thead>
          <tbody>
            {loading && <tr className="no-results"><td colSpan={5}>Loading your tasks…</td></tr>}
            {empty && <tr className="no-results"><td colSpan={5}>All caught up — no open tasks</td></tr>}
            {(tasks || []).map((t) => (
              <tr key={t.id} className="row-link" onClick={() => { window.location.hash = `#/tasks/${t.id}`; }}>
                <td><Badge kind="info">Task</Badge></td>
                <td><b>{t.taskName}</b><div className="cell-sub">as {t.role} · {TASK_STATE[t.taskState] || t.taskState}</div></td>
                <td>{t.employeeName || '—'}</td>
                <td>{ago(t.createdOn)}</td>
                <td className="col-center"><a className="btn btn-secondary btn-xs" href={`#/tasks/${t.id}`}>Open</a></td>
              </tr>
            ))}
            {(items || []).map((i) => (
              <tr key={`item-${i.id}`}>
                <td><Badge kind="new">Checklist</Badge></td>
                <td><b>{i.name}</b>{i.mandatory && <span className="req-star">*</span>}<div className="cell-sub">Onboarding</div></td>
                <td>{i.employeeName}</td>
                <td>—</td>
                <td className="col-center">
                  <button className="btn btn-primary btn-xs" onClick={() => tick(i)}>Mark done</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function PhaseDots({ phase }) {
  const index = PHASES.findIndex((p) => p.key === phase);
  return (
    <span className="dots" aria-hidden="true">
      {PHASES.map((p, i) => <span key={p.key} className={i < index ? 'done' : i === index ? 'now' : ''} />)}
    </span>
  );
}

function CaseList({ me }) {
  const [cases, error, reload] = useLoad('/cases');
  const [employees] = useLoad('/employees');
  const [employeeId, setEmployeeId] = useState('');
  const [trigger, setTrigger] = useState('');
  const [startError, setStartError] = useState(null);
  const canStart = me && (me.roles.includes('hr') || me.roles.includes('manager'));
  const candidates = (employees || []).filter((e) => e.managerId && e.stage === 'ACTIVE');
  // One active case per employee: while onboarding or a plan runs no new one can be set up.
  const running = new Set((cases || []).filter((c) => ['plan', 'perform', 'evaluate', 'onboarding'].includes(c.phase))
    .map((c) => c.employee?.employeeId));

  const start = () => {
    setStartError(null);
    api('POST', '/cases', { employeeId, trigger })
      .then(({ caseId }) => { window.location.hash = `#/cases/${caseId}`; })
      .catch((e) => setStartError(e.message));
  };

  return (
    <section>
      <div className="page-header">
        <div><div className="page-title">{me.roles.includes('hr') ? 'Employee cases' : me.roles.includes('manager') ? 'My Team' : 'My Cases'}</div>
          <div className="page-subtitle">Onboarding, development plan, appraisal and work cycle per employee</div></div>
        <div className="header-actions"><button className="btn btn-secondary btn-sm" onClick={reload}>Refresh</button></div>
      </div>
      {canStart && (
        <div className="card start">
          <label htmlFor="employee">Set up development planning for</label>
          <select id="employee" className="field-select filt" value={employeeId} onChange={(e) => setEmployeeId(e.target.value)}>
            <option value="">Choose an employee</option>
            {candidates.map((e) => (
              <option key={e.employeeId} value={e.employeeId} disabled={running.has(e.employeeId)}>
                {e.name}{running.has(e.employeeId) ? ' (running)' : ''}
              </option>
            ))}
          </select>
          <label htmlFor="trigger">because of</label>
          <select id="trigger" className="field-select filt" value={trigger} onChange={(e) => setTrigger(e.target.value)}>
            <option value="">Choose a reason</option>
            {TRIGGERS.map((t) => <option key={t} value={t}>{label(t)}</option>)}
          </select>
          <button className="btn btn-primary" disabled={!employeeId || !trigger} onClick={start}>Set up</button>
          <Problem message={startError} />
        </div>
      )}
      <Problem message={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>Employee</th><th>Since</th><th>Development plan</th><th>Status</th></tr></thead>
          <tbody>
            {!cases && !error && <tr className="no-results"><td colSpan={4}>Loading…</td></tr>}
            {cases && cases.length === 0 && <tr className="no-results"><td colSpan={4}>{canStart ? 'No cases yet. Choose an employee above to set one up.' : 'No cases yet.'}</td></tr>}
            {(cases || []).map((c) => (
              <tr key={c.caseId} className="row-link" onClick={() => { window.location.hash = `#/cases/${c.caseId}`; }}>
                <td><span className="cell-person"><Initials name={c.employee?.name || 'New'} /><span className="cell-name">{c.employee?.name || 'New employee'}</span></span></td>
                <td>{day(c.startedAt)}{c.round > 0 ? ` · round ${c.round}` : ''}</td>
                <td><PhaseDots phase={c.phase} /></td>
                <td>{c.phase === 'onboarding' ? <Badge kind="info">Onboarding</Badge>
                  : <span>{c.phase === 'waiting' && c.round === 0 ? 'Not started yet' : PHASE_LABEL[c.phase] || c.state}</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** The plan round as three steps; the current one is large. */
function PhaseStrip({ phase, round }) {
  const index = PHASES.findIndex((p) => p.key === phase);
  if (index < 0) {
    const notStarted = round === 0;
    return (
      <div className="strip idle">
        <p className="strip-title">{notStarted ? 'Not started yet' : 'No plan running'}</p>
        <p className="muted">{notStarted ? 'Start the development plan to begin.' : 'The manager or HR can start a new development plan.'}</p>
      </div>
    );
  }
  return (
    <ol className="strip" aria-label="Plan progress">
      {PHASES.map((p, i) => (
        <li key={p.key} className={i < index ? 'done' : i === index ? 'now' : 'next'} aria-current={i === index ? 'step' : undefined}>
          <span className="step-no">{i < index ? '✓' : i + 1}</span>
          <span className="step-name">{p.name}</span>
          {i === index && <span className="step-hint">{p.hint}</span>}
        </li>
      ))}
    </ol>
  );
}

/**
 * One employee's case. Its top-level stages come from the case model (onboarding, development plan, appraisal, work
 * cycle, and any stage added later), each with its state and the events the user may raise to start or drive it.
 */
function CaseDetail({ caseId, me }) {
  const [data, error, reload] = useLoad(`/cases/${caseId}`);
  const [actionError, setActionError] = useState(null);
  const [busy, setBusy] = useState(false);
  const roles = caseRolesOf(me);
  if (error) return <Problem message={error} />;
  if (!data) return <p className="muted">Loading the case…</p>;

  const act = (promise) => {
    setActionError(null);
    setBusy(true);
    promise.then(reload).catch((e) => setActionError(e.message)).finally(() => setBusy(false));
  };
  const file = data.caseFile || {};
  const openTasks = data.tasks.filter((t) => OPEN_TASK.includes(t.taskState));
  const mine = openTasks.filter((t) => roles.includes(t.role));
  const others = openTasks.filter((t) => !roles.includes(t.role));
  const employeeId = file.Employee?.employeeId;
  const canSeeOnboarding = me.roles.includes('hr') || me.roles.includes('it_admin');

  return (
    <section>
      <Flash />
      <a className="back-link" href="#/cases">← All cases</a>
      <div className="emp-head">
        <Initials name={file.Employee?.name || 'New'} large />
        <div className="emp-head-main">
          <div className="emp-name">{file.Employee?.name || 'New employee'}</div>
          <div className="emp-meta">
            {[file.Employee?.jobTitle, file.Employee?.managerName && `Manager: ${file.Employee.managerName}`,
              file.Employee?.trigger && `Reason: ${label(file.Employee.trigger)}`].filter(Boolean).join(' · ')}
          </div>
        </div>
        {data.phase === 'onboarding' ? <Badge kind="info">Onboarding</Badge> : <Badge kind="success">Active</Badge>}
      </div>
      <Problem message={actionError} />

      <div className="stage-cards">
        {data.stages.map((s) => (
          <div key={s.planItemId} className={`card stage-card${s.state === 'Active' ? ' active' : ''}`}>
            <div className="card-title card-title-row">
              <span>{s.name}</span>
              <StateBadge state={s.state} />
            </div>
            {s.planItemId === DEVELOPMENT_STAGE && <PhaseStrip phase={data.phase} round={data.round} />}
            {s.planItemId === DEVELOPMENT_STAGE && (s.state !== 'Available' || data.round > 0) && (
              <a className="btn btn-secondary btn-sm" href={`#/plan/${caseId}`}>Open development plan</a>
            )}
            {s.planItemId !== DEVELOPMENT_STAGE && s.state === 'Active' && s.events.length === 0
              && !(s.planItemId === 'pi_onboarding') && <p className="muted">Started. No tasks yet.</p>}
            {s.planItemId === 'pi_onboarding' && s.state === 'Active' && canSeeOnboarding && employeeId && (
              <a className="btn btn-secondary btn-sm" href={`#/onboarding/${employeeId}`}>Open onboarding</a>
            )}
            {s.state === 'Available' && s.events.length === 0 && (
              <p className="muted">{data.phase === 'onboarding' ? 'Can be started after onboarding.' : 'Not started.'}</p>
            )}
            {s.events.length > 0 && (
              <div className="stage-actions">
                {s.events.map((e) => (
                  <div key={e.id} className="action">
                    <button className="btn btn-primary btn-sm" disabled={busy}
                            onClick={() => act(api('POST', `/cases/${caseId}/events/${e.id}`))}>{e.name}</button>
                    {EVENT_HELP[e.name] && <span className="muted"> {EVENT_HELP[e.name]}</span>}
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>

      <div className="card">
        <div className="card-title">For you</div>
        {mine.length === 0 && <p className="muted">Nothing waits on you in this case.</p>}
        {mine.length > 0 && (
          <div className="task-list">
            {mine.map((t) => (
              <a key={t.id} className="task-row" href={`#/tasks/${t.id}`}>
                <span><Badge kind="info">Open</Badge></span>
                <span><div className="task-title">{t.taskName}</div><div className="task-sub">as {t.role}</div></span>
                <span className="task-due">{ago(t.createdOn)}</span>
                <span>→</span>
              </a>
            ))}
          </div>
        )}
        {others.length > 0 && (
          <>
            <div className="section-h">Waiting on others</div>
            <ul className="plain">
              {others.map((t) => <li key={t.id}>{t.taskName} <span className="muted">· {t.role}</span></li>)}
            </ul>
          </>
        )}
      </div>
    </section>
  );
}
