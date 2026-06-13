-- Allow portal-initiated interactions where no org user is the initiator.
-- When a client starts an interaction from the portal, created_by_user_id is NULL.
ALTER TABLE assisted_interactions
    ALTER COLUMN created_by_user_id DROP NOT NULL;
