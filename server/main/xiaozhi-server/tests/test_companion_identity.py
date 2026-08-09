import unittest

from core.companion.identity import CompanionIdentity


class CompanionIdentityTest(unittest.TestCase):
    def test_valid_identity_is_parsed(self):
        identity = CompanionIdentity.from_config({
            "companion_identity": {
                "user_id": 7,
                "agent_id": "agent-id",
                "device_id": "device-id",
                "memory_namespace": "companion:" + "a" * 64,
            }
        })
        self.assertEqual("device-id", identity.device_id)
        self.assertEqual("companion:" + "a" * 64, identity.memory_namespace)

    def test_incomplete_identity_returns_none(self):
        self.assertIsNone(CompanionIdentity.from_config({"companion_identity": {}}))

    def test_invalid_namespace_returns_none(self):
        config = {"companion_identity": {
            "user_id": 7,
            "agent_id": "agent-id",
            "device_id": "device-id",
            "memory_namespace": "device-id",
        }}
        self.assertIsNone(CompanionIdentity.from_config(config))


if __name__ == "__main__":
    unittest.main()
