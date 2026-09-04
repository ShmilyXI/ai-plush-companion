__all__ = ["MemoryProvider"]


def __getattr__(name):
    if name != "MemoryProvider":
        raise AttributeError(name)
    from .tencentdb import MemoryProvider

    return MemoryProvider

__all__ = ["MemoryProvider"]
