import json
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
TAG = __name__


async def handleAbortMessage(conn: "ConnectionHandler"):
    conn.logger.bind(tag=TAG).info("Abort message received")
    notify_activity = getattr(conn, "notify_confirmed_user_activity", None)
    if callable(notify_activity):
        notify_activity()
    cancel_proactive = getattr(conn, "cancel_proactive_playback", None)
    if getattr(conn, "proactive_playback_active", False) and callable(cancel_proactive):
        cancel_proactive()
    conn.emit_debug_event(
        "audio",
        "barge_in.triggered",
        "info",
        "用户插话已触发",
        details={
            "clientAec": bool(conn.client_aec),
            "listenMode": conn.client_listen_mode,
        },
        sentence_id=conn.sentence_id,
    )
    # 设置成打断状态，会自动打断llm、tts任务
    conn.close_after_chat = False
    conn.client_abort = True
    if conn.tts:
        conn.tts._cancel_tts_debug(conn.sentence_id, "client_abort")
    conn._fail_llm_debug(conn.sentence_id, RuntimeError("cancelled"), "Cancelled")
    conn.clear_queues()
    # 打断客户端说话状态
    await conn.websocket.send(
        json.dumps({"type": "tts", "state": "stop", "session_id": conn.session_id})
    )
    conn.clearSpeakStatus()
    conn.logger.bind(tag=TAG).info("Abort message received-end")
