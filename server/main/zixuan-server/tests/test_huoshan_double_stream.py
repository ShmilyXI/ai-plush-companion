import sys
import types
import unittest
from enum import Enum
from unittest.mock import AsyncMock, MagicMock, patch


class InterfaceType(Enum):
    DUAL_STREAM = "dual_stream"


class SentenceType(Enum):
    FIRST = "first"


class ContentType(Enum):
    TEXT = "text"
    FILE = "file"


class TTSProviderBase:
    def __init__(self, config, delete_audio_file):
        self.delete_audio_file = delete_audio_file
        self.tts_timeout = int(config.get("tts_timeout", 15))


logger = MagicMock()
logger.bind.return_value = logger

websockets_module = types.ModuleType("websockets")
websockets_module.connect = AsyncMock()
sys.modules.setdefault("websockets", websockets_module)

logger_module = types.ModuleType("config.logger")
logger_module.setup_logging = lambda: logger
sys.modules.setdefault("config.logger", logger_module)

base_module = types.ModuleType("core.providers.tts.base")
base_module.TTSProviderBase = TTSProviderBase
sys.modules.setdefault("core.providers.tts.base", base_module)

util_module = types.ModuleType("core.utils.util")
util_module.check_model_key = lambda *_: None
sys.modules.setdefault("core.utils.util", util_module)

tts_module = types.ModuleType("core.utils.tts")
tts_module.MarkdownCleaner = object
tts_module.convert_percentage_to_range = lambda value, **_: value
sys.modules.setdefault("core.utils.tts", tts_module)

dto_module = types.ModuleType("core.providers.tts.dto.dto")
dto_module.SentenceType = SentenceType
dto_module.ContentType = ContentType
dto_module.InterfaceType = InterfaceType
sys.modules.setdefault("core.providers.tts.dto.dto", dto_module)


class HuoshanDoubleStreamVersionTest(unittest.IsolatedAsyncioTestCase):
    async def test_uses_shared_endpoint_and_version_resource_header(self):
        from core.providers.tts.huoshan_double_stream import TTSProvider

        endpoint = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
        for resource_id, report_on_last in (
            ("seed-tts-1.0", False),
            ("seed-tts-2.0", True),
        ):
            provider = TTSProvider({
                "appid": "app-id",
                "access_token": "access-token",
                "resource_id": resource_id,
                "ws_url": endpoint,
                "speaker": "voice-code",
            }, True)
            provider._cancel_monitor_task = AsyncMock()
            provider._start_monitor_tts_response = AsyncMock()

            connection = AsyncMock()
            with patch(
                "core.providers.tts.huoshan_double_stream.websockets.connect",
                new_callable=AsyncMock,
                return_value=connection,
            ) as connect:
                actual = await provider._ensure_connection()

            self.assertIs(connection, actual)
            self.assertEqual(endpoint, connect.call_args.args[0])
            self.assertEqual(
                resource_id,
                connect.call_args.kwargs["additional_headers"]["X-Api-Resource-Id"],
            )
            self.assertIs(report_on_last, provider.report_on_last)


if __name__ == "__main__":
    unittest.main()
