import unittest

from core.capabilities.models import CapabilityBundle
from core.conversation.contract import ConversationInput, ConversationRequest


class ConversationRuntimeContractTest(unittest.TestCase):
    def test_request_is_immutable_and_captures_runtime_identity(self):
        request = ConversationRequest(
            user_id="user-a",
            profile_id="profile-a",
            conversation_id="conversation-a",
            source="app",
            input_mode="text",
            output_mode="text",
            text="你好",
        )

        with self.assertRaises(Exception):
            request.profile_id = "profile-b"
        self.assertEqual("app", request.source)

    def test_input_rejects_unknown_kinds(self):
        with self.assertRaises(ValueError):
            ConversationInput(kind="video", request_id="request-a")


if __name__ == "__main__":
    unittest.main()
