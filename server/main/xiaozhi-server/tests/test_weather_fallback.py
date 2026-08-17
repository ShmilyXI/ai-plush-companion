import asyncio
import sys
import types
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

bs4_module = types.ModuleType("bs4")
bs4_module.BeautifulSoup = object
sys.modules.setdefault("bs4", bs4_module)

from plugins_func.functions.get_weather import get_weather
from plugins_func.register import Action


class WeatherFallbackTest(unittest.TestCase):
    def test_weather_falls_back_when_qweather_request_raises(self):
        conn = SimpleNamespace(
            config={"plugins": {"get_weather": {"default_location": "深圳"}}},
            client_ip=None,
        )
        fallback_report = "您查询的位置是：深圳\n当前天气：晴，气温 30℃"

        with patch(
            "plugins_func.functions.get_weather.fetch_city_info",
            new=AsyncMock(side_effect=RuntimeError("network unavailable")),
        ), patch(
            "plugins_func.functions.get_weather.fetch_open_meteo_weather",
            new=AsyncMock(return_value=fallback_report),
        ) as fallback:
            result = asyncio.run(get_weather(conn, location="深圳"))

        fallback.assert_awaited_once_with("深圳", "zh_CN")
        self.assertEqual(fallback_report, result.result)

    def test_weather_falls_back_to_open_meteo_when_qweather_auth_fails(self):
        conn = SimpleNamespace(
            config={
                "plugins": {
                    "get_weather": {
                        "api_host": "invalid.example",
                        "api_key": "expired",
                        "default_location": "深圳",
                    }
                }
            },
            client_ip=None,
        )
        fallback_report = "您查询的位置是：回归测试城甲\n未来7天预报：\n明天：晴，气温 25~31℃"

        with patch(
            "plugins_func.functions.get_weather.fetch_city_info",
            new=AsyncMock(return_value=None),
        ), patch(
            "plugins_func.functions.get_weather.fetch_open_meteo_weather",
            new=AsyncMock(return_value=fallback_report),
        ) as fallback:
            result = asyncio.run(get_weather(conn, location="回归测试城甲"))

        fallback.assert_awaited_once_with("回归测试城甲", "zh_CN")
        self.assertEqual(Action.REQLLM, result.action)
        self.assertEqual(fallback_report, result.result)

    def test_weather_accepts_city_alias_from_model_tool_call(self):
        conn = SimpleNamespace(
            config={"plugins": {"get_weather": {"default_location": "深圳"}}},
            client_ip=None,
        )
        fallback_report = "您查询的位置是：回归测试城乙\n未来7天预报：\n明天：晴，气温 25~31℃"

        with patch(
            "plugins_func.functions.get_weather.fetch_city_info",
            new=AsyncMock(return_value=None),
        ), patch(
            "plugins_func.functions.get_weather.fetch_open_meteo_weather",
            new=AsyncMock(return_value=fallback_report),
        ) as fallback:
            result = asyncio.run(get_weather(conn, city="回归测试城乙"))

        fallback.assert_awaited_once_with("回归测试城乙", "zh_CN")
        self.assertEqual(fallback_report, result.result)


if __name__ == "__main__":
    unittest.main()
