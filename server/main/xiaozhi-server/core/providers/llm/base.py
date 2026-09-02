from abc import ABC, abstractmethod
import inspect
from config.logger import setup_logging

TAG = __name__
logger = setup_logging()

class LLMProviderBase(ABC):
    @abstractmethod
    def response(self, session_id, dialogue):
        """LLM response generator"""
        pass

    def response_no_stream(self, system_prompt, user_prompt, session_id="", **kwargs):
        # 构造对话格式
        dialogue = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ]
        result = ""
        response_kwargs = kwargs
        try:
            signature = inspect.signature(self.response)
            parameters = signature.parameters
            accepts_kwargs = any(
                parameter.kind == inspect.Parameter.VAR_KEYWORD
                for parameter in parameters.values()
            )
            if not accepts_kwargs:
                response_kwargs = {
                    key: value for key, value in kwargs.items() if key in parameters
                }
        except (TypeError, ValueError):
            response_kwargs = kwargs
        if response_kwargs:
            parts = self.response(session_id, dialogue, **response_kwargs)
        else:
            parts = self.response(session_id, dialogue)
        for part in parts:
            result += part
        return result
    
    def response_with_functions(self, session_id, dialogue, functions=None):
        """
        Default implementation for function calling (streaming)
        This should be overridden by providers that support function calls

        Returns: generator that yields either text tokens or a special function call token
        """
        # For providers that don't support functions, just return regular response
        for token in self.response(session_id, dialogue):
            yield token, None
