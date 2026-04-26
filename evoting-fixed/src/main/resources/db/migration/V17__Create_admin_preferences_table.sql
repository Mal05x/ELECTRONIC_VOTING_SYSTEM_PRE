CREATE TABLE admin_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id UUID NOT NULL UNIQUE, -- Links these preferences to a specific admin

    -- Notifications
    email_vote_cast BOOLEAN DEFAULT true,
    email_enrollment BOOLEAN DEFAULT true,
    email_tamper BOOLEAN DEFAULT true,
    email_auth BOOLEAN DEFAULT false,
    in_app_all BOOLEAN DEFAULT true,
    sms_alerts BOOLEAN DEFAULT false,

    -- Display
    density VARCHAR(20) DEFAULT 'comfortable',
    date_format VARCHAR(20) DEFAULT 'dd/MM/yyyy',
    language VARCHAR(10) DEFAULT 'en-NG',
    font_size VARCHAR(10) DEFAULT 'base',

    -- Session
    auto_logout INTEGER DEFAULT 30,
    dual_approval BOOLEAN DEFAULT false,
    session_logs BOOLEAN DEFAULT true,

    -- Terminals
    heartbeat INTEGER DEFAULT 30,
    offline_thresh INTEGER DEFAULT 120,
    auto_lock BOOLEAN DEFAULT true,

    -- System
    audit_retention INTEGER DEFAULT 365,
    backup_schedule VARCHAR(20) DEFAULT 'daily',
    merkle_publish BOOLEAN DEFAULT true,
    liveness_fail_open BOOLEAN DEFAULT false,

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);