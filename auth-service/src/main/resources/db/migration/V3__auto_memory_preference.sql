-- User-facing switch for automatic long-term memory. Existing users keep the
-- historical behavior: automatic recall and background extraction stay enabled
-- until the user turns the setting off.

ALTER TABLE user_preferences
    ADD COLUMN auto_memory_enabled BOOLEAN NOT NULL DEFAULT TRUE;
