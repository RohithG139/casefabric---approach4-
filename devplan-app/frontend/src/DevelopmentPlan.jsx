import React, { useEffect, useState } from 'react';
import { api } from './api.js';
import { Badge, OPEN_TASK, Problem, StateBadge, TASK_STATE, ago, caseRolesOf, day, setFlash, useLoad } from './shared.jsx';
import TaskForm from './TaskForm.jsx';

const ROUND_STATUS = {
  DRAFT: ['warning', 'Draft'], SUBMITTED: ['info', 'Waiting for approval'], REJECTED: ['error', 'Sent back'],
  APPROVED: ['info', 'Approved'], EVALUATING: ['info', 'Evaluating'], CLOSED: ['success', 'Closed'],
};

/**
 * One employee's development plan, in the onboarding wizard's look. The step tabs are the stages inside the case
 * model's "Development plan" stage (1 · Plan, 2 · Perform plan, 3 · Evaluate plan), each with the tasks of its latest
 * run rendered as their engine forms. The last tab is the plan record: every round straight from the case file
 * (closed rounds from PreviousRounds, the running one live), newest first.
 */
export default function DevelopmentPlan({ caseId, me }) {
  const [data, error, reload] = useLoad(`/cases/${caseId}/plan`);
  const [tab, setTab] = useState(null);
  const [taskId, setTaskId] = useState(null);
  const [actionError, setActionError] = useState(null);
  const [busy, setBusy] = useState(false);
  const roles = caseRolesOf(me);

  // Open on the running step; the record when no step runs.
  useEffect(() => {
    if (!data || tab !== null) return;
    const running = data.steps.findIndex((s) => s.state === 'Active');
    setTab(running >= 0 ? running : data.steps.length);
  }, [data, tab]);
  // In a step, open the first task waiting for this user.
  useEffect(() => {
    if (!data || tab === null || tab >= data.steps.length) { setTaskId(null); return; }
    const mine = data.steps[tab].tasks.find((t) => OPEN_TASK.includes(t.taskState) && roles.includes(t.role));
    setTaskId(mine ? mine.id : null);
  }, [data, tab]); // eslint-disable-line react-hooks/exhaustive-deps

  if (error) return <Problem message={error} />;
  if (!data || tab === null) return <p className="muted">Loading the development plan…</p>;
  const { steps, record, employee } = data;
  const recordTab = steps.length;
  const step = steps[tab];

  const addActivity = (item) => {
    setActionError(null);
    setBusy(true);
    api('POST', `/cases/${caseId}/plan`, item)
      .then(() => { setFlash(`${item.name}: added. Fill it in below.`); reload(); })
      .catch((e) => setActionError(e.message)).finally(() => setBusy(false));
  };
  const taskDone = (task) => { setFlash(`${task.taskName}: done.`); setTaskId(null); setTab(null); reload(); };

  return (
    <section>
      <a className="back-link" href={`#/cases/${caseId}`}>← {employee?.name ? `${employee.name}'s case` : 'Back to the case'}</a>
      <div className="page-header">
        <div>
          <div className="page-title">Development plan</div>
          <div className="page-subtitle">{[employee?.name, data.round > 0 && `Round ${data.round}`].filter(Boolean).join(' · ')}</div>
        </div>
      </div>

      <div className="bcrumb steptabs" role="tablist">
        {steps.map((s, i) => (
          <React.Fragment key={s.planItemId}>
            <button type="button" role="tab" aria-selected={tab === i}
                    className={`step${tab === i ? ' cur' : s.state === 'Completed' ? ' done' : ''}`}
                    onClick={() => setTab(i)}>{s.name}</button>
            <span className="arrow">→</span>
          </React.Fragment>
        ))}
        <button type="button" role="tab" aria-selected={tab === recordTab}
                className={`step${tab === recordTab ? ' cur' : ''}`} onClick={() => setTab(recordTab)}>Plan record</button>
      </div>
      <Problem message={actionError} />

      {step && (
        <div>
          <div className="card">
            <div className="card-title card-title-row"><span>{step.name}</span><StateBadge state={step.state} /></div>
            {step.tasks.length === 0 && (
              <p className="muted">{step.state === 'Available' ? 'This step has not started yet.' : 'No tasks in this step yet.'}</p>
            )}
            {step.tasks.length > 0 && (
              <div className="task-list">
                {step.tasks.map((t) => {
                  const open = OPEN_TASK.includes(t.taskState);
                  return (
                    <button key={t.id} type="button" className={`task-row${taskId === t.id ? ' selected' : ''}`} onClick={() => setTaskId(t.id)}>
                      <span><Badge kind={open ? 'info' : 'success'}>{open ? 'Open' : 'Done'}</Badge></span>
                      <span><div className="task-title">{t.taskName}</div>
                        <div className="task-sub">{t.role} · {TASK_STATE[t.taskState] || t.taskState}</div></span>
                      <span className="task-due">{ago(t.lastModified || t.createdOn)}</span>
                      <span>→</span>
                    </button>
                  );
                })}
              </div>
            )}
            {step.discretionaryItems.length > 0 && (
              <>
                <div className="section-h">Add an activity</div>
                <div className="chips">
                  {step.discretionaryItems.map((d) => (
                    <button key={d.definitionId} className="btn btn-secondary btn-sm" disabled={busy} onClick={() => addActivity(d)}>+ {d.name}</button>
                  ))}
                </div>
              </>
            )}
          </div>
          {taskId && <TaskForm key={taskId} taskId={taskId} me={me} embedded onDone={taskDone} onCancel={() => setTaskId(null)} />}
        </div>
      )}

      {tab === recordTab && <PlanRecord record={record} />}
    </section>
  );
}

