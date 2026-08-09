# AI Plush Companion

AI Plush Companion is an open-source emotional companion project built around ESP32 hardware and the XiaoZhi ecosystem. This monorepo contains the management console, backend services, voice service, device firmware, and MQTT gateway used by the project.

## Components

- `server` contains the React management console, Java manager API, and Python XiaoZhi voice service. It is based on [xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server).
- `firmware` contains the ESP32 firmware and the project-specific board adaptations. It is based on [78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32).
- `mqtt-gateway` contains the MQTT and UDP gateway. It is based on [xinnan-tech/xiaozhi-mqtt-gateway](https://github.com/xinnan-tech/xiaozhi-mqtt-gateway).

Each component keeps its original license and attribution files. Review the license inside that component before redistributing a modified build.

## Development

Start with the component README files for environment setup and build commands. Local credentials, generated firmware, device backups, dependency directories, virtual environments, downloaded models, and build outputs are intentionally excluded from this repository.
