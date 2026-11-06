import React, { useEffect, useMemo, useState } from 'react';
import { JsonForms } from '@jsonforms/react';
import { materialCells } from '@jsonforms/material-renderers';
import RjsfForm from '@rjsf/mui';
import validator from '@rjsf/validator-ajv8';
import { ThemeProvider } from '@mui/material/styles';
import { api } from './api.js';
import { OPEN_TASK, Problem, renderers, setFlash, theme, useLoad } from './shared.jsx';
import { ChecklistSections } from './Checklist.jsx';

/** True when the object, or anything nested in it, has one of the given keys. */
function hasProperties(object, ...names) {
  if (!object || typeof object !== 'object') return false;
  return Object.keys(object).some((key) => names.includes(key) || hasProperties(object[key], ...names));
}

/**
 * Which renderer a task-model asks for, decided as the CaseFabric Case UI does: no "schema" means no form; RJSF is the
 * default, JSONForms is used when the uiSchema has JSONForms layout keys and no RJSF keys.
 */
export function formKind(model) {
  if (!model || typeof model !== 'object' || !model.schema) return 'none';
  const ui = model.uiSchema;
  const rjsf = hasProperties(ui, 'ui:widget', 'ui:order', 'ui:readonly');
  return rjsf || !hasProperties(ui, 'type', 'elements', 'rule') ? 'rjsf' : 'jsonforms';
}

/** The schema with "$CURRENT_USER" values replaced by the signed-in user's id, as in the Case UI. */
function withCurrentUser(value, userId) {
  if (value === '$CURRENT_USER') return userId;
  if (Array.isArray(value)) return value.map((v) => withCurrentUser(v, userId));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, withCurrentUser(v, userId)]));
  }
  return value;
}

/**
 * Renders a task's form straight from its task-model (from the engine, with DB data merged in by the backend) and
 * completes the task with the whole form. Nothing here is task-specific: a field added to the human task model shows
 * up without changes to the app. The button text comes from the model's submitLabel.
 * A completed task whose DB data may still change (editable) gets "Save changes" for HR.
 *
 * embedded: inside another page (the onboarding wizard); onDone/onCancel then replace the navigation.
 */
