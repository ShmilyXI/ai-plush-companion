CREATE TABLE IF NOT EXISTS ai_screen_scene (
  id VARCHAR(64) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  device_id VARCHAR(128) NOT NULL,
  scene_id VARCHAR(128) NOT NULL,
  version INT NOT NULL DEFAULT 1,
  mode VARCHAR(32) NOT NULL,
  scene_json LONGTEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  KEY idx_screen_scene_device (user_id, device_id, updated_at)
);
