-- "Confirm equipment received" is removed from the onboarding flow and the case model (no more pi_confirm_equipment
-- human task, no more EQUIPMENT_CONFIRM item kind). New joiners no longer get this default checklist item.
DELETE FROM checklist_default_item WHERE item_kind = 'EQUIPMENT_CONFIRM';
