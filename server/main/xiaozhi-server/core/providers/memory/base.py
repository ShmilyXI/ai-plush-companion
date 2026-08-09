from abc import ABC, abstractmethod
from config.logger import setup_logging

TAG = __name__
logger = setup_logging()


class MemoryProviderBase(ABC):
    def __init__(self, config):
        self.config = config
        self.memory_namespace = None
        self.role_id = None
        self.source_metadata = {}

    def set_llm(self, llm):
        self.llm = llm

    @abstractmethod
    async def save_memory(self, msgs, session_id=None):
        """Save a new memory for specific role and return memory ID"""
        print("this is base func", msgs)

    @abstractmethod
    async def query_memory(self, query: str) -> str:
        """Query memories for specific role based on similarity"""
        return "please implement query method"

    def init_memory(self, memory_namespace, llm, **kwargs):
        self.memory_namespace = memory_namespace
        self.role_id = memory_namespace
        self.llm = llm
        metadata = kwargs.get("source_metadata")
        self.source_metadata = dict(metadata) if isinstance(metadata, dict) else {}

    async def clear_memory(self) -> bool:
        return False

    async def list_memory_items(self) -> list[dict]:
        return []

    async def update_memory_item(self, memory_id: str, content: str) -> bool:
        return False

    async def delete_memory_item(self, memory_id: str) -> bool:
        return False

    def get_management_summary(self):
        return None
