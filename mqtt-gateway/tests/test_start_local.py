import os
import stat
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


class StartLocalScriptTest(unittest.TestCase):
    def test_uses_project_containers_and_syncs_public_endpoints(self):
        gateway_root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temp_dir:
            bin_dir = Path(temp_dir)
            calls_file = bin_dir / "docker-calls"

            docker = bin_dir / "docker"
            docker.write_text(
                textwrap.dedent(
                    f"""\
                    #!/bin/sh
                    printf '%s\\n' "$*" >> {calls_file}
                    case "$*" in
                      *"inspect -f"*) printf 'MYSQL_ROOT_PASSWORD=secret\\n' ;;
                      *"server.mqtt_signature_key"*) printf 'ValidSecretKeyA\\n' ;;
                      *"server.secret"*) printf 'ServerSecretA\\n' ;;
                    esac
                    """
                )
            )
            npm = bin_dir / "npm"
            npm.write_text("#!/bin/sh\nprintf 'gateway-started\\n'\n")
            for executable in (docker, npm):
                executable.chmod(executable.stat().st_mode | stat.S_IEXEC)

            env = os.environ.copy()
            env["PATH"] = f"{bin_dir}:{env['PATH']}"
            env["PUBLIC_IP"] = "192.168.0.107"
            result = subprocess.run(
                [str(gateway_root / "start-local.sh")],
                cwd=gateway_root,
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("gateway-started", result.stdout)
            calls = calls_file.read_text()
            self.assertIn("ai-plush-companion-mysql", calls)
            self.assertNotIn("codex-companion-mysql-task12", calls)
            self.assertIn("server.ota", calls)
            self.assertIn("http://192.168.0.107:8002/zixuan/ota/", calls)
            self.assertIn("server.websocket", calls)
            self.assertIn("ws://192.168.0.107:8000/zixuan/v1/", calls)
            self.assertIn("server.http", calls)
            self.assertIn("http://192.168.0.107:8003", calls)
            self.assertIn("server.mqtt_gateway", calls)
            self.assertIn("192.168.0.107:1883", calls)
            self.assertIn("server.udp_gateway", calls)
            self.assertIn("192.168.0.107:8884", calls)
            self.assertIn("server.mqtt_manager_api", calls)
            self.assertIn("127.0.0.1:8007", calls)
            self.assertIn("ai-plush-companion-redis redis-cli HDEL sys:params", calls)


if __name__ == "__main__":
    unittest.main()
