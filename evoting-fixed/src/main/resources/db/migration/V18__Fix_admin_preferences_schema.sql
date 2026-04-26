-- Drop the incorrect "wide" table we made in V17
DROP TABLE IF EXISTS admin_preferences;

-- Create the correct Key-Value table expected by AdminController
CREATE TABLE admin_preferences (
    admin_id UUID NOT NULL,
    pref_key VARCHAR(255) NOT NULL,
    pref_value TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- The composite primary key is required for the ON CONFLICT clause to work
    PRIMARY KEY (admin_id, pref_key)
);