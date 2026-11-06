-- The "Documents & admin" checklist items are acknowledgements HR records on the employee's behalf, not something
-- the new joiner ticks themselves.
UPDATE checklist_default_item SET owner_role = 'HR' WHERE section = 'DOCUMENTS_ADMIN' AND owner_role = 'EMPLOYEE';