/** The goal result for one goal from a self-assessment's or evaluation's goalResults, by title. */
const resultFor = (results, title) => (results || []).find((r) => r.goalTitle === title);

/** Every plan round straight from the case file (closed rounds from PreviousRounds, the running one from the live
 * case data), newest first. */
function PlanRecord({ record }) {
  const [index, setIndex] = useState(0);
  const rounds = record.rounds;
  if (rounds.length === 0) return <div className="card empty">No plan has been sent yet.</div>;
  const round = rounds[Math.min(index, rounds.length - 1)];
  const [kind, text] = ROUND_STATUS[round.status] || ['neutral', round.status];
  const goals = round.plan?.goals || [];
  const activities = round.activities || [];
  const reviews = round.progressReviews || [];

  return (
    <div>
      <div className="chips round-chips">
        {rounds.map((r, i) => (
          <button key={r.roundNo} type="button" className={`btn btn-sm ${i === index ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setIndex(i)}>
            Round {r.roundNo}{r.period ? ` · ${r.period}` : ''}
          </button>
        ))}
      </div>
      <div className="status-line">
        <span className="sl-label">Round {round.roundNo}</span>
        <Badge kind={kind}>{text}</Badge>
      </div>

      <div className="card">
        <div className="card-title">Goals</div>
        {goals.length === 0 ? <p className="muted">No goals yet.</p> : (
          <ul className="plain">
            {goals.map((g, i) => {
              const self = resultFor(round.selfAssessment?.goalResults, g.title);
              const evaluated = resultFor(round.evaluation?.goalResults, g.title);
              return (
                <li key={i}>
                  <b>{g.title}</b> <span className="muted">· {g.type} · by {day(g.targetDate)}</span>
                  {g.successCriteria && <div className="cell-sub">{g.successCriteria}</div>}
                  {(self || evaluated) && (
                    <div className="cell-sub">
                      {self && `Self: ${self.result}`}{self && evaluated && ' · '}{evaluated && `Evaluation: ${evaluated.result}`}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </div>

      {round.approval?.decision && (
        <div className="card">
          <div className="card-title">Manager's decision</div>
          <p>{round.approval.decision}{round.approval.comments ? ` — ${round.approval.comments}` : ''}</p>
        </div>
      )}

      {activities.length > 0 && (
        <div className="card">
          <div className="card-title">Activities</div>
          <ul className="plain">
            {activities.map((a, i) => <li key={i}>{a.description} <span className="muted">· {a.type} · {a.status}</span></li>)}
          </ul>
        </div>
      )}

      {reviews.length > 0 && (
        <div className="card">
          <div className="card-title">Progress reviews</div>
          <ul className="plain">
            {reviews.map((r, i) => <li key={i}>{day(r.date)}: {r.onTrack ? 'on track' : 'not on track'}{r.notes ? ` — ${r.notes}` : ''}</li>)}
          </ul>
        </div>
      )}

      {round.evaluation?.summary && (
        <div className="card">
          <div className="card-title">Evaluation summary</div>
          <p>{round.evaluation.summary}</p>
        </div>
      )}
    </div>
  );
}
