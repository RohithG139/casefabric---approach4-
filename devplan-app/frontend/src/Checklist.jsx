import React, { useState } from 'react';
import { api } from './api.js';
import { Badge, Problem, shortDay } from './shared.jsx';

const SECTIONS = [
  ['DOCUMENTS_ADMIN', 'Documents & admin'],
  ['EQUIPMENT_ISSUED', 'Equipment issued'],
  ['ACCOUNTS_INSTALLATIONS', 'Accounts & installations'],
  ['ORIENTATION', 'Orientation'],
];
const OWNER = { HR: 'HR', IT_ADMIN: 'IT admin', MANAGER: 'Manager', EMPLOYEE: 'Employee' };

/** The progress bar of a checklist: "x of y items · a of b mandatory". */
export function ChecklistProgress({ items, children }) {
  const done = items.filter((i) => i.status === 'DONE').length;
  const mandatory = items.filter((i) => i.mandatory);
  const mandatoryDone = mandatory.filter((i) => i.status === 'DONE').length;
  const percent = items.length ? Math.round((done / items.length) * 100) : 0;
  return (
    <div className="card">
      <div className="card-title card-title-row"><span>Progress</span><span className="card-actions">{children}</span></div>
      <div className="prog-hd">
        <span>{percent}% complete</span>
        <span>{done} of {items.length} items · {mandatoryDone} of {mandatory.length} mandatory</span>
      </div>
      <div className="progress"><span style={{ width: `${percent}%` }} /></div>
    </div>
  );
}

/**
 * Checklist items by section, as in WF-02: tick box, name (* = mandatory), owner, done date, status. A tick is saved
 * at once (ONB-04). canTick(item) decides per item; the backend checks it again. onChanged(item) follows a tick.
 */
export function ChecklistSections({ items, canTick, onChanged, showOwnerInHeader = true }) {
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const tick = (item) => {
    setBusy(item.id);
    setError(null);
    api('POST', `/onboarding/checklist/${item.id}/done`)
      .then(() => onChanged && onChanged(item))
      .catch((e) => setError(e.message))
      .finally(() => setBusy(null));
  };
  return (
    <>
      <Problem message={error} />
      {SECTIONS.map(([key, name]) => {
        const inSection = items.filter((i) => i.section === key);
        if (inSection.length === 0) return null;
        const owners = [...new Set(inSection.map((i) => OWNER[i.ownerRole]))].join(' / ');
        const done = inSection.filter((i) => i.status === 'DONE').length;
        return (
          <div className="cl-sec" key={key}>
            <div className="cl-hd">
              <span>{name}</span>
              <span className="cl-meta">{showOwnerInHeader ? `${owners} · ` : ''}{done} / {inSection.length}</span>
            </div>
            {inSection.map((item) => {
              const isDone = item.status === 'DONE';
              const allowed = !isDone && canTick(item);
              return (
                <div key={item.id} className={`cl-item${isDone ? ' done' : ''}`}>
                  <input type="checkbox" checked={isDone} disabled={!allowed || busy === item.id}
                         onChange={() => allowed && tick(item)} aria-label={item.name} />
                  <div>
                    <span className="cl-name">{item.name}</span>
                    {item.mandatory && <span className="req-star">*</span>}
                    {!isDone && item.itemKind === 'EQUIPMENT' && <span className="cl-extra">ticked when IT issues it</span>}
                  </div>
                  <span className="cl-owner">{OWNER[item.ownerRole]}</span>
                  <span className="cl-date">{isDone ? shortDay(item.doneAt) : ''}</span>
                  <span className="cl-status">{isDone ? <Badge kind="success">Done</Badge> : <Badge>Open</Badge>}</span>
                </div>
              );
            })}
          </div>
        );
      })}
    </>
  );
}
