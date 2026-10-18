import base64
import hashlib
import hmac
import json
import os
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[1]


class DeviceAuthContractTest(unittest.TestCase):
    def _node_validate(self, client_id, username, password, env):
        script = (
            "const {validateMqttCredentials}=require('./utils/mqtt_config_v2');"
            "try { validateMqttCredentials(process.argv[1], process.argv[2], process.argv[3]); "
            "process.stdout.write('ok'); } catch (e) { process.stdout.write(e.message); process.exitCode=2; }"
        )
        return subprocess.run(
            ["node", "-e", script, client_id, username, password],
            cwd=ROOT,
            env={**os.environ, **env},
            text=True,
            capture_output=True,
            check=False,
        )

    def test_missing_signature_key_fails_closed(self):
        result = self._node_validate("GID@@@aa_bb_cc_dd_ee_ff@@@uuid", "eA==", "x", {"MQTT_SIGNATURE_KEY": ""})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("缺少MQTT_SIGNATURE_KEY", result.stdout)

    def test_valid_signature_is_required_for_device_connection(self):
        key = "test-signature-key"
        client_id = "GID@@@aa_bb_cc_dd_ee_ff@@@uuid"
        username = base64.b64encode(json.dumps({"ip": "127.0.0.1"}).encode()).decode()
        password = base64.b64encode(hmac.new(key.encode(), f"{client_id}|{username}".encode(), hashlib.sha256).digest()).decode()
        result = self._node_validate(client_id, username, password, {"MQTT_SIGNATURE_KEY": key})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("ok", result.stdout)

    def test_gateway_does_not_log_credentials_or_accept_unsigned_legacy_ids(self):
        source = (ROOT / "app.js").read_text(encoding="utf-8")
        self.assertNotIn("password: this.password", source)
        self.assertIn("拒绝未签名的 legacy clientId", source)


if __name__ == "__main__":
    unittest.main()
