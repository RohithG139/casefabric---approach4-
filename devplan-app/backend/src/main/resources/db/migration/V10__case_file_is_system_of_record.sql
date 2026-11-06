-- The case file is now the system of record for business content (development plan, equipment, checklist progress);
-- the app DB keeps only personal data (employee) and shared, cross-case configuration (department, job_title,
-- checklist_default_item). Everything below was business content with no personal data in it, now held in the case
-- file instead (development.type's Plan/Approval/Activities/ProgressReviews/SelfAssessment/Evaluation/PreviousRounds,
-- and Onboarding's ITRequest/Assets/Checklist/status fields).
DROP TABLE dev_round_task;
DROP TABLE dev_progress_review;
DROP TABLE dev_activity;
DROP TABLE dev_goal;
DROP TABLE dev_round;
DROP TABLE checklist_item;
DROP TABLE asset;
DROP TABLE onboarding;
