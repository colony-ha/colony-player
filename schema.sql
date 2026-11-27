PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL UNIQUE,
  device_key_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  trial_ends_at INTEGER NOT NULL,
  license_ends_at INTEGER,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','blocked','revoked'))
);

CREATE TABLE IF NOT EXISTS device_sessions (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_device_sessions_token ON device_sessions(token_hash);
CREATE INDEX IF NOT EXISTS idx_device_sessions_expiry ON device_sessions(expires_at);

CREATE TABLE IF NOT EXISTS playlists (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  playlist_type TEXT NOT NULL CHECK (playlist_type IN ('m3u','xtream')),
  encrypted_url TEXT NOT NULL,
  iv TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_playlists_device ON playlists(device_id, created_at);
