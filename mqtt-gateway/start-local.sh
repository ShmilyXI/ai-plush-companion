#!/bin/zsh
set -euo pipefail

gateway_root=${0:A:h}
mysql_container=codex-companion-mysql-task12
mysql_password=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$mysql_container" | awk -F= '$1=="MYSQL_ROOT_PASSWORD" {sub(/^[^=]*=/, ""); print; exit}')

read_parameter() {
  docker exec -e MYSQL_PWD="$mysql_password" "$mysql_container" mysql -uroot -N -B \
    -e "SELECT param_value FROM xiaozhi_esp32_server.sys_params WHERE param_code='$1' LIMIT 1;"
}

mqtt_signature_key=$(read_parameter server.mqtt_signature_key)
server_secret=$(read_parameter server.secret)
gateway_public_ip=${PUBLIC_IP:-$(ipconfig getifaddr en0)}

if [[ -z "$gateway_public_ip" || -z "$mqtt_signature_key" || "$mqtt_signature_key" == "null" || -z "$server_secret" || "$server_secret" == "null" ]]; then
  print -u2 "MQTT gateway configuration is incomplete"
  exit 1
fi

export PUBLIC_IP="$gateway_public_ip"
export MQTT_PORT=${MQTT_PORT:-1883}
export UDP_PORT=${UDP_PORT:-8884}
export API_PORT=${API_PORT:-8007}
export MQTT_SIGNATURE_KEY="$mqtt_signature_key"
export SERVER_SECRET="$server_secret"

cd "$gateway_root"
exec npm start
