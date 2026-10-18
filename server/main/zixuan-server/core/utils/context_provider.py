import httpx
from typing import Dict, Any, List
from config.logger import setup_logging

TAG = __name__

class ContextDataProvider:
    """数据上下文填充，负责从配置的API获取数据"""
    
    def __init__(self, config: Dict[str, Any], logger=None):
        self.config = config
        self.logger = logger or setup_logging()
        self.context_data = ""

    def fetch_all(self, device_id: str) -> str:
        """获取所有配置的上下文数据"""
        context_providers = self.config.get("context_providers", [])
        if not context_providers:
            return ""

        formatted_lines = []
        for provider in context_providers:
            url = provider.get("url")
            headers = provider.get("headers", {})

            if not url:
                continue

            try:
                headers = headers.copy() if isinstance(headers, dict) else {}
                # 将 device_id 添加到请求头
                headers["device-id"] = device_id
                
                # 发送请求
                response = httpx.get(url, headers=headers, timeout=3)
                
                if response.status_code == 200:
                    result = response.json()
                    if isinstance(result, dict):
                        if result.get("code") == 0:
                            data = result.get("data")
                            # 格式化数据
                            if isinstance(data, dict):
                                for k, v in data.items():
                                    formatted_lines.append(f"- **{k}：** {v}")
                            elif isinstance(data, list):
                                for item in data:
                                    formatted_lines.append(f"- {item}")
                            else:
                                formatted_lines.append(f"- {data}")
                        else:
                            self.logger.bind(tag=TAG).warning(
                                f"动态上下文 API 返回错误码: {result.get('code')}"
                            )
                    else:
                        self.logger.bind(tag=TAG).warning("动态上下文 API 返回非对象 JSON")
                else:
                    self.logger.bind(tag=TAG).warning(
                        f"动态上下文 API 请求失败: status={response.status_code}"
                    )
            except Exception as e:
                self.logger.bind(tag=TAG).error(
                    f"获取动态上下文失败: {type(e).__name__}"
                )
        
        # 将所有格式化后的行拼接成一个字符串
        self.context_data = "\n".join(formatted_lines)
        if self.context_data:
            self.logger.bind(tag=TAG).debug(
                f"动态上下文条目数: {len(formatted_lines)}"
            )
        return self.context_data
