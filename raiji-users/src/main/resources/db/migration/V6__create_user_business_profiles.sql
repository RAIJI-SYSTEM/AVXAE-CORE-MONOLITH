CREATE TABLE IF NOT EXISTS user_business_profiles(
    sub VARCHAR(64) PRIMARY KEY,
    phone VARCHAR(20),
    photo_url VARCHAR(500),
    update_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);