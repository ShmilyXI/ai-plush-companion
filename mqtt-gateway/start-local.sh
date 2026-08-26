#!/bin/zsh
set -euo pipefail

gateway_root=${0:A:h}
local_config_file="$gateway_root/../server/main/xiaozhi-server/data/.config.yaml"
mysql_container=${MYSQL_CONTAINER:-ai-plush-companion-mysql}
redis_container=${REDIS_CONTAINER:-ai-plush-companion-redis}
mysql_password=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$mysql_container" | awk -F= '$1=="MYSQL_ROOT_PASSWORD" {sub(/^[^=]*=/, ""); print; exit}')

read_parameter() {
  docker exec -e MYSQL_PWD="$mysql_password" "$mysql_container" mysql -uroot -N -B \
    -e "SELECT param_value FROM xiaozhi_esp32_server.sys_params WHERE param_code='$1' LIMIT 1;"
}

write_parameter() {
  local param_code=$1
  local param_value=$2
  local escaped_value=${param_value//\'/\'\'}
  docker exec -e MYSQL_PWD="$mysql_password" "$mysql_container" mysql -uroot -N -B \
    -e "UPDATE xiaozhi_esp32_server.sys_params SET param_value='$escaped_value' WHERE param_code='$param_code';"
}

mqtt_signature_key=$(read_parameter server.mqtt_signature_key)
server_secret=$(read_parameter server.secret)
gateway_public_ip=${PUBLIC_IP:-$(ipconfig getifaddr en0)}

if [[ -z "$gateway_public_ip" || -z "$mqtt_signature_key" || "$mqtt_signature_key" == "null" || -z "$server_secret" || "$server_secret" == "null" ]]; then
  print -u2 "MQTT gateway configuration is incomplete"
  exit 1
fi

write_parameter server.ota "http://$gateway_public_ip:8002/xiaozhi/ota/"
write_parameter server.http "http://$gateway_public_ip:8003"
write_parameter server.websocket "ws://$gateway_public_ip:8000/xiaozhi/v1/"
write_parameter server.vision_explain "http://$gateway_public_ip:8003/mcp/vision/explain"
write_parameter server.mqtt_gateway "$gateway_public_ip:${MQTT_PORT:-1883}"
write_parameter server.udp_gateway "$gateway_public_ip:${UDP_PORT:-8884}"

# The xiaozhi server keeps public URLs in the local config when it loads
# manager-api settings. Keep that file aligned with the gateway address.
if [[ -f "$local_config_file" ]]; then
  GATEWAY_PUBLIC_IP="$gateway_public_ip" perl -0pi -e 's/^  websocket: .*$/  websocket: ws:\/\/$ENV{GATEWAY_PUBLIC_IP}:8000\/xiaozhi\/v1\//m; s/^  vision_explain: .*$/  vision_explain: http:\/\/$ENV{GATEWAY_PUBLIC_IP}:8003\/mcp\/vision\/explain/m' \
    "$local_config_file"
else
  print -u2 "本地 xiaozhi 配置不存在，跳过视觉地址同步: $local_config_file"
fi

docker exec "$redis_container" redis-cli HDEL sys:params \
  server.http server.ota server.websocket server.vision_explain server.mqtt_gateway server.udp_gateway >/dev/null

export PUBLIC_IP="$gateway_public_ip"
export MQTT_PORT=${MQTT_PORT:-1883}
export UDP_PORT=${UDP_PORT:-8884}
export API_PORT=${API_PORT:-8007}
export MQTT_SIGNATURE_KEY="$mqtt_signature_key"
export SERVER_SECRET="$server_secret"

cd "$gateway_root"
exec npm start
