-- V6: Admin profile (email), preferences storage, terminal management

-- Add email to admin_users
ALTER TABLE admin_users ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE admin_users ADD COLUMN IF NOT EXISTS display_name VARCHAR(100);

-- Admin preferences (notification, display, system settings per user)
CREATE TABLE IF NOT EXISTS admin_preferences (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id      UUID NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    pref_key      VARCHAR(100) NOT NULL,
    pref_value    TEXT,
    updated_at    TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (admin_id, pref_key)
);

CREATE INDEX IF NOT EXISTS idx_admin_preferences_admin_id ON admin_preferences(admin_id);

-- Index for terminal heartbeat lookups (latest per terminal)
CREATE INDEX IF NOT EXISTS idx_heartbeat_terminal_reported
    ON terminal_heartbeats(terminal_id, reported_at DESC);
