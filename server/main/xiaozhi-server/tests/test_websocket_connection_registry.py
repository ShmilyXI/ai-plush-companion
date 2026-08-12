import unittest

from core.websocket_server import WebSocketServer


class WebSocketConnectionRegistryTest(unittest.TestCase):
    def test_tracks_active_connections_by_case_insensitive_device_id(self):
        server = WebSocketServer.__new__(WebSocketServer)
        server._device_connections = {}
        connection = type("Connection", (), {"device_id": "AA:BB:CC:DD:EE:FF"})()

        server.register_connection(connection)

        self.assertIs(connection, server.get_connection("aa:bb:cc:dd:ee:ff"))
        server.unregister_connection(connection)
        self.assertIsNone(server.get_connection("AA:BB:CC:DD:EE:FF"))

    def test_old_disconnect_does_not_remove_a_newer_connection(self):
        server = WebSocketServer.__new__(WebSocketServer)
        server._device_connections = {}
        older = type("Connection", (), {"device_id": "AA:BB"})()
        newer = type("Connection", (), {"device_id": "aa:bb"})()

        server.register_connection(older)
        server.register_connection(newer)
        server.unregister_connection(older)

        self.assertIs(newer, server.get_connection("AA:BB"))


if __name__ == "__main__":
    unittest.main()
