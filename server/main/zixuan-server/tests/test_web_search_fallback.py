import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from plugins_func.functions.web_search import web_search
from plugins_func.register import Action


class WebSearchFallbackTest(unittest.TestCase):
    def test_search_without_api_key_uses_public_fallback(self):
        conn = SimpleNamespace(
            config={
                "plugins": {
                    "web_search": {
                        "provider": "metaso",
                        "max_results": 5,
                    }
                }
            }
        )
        search_report = "【联网搜索结果】\n1. 标题：OpenAI 发布新模型"

        with patch(
            "plugins_func.functions.web_search._search_bing_rss",
            new=AsyncMock(return_value=search_report),
        ) as fallback:
            result = asyncio.run(web_search(conn, query="OpenAI 最新模型"))

        fallback.assert_awaited_once_with("OpenAI 最新模型", 5)
        self.assertEqual(Action.REQLLM, result.action)
        self.assertEqual(search_report, result.result)

    def test_search_fallback_failure_returns_fixed_response(self):
        conn = SimpleNamespace(
            config={"plugins": {"web_search": {"max_results": 3}}}
        )

        with patch(
            "plugins_func.functions.web_search._search_bing_rss",
            new=AsyncMock(side_effect=RuntimeError("network unavailable")),
        ):
            result = asyncio.run(web_search(conn, query="OpenAI 最新模型"))

        self.assertEqual(Action.RESPONSE, result.action)
        self.assertEqual(
            "联网搜索暂时不可用，请稍后再试。",
            result.response,
        )


if __name__ == "__main__":
    unittest.main()
