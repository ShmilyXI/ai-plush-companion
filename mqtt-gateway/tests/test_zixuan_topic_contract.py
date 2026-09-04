import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def run_node(script: str):
    return subprocess.run(
        ["node", "-e", script],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def test_topic_helpers_build_only_zixuan_namespaces():
    result = run_node(
        "const t=require('./product-identity');"
        "process.stdout.write(JSON.stringify({"
        "up:t.MQTT_UPLINK_TOPIC,down:t.deviceDownlinkTopic('aa_bb')}));"
    )

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == {
        "up": "zixuan/device-server",
        "down": "zixuan/devices/p2p/aa_bb",
    }


def test_topic_helpers_reject_retired_and_foreign_topics():
    result = run_node(
        "const t=require('./product-identity');"
        "const out=["
        "t.isDeviceUplinkTopic('device-server'),"
        "t.isDeviceDownlinkTopic('devices/p2p/aa_bb','aa_bb'),"
        "t.isDeviceUplinkTopic('foreign/device-server'),"
        "t.isDeviceDownlinkTopic('zixuan/devices/p2p/bb_cc','aa_bb')];"
        "process.stdout.write(JSON.stringify(out));"
    )

    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout) == [False, False, False, False]


def test_gateway_checks_topics_before_routing_messages():
    source = (ROOT / "app.js").read_text(encoding="utf-8")

    assert "if (!isDeviceUplinkTopic(publishData.topic))" in source
    assert "if (!isDeviceDownlinkTopic(subscribeData.topic, this.deviceIdSafe))" in source
    assert "this.replyTo = deviceDownlinkTopic(this.deviceIdSafe);" in source