export default function TaskForm({ taskId, me, embedded, onDone, onCancel }) {
  const [form, loadError, reload] = useLoad(`/tasks/${taskId}`);
  const [data, setData] = useState(null);
  const [errors, setErrors] = useState([]);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);
  const [busy, setBusy] = useState(false);
  // The role's checklist items shown with the task; a tick updates only these, so unsaved form input stays.
  const [checklist, setChecklist] = useState(null);
  useEffect(() => {
    setData(form?.formData ?? null);
    setChecklist(form?.checklist ?? null);
  }, [form]);
  const ticked = (item) => setChecklist((items) => items.map((i) => (i.id === item.id
    ? { ...i, status: 'DONE', doneAt: new Date().toISOString() } : i)));
  useEffect(() => { setSubmitted(false); setError(null); setNotice(null); }, [taskId]);
  // One schema object per loaded form: a new object on every render would make JSONForms start over.
  const userId = me?.userId;
  const prepared = useMemo(() => {
    const { title, description, ...schema } = withCurrentUser(form?.taskModel?.schema ?? {}, userId);
    return { title, description, schema };
  }, [form, userId]);
  if (loadError) return <Problem message={loadError} />;
  if (!form || !data) return <p className="muted">Loading the form…</p>;
  const task = form.task;
  const open = OPEN_TASK.includes(task.taskState);
  const canEditDone = !open && form.editable && me?.roles?.includes('hr');
  const writable = open || canEditDone;
  const model = form.taskModel;
  const kind = formKind(model);
  // The page shows the form's title and description itself, so the renderer gets the schema without them.
  const { title, description, schema } = prepared;
  const submitLabel = canEditDone ? 'Save changes' : model?.submitLabel || 'Submit';
  const backHref = form.editable ? '#/tasks' : `#/cases/${task.caseInstanceId}`;

  const run = (method, path, body, after) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    api(method, path, body).then(after).catch((e) => setError(e.message)).finally(() => setBusy(false));
  };
  const complete = (formData) => {
    if (canEditDone) {
      run('POST', `/tasks/${taskId}/business-data`, formData, () => { setNotice('Changes saved.'); reload(); });
      return;
    }
    run('POST', `/tasks/${taskId}/complete`, formData, () => {
      if (onDone) { onDone(task); return; }
      setFlash(`${task.taskName}: done.`);
      window.location.hash = backHref;
    });
  };
  const cancel = (event) => {
    event.preventDefault();
    if (onCancel) onCancel(); else window.location.hash = backHref;
  };

  // Validation messages stay hidden until the first submit attempt.
  const submitJsonForms = (event) => {
    event.preventDefault();
    setSubmitted(true);
    if (errors.length > 0) return;
    complete(data);
  };

  const actions = writable && (
    <div className="action-row">
      <div className="action-row-left">
        <button type="button" className="btn btn-cancel" onClick={cancel}>Cancel</button>
      </div>
      <div className="action-row-right">
        <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? 'Saving…' : submitLabel}</button>
      </div>
    </div>
  );
  const messages = (
    <>
      {submitted && errors.length > 0 && <Problem message="Some fields still need an answer. They are marked in red." />}
      <Problem message={error} />
      {notice && <div className="alert success" role="status">{notice}</div>}
    </>
  );

  return (
    <section className="task">
      {!embedded && <a className="back-link" href={backHref}>← {form.editable ? 'Back to My Tasks' : form.employeeName ? `${form.employeeName}'s case` : 'Back to the case'}</a>}
      <div className="page-header">
        <div>
          {embedded ? <h2 className="section-title">{title || task.taskName}</h2> : <h1 className="page-title">{title || task.taskName}</h1>}
          {!embedded && form.employeeName && <div className="page-subtitle">{form.employeeName}</div>}
        </div>
      </div>
      {description && <p className="lead">{description}</p>}
      {!open && <div className="alert info">{canEditDone ? 'This step is done. HR can still change its details.' : 'This task is done. You are looking at what was filled in.'}</div>}
      <ThemeProvider theme={theme}>
        {kind === 'rjsf' && (
          <RjsfForm schema={schema} uiSchema={model.uiSchema || {}} formData={data} validator={validator}
                    readonly={!writable || busy} noHtml5Validate showErrorList={false} liveValidate={submitted}
                    onChange={(e) => { setData(e.formData); setErrors(e.errors ?? []); }}
                    onError={(found) => { setSubmitted(true); setErrors(found); }}
                    onSubmit={(e) => { setErrors([]); complete(e.formData); }}>
            {/* Children replace RJSF's own submit button. */}
            {messages}
            {actions}
          </RjsfForm>
        )}
        {kind !== 'rjsf' && (
          <form className="wf-form" onSubmit={submitJsonForms} noValidate>
            {kind === 'jsonforms' && (
              <JsonForms schema={schema} uischema={model.uischema || model.uiSchema} data={data} renderers={renderers}
                         cells={materialCells} readonly={!writable || busy}
                         validationMode={submitted ? 'ValidateAndShow' : 'ValidateAndHide'}
                         onChange={({ data: changed, errors: found }) => { setData(changed); setErrors(found ?? []); }} />
            )}
            {kind === 'none' && <p className="muted">This task has no form. Press {submitLabel} to complete it.</p>}
            {checklist && checklist.length > 0 && (
              <div className="card mt-16">
                <div className="card-title">Your checklist items</div>
                <ChecklistSections items={checklist} canTick={() => open} onChanged={ticked} />
              </div>
            )}
            {messages}
            {actions}
          </form>
        )}
      </ThemeProvider>
    </section>
  );
}
