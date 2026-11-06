import React, { useEffect, useMemo, useState } from 'react';
import { JsonForms } from '@jsonforms/react';
import { materialCells } from '@jsonforms/material-renderers';
import { ThemeProvider } from '@mui/material/styles';
import { api } from './api.js';
import { Badge, Flash, Initials, OPEN_TASK, Problem, STAGE_BADGE, day, renderers, setFlash, theme, useLoad } from './shared.jsx';
import { ChecklistProgress, ChecklistSections } from './Checklist.jsx';
import TaskForm from './TaskForm.jsx';

const IT_STATUS = {
  NOT_SENT: ['neutral', 'Not sent yet'], SENT: ['info', 'Sent to IT · in progress'], COMPLETED: ['success', 'Completed by IT'],
};
const ASSET_TYPE = {
  LAPTOP: 'Laptop', MONITOR: 'Monitor', PHONE: 'Phone', ACCESS_BADGE: 'Access badge', DOCKING_STATION: 'Docking station',
  HEADSET: 'Headset', KEYBOARD_MOUSE: 'Keyboard & mouse', OTHER: 'Other',
};

/** Employees (HR, IT admin): everyone with their stage; HR starts onboarding a new joiner here. */
export function Employees({ me }) {
  const [employees, error, reload] = useLoad('/employees');
  const [query, setQuery] = useState('');
  const [startError, setStartError] = useState(null);
  const [busy, setBusy] = useState(false);
  const isHr = me?.roles?.includes('hr');
  const names = Object.fromEntries((employees || []).map((e) => [e.employeeId, e.name]));
  const shown = (employees || []).filter((e) => !query
    || `${e.name} ${e.jobTitle} ${e.employeeCode} ${e.username}`.toLowerCase().includes(query.toLowerCase()));

  const newEmployee = () => {
    setBusy(true);
    setStartError(null);
    api('POST', '/onboarding')
      .then(({ employeeId }) => { window.location.hash = `#/onboarding/${employeeId}`; })
      .catch((e) => { setStartError(e.message); setBusy(false); });
  };
  const open = (e) => {
    if (e.stage === 'DRAFT' || e.stage === 'ONBOARDING') window.location.hash = `#/onboarding/${e.employeeId}`;
    else if (e.caseId) window.location.hash = `#/cases/${e.caseId}`;
  };

  return (
    <section>
      <Flash />
      <div className="page-header">
        <div><div className="page-title">Employees</div><div className="page-subtitle">Everyone in CubeWorks HR</div></div>
        <div className="header-actions">
          {isHr && <button className="btn btn-primary" disabled={busy} onClick={newEmployee}>+ New Employee</button>}
        </div>
      </div>
      <Problem message={startError || error} />
      <div className="table-wrap">
        <div className="table-toolbar">
          <input className="search-in" placeholder="Search name, code or job title" value={query} onChange={(e) => setQuery(e.target.value)} />
          <button className="btn btn-secondary btn-sm" onClick={reload}>Refresh</button>
        </div>
        <table>
          <thead><tr><th>Employee</th><th>Code</th><th>Job title</th><th>Manager</th><th>Stage</th></tr></thead>
          <tbody>
            {!employees && !error && <tr className="no-results"><td colSpan={5}>Loading…</td></tr>}
            {employees && shown.length === 0 && <tr className="no-results"><td colSpan={5}>No employees match</td></tr>}
            {shown.map((e) => {
              const [kind, text] = STAGE_BADGE[e.stage] || ['neutral', e.stage];
              const clickable = e.stage !== 'ACTIVE' || e.caseId;
              return (
                <tr key={e.employeeId} className={clickable ? 'row-link' : ''} onClick={() => clickable && open(e)}>
                  <td><span className="cell-person"><Initials name={e.name || 'New'} />
                    <span><span className="cell-name">{e.name || 'New employee'}</span>
                      <span className="cell-sub">{e.username || 'no account yet'}</span></span></span></td>
                  <td>{e.employeeCode || '—'}</td>
                  <td>{e.jobTitle || '—'}</td>
                  <td>{names[e.managerId] || '—'}</td>
                  <td><Badge kind={kind}>{text}</Badge></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/**
 * The onboarding of one new joiner (Screens 1–5). The step tabs are the HR tasks of the Onboarding stage as the case
 * model defines them, each rendered as its engine form; the last tab is the checklist. Steps open in any order.
 */
export function OnboardingWizard({ employeeId, me }) {
  const [data, error, reload] = useLoad(`/onboarding/${employeeId}`);
  const [tab, setTab] = useState(null);
  const [showAdd, setShowAdd] = useState(false);
  const [actionError, setActionError] = useState(null);
  const [busy, setBusy] = useState(false);
  const isHr = me?.roles?.includes('hr');

  // Open the first step that waits for HR; the checklist once all steps are done.
  useEffect(() => {
    if (!data || tab !== null) return;
    const next = data.steps.findIndex((s) => s.taskId && OPEN_TASK.includes(s.taskState));
    setTab(next >= 0 ? next : data.steps.length);
  }, [data, tab]);

  if (error) return <Problem message={error} />;
  if (!data || tab === null) return <p className="muted">Loading the onboarding…</p>;
  const { employee, steps, checklist, onboarding } = data;
  const items = checklist.sections.flatMap((s) => s.items);
  const isDraft = employee.stage === 'DRAFT';
  const checklistTab = steps.length;

  const cancel = () => {
    if (isDraft && isHr) {
      if (!window.confirm('Cancel this new employee? The draft is deleted.')) return;
      api('DELETE', `/onboarding/${employeeId}`)
        .then(() => { setFlash('Draft cancelled.'); window.location.hash = '#/employees'; })
        .catch((e) => setActionError(e.message));
      return;
    }
    window.location.hash = '#/employees';
  };
  const stepDone = (index) => () => { reload(); setTab(index + 1); };
  const complete = () => {
    setBusy(true);
    setActionError(null);
    api('POST', `/onboarding/${employeeId}/complete`)
      .then(() => { setFlash(`Milestone reached · ${employee.name} onboarded`); window.location.hash = '#/employees'; })
      .catch((e) => { setActionError(e.message); setBusy(false); });
  };

  return (
    <section>
      <a className="back-link" href="#/employees">← Back to Employees</a>
      <div className="page-header">
        <div>
          <div className="page-title">{employee.name || 'New employee'}</div>
          <div className="page-subtitle">Onboarding · {employee.employeeCode}{employee.username ? ` · username ${employee.username}` : ''}</div>
        </div>
        <div className="header-actions">
          <Badge kind={(STAGE_BADGE[employee.stage] || ['neutral'])[0]}>{(STAGE_BADGE[employee.stage] || [, employee.stage])[1]}</Badge>
          {employee.caseId && employee.stage !== 'DRAFT' && <a className="btn btn-secondary btn-sm" href={`#/cases/${employee.caseId}`}>Open case</a>}
        </div>
      </div>

      <div className="bcrumb steptabs" role="tablist">
        {steps.map((s, i) => (
          <React.Fragment key={s.planItemId}>
            <button type="button" role="tab" aria-selected={tab === i}
                    className={`step${tab === i ? ' cur' : s.taskState === 'Completed' ? ' done' : ''}`}
                    onClick={() => setTab(i)}>{i + 1} · {s.name}</button>
            <span className="arrow">→</span>
          </React.Fragment>
        ))}
        <button type="button" role="tab" aria-selected={tab === checklistTab}
                className={`step${tab === checklistTab ? ' cur' : ''}`} onClick={() => setTab(checklistTab)}>
          {steps.length + 1} · Checklist
        </button>
      </div>
      <Problem message={actionError} />

      {tab < checklistTab && (() => {
        const step = steps[tab];
        const isIt = /IT/.test(step.name) && onboarding.itStatus; // the IT request shows where IT is
        return (
          <div>
            {isIt && (
              <div className="status-line">
                <span className="sl-label">IT request</span>
                <Badge kind={IT_STATUS[onboarding.itStatus]?.[0]}>{IT_STATUS[onboarding.itStatus]?.[1]}</Badge>
                {onboarding.itSentAt && <span className="sl-meta">sent {day(onboarding.itSentAt)}</span>}
              </div>
            )}
            {step.taskId
              ? <TaskForm key={step.taskId} taskId={step.taskId} me={me} embedded onDone={stepDone(tab)} onCancel={cancel} />
              : <div className="card empty">This step opens when the step before it is done.</div>}
          </div>
        );
      })()}

      {tab === checklistTab && (
        <div>
          {items.length === 0 ? (
            <div className="card empty">The checklist is created when Employee details are saved with Save &amp; continue.</div>
          ) : (
            <>
              <ChecklistProgress items={items}>
                {isHr && employee.stage === 'ONBOARDING' && <button className="btn btn-primary btn-xs" onClick={() => setShowAdd(true)}>+ Add item</button>}
              </ChecklistProgress>
              <ChecklistSections items={items} onChanged={reload}
                                 canTick={(item) => isHr && employee.stage === 'ONBOARDING'} />
            </>
          )}
          <div className="action-row">
            <div className="action-row-left"><button className="btn btn-cancel" onClick={cancel}>Cancel</button></div>
            <div className="action-row-right">
              {isHr && (
                <button className="btn btn-primary" disabled={!data.canComplete || busy} onClick={complete}
                        title={data.canComplete ? '' : 'All mandatory items must be done first'}>
                  Complete onboarding
                </button>
              )}
            </div>
          </div>
        </div>
      )}
      {showAdd && <AddItemModal employeeId={employeeId} onClose={(saved) => { setShowAdd(false); if (saved) reload(); }} />}
    </section>
  );
}

/** "+ Add item" (Screen 5): its form comes from the backend, rendered like a task form. */
function AddItemModal({ employeeId, onClose }) {
  const [model, error] = useLoad('/onboarding/forms/checklist-item');
  const [initial] = useState({ mandatory: true }); // given to JSONForms once; changes arrive in data
  const [data, setData] = useState(initial);
  const schema = useMemo(() => model && (({ title, ...rest }) => rest)(model.schema), [model]);
  const [errors, setErrors] = useState([]);
  const [submitted, setSubmitted] = useState(false);
  const [saveError, setSaveError] = useState(null);
  const save = () => {
    setSubmitted(true);
    if (errors.length > 0) return;
    api('POST', `/onboarding/${employeeId}/checklist`, data).then(() => onClose(true)).catch((e) => setSaveError(e.message));
  };
  return (
    <div className="modal-bg" onClick={(e) => e.target === e.currentTarget && onClose(false)}>
      <div className="modal" role="dialog" aria-modal="true" aria-label="Checklist item">
        <div className="modal-hd"><span>{model?.schema?.title || 'Checklist item'}</span>
          <button className="modal-close" onClick={() => onClose(false)} aria-label="Close">×</button></div>
        <div className="modal-body wf-form">
          <Problem message={error || saveError} />
          {model && (
            <ThemeProvider theme={theme}>
              <JsonForms schema={schema} uischema={model.uiSchema} data={initial}
                         renderers={renderers} cells={materialCells}
                         validationMode={submitted ? 'ValidateAndShow' : 'ValidateAndHide'}
                         onChange={({ data: d, errors: found }) => { setData(d); setErrors(found ?? []); }} />
            </ThemeProvider>
          )}
        </div>
        <div className="modal-ft">
          <div className="ft-l"><button className="btn btn-cancel" onClick={() => onClose(false)}>Cancel</button></div>
          <div className="ft-r"><button className="btn btn-primary" onClick={save}>{model?.submitLabel || 'Save'}</button></div>
        </div>
      </div>
    </div>
  );
}

/** The new joiner's dashboard (Screen 9): own onboarding items only (ONB-07), team and equipment. */
export function EmployeeDashboard({ me }) {
  const [data, error, reload] = useLoad('/onboarding/me');
  if (error) return <Problem message={error} />;
  if (!data) return <p className="muted">Loading…</p>;
  const first = (me?.name || '').split(' ')[0];
  const items = data.items || [];
  return (
    <section>
      <Flash />
      <div className="page-header">
        <div>
          <div className="page-title">Welcome to CubeWorks{first ? `, ${first}` : ''}!</div>
          <div className="page-subtitle">{data.employee?.stage === 'ONBOARDING' ? 'Your onboarding' : 'Your overview'}</div>
        </div>
      </div>
      <div className="grid-main-side">
        <div className="col">
          {items.length > 0 ? (
            <>
              <ChecklistProgress items={items} />
              <ChecklistSections items={items} onChanged={reload} showOwnerInHeader={false} canTick={() => true} />
            </>
          ) : (
            <div className="card empty">No onboarding tasks. Your tasks are under <a className="lnk" href="#/tasks">My Tasks</a>.</div>
          )}
        </div>
        <div className="col">
          <div className="card">
            <div className="card-title">Your team</div>
            <dl className="kv">
              <dt>Job title</dt><dd>{data.team?.jobTitle || '—'}</dd>
              <dt>Department</dt><dd>{data.team?.department || '—'}</dd>
              <dt>Manager</dt><dd>{data.team?.manager || '—'}</dd>
              <dt>Buddy</dt><dd>{data.team?.buddy || '—'}</dd>
            </dl>
          </div>
        </div>
      </div>
      <div className="card">
        <div className="card-title">My equipment</div>
        {(data.assets || []).length === 0 ? <p className="muted">No equipment issued yet.</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Item</th><th>Asset no.</th><th>Issued</th><th className="col-center">Received</th></tr></thead>
              <tbody>
                {data.assets.map((a) => (
                  <tr key={a.assetNumber}>
                    <td>{ASSET_TYPE[a.assetType] || a.assetType} · {a.model}</td>
                    <td>{a.assetNumber}</td>
                    <td>{day(a.issuedOn)}</td>
                    <td className="col-center">{a.received ? '✓' : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}
