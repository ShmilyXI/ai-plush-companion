"""服务端 MCP 工具模块。"""

__all__ = ["ServerMCPManager", "ServerMCPExecutor", "ServerMCPClient"]


def __getattr__(name):
    if name == "ServerMCPManager":
        from .mcp_manager import ServerMCPManager

        return ServerMCPManager
    if name == "ServerMCPExecutor":
        from .mcp_executor import ServerMCPExecutor

        return ServerMCPExecutor
    if name == "ServerMCPClient":
        from .mcp_client import ServerMCPClient

        return ServerMCPClient
    raise AttributeError(name)
