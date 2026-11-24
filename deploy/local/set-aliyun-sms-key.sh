#!/bin/bash
# 把剪贴板里的阿里云 AccessKey 存进本机数据库的系统参数。值不会打印出来。
# 用法：先在阿里云 RAM 控制台点“复制”，再运行
#   deploy/local/set-aliyun-sms-key.sh id       # 存 AccessKey ID
#   deploy/local/set-aliyun-sms-key.sh secret   # 存 AccessKey Secret
# 只用于本机开发的 zixuan-local-db；线上环境在管理台“参数管理”里填。
set -euo pipefail

case "${1:-}" in
  id) code="aliyun.sms.access_key_id" ;;
  secret) code="aliyun.sms.access_key_secret" ;;
  *) echo "用法：$0 id|secret" >&2; exit 2 ;;
esac

value="$(pbpaste | tr -d ' \t\r\n')"
if [ -z "$value" ]; then
  echo "剪贴板是空的：先在阿里云控制台点“复制”" >&2
  exit 1
fi
# AccessKey 只有字母和数字。这样也不会有引号混进 SQL。
if ! printf '%s' "$value" | grep -Eq '^[A-Za-z0-9]{12,64}$'; then
  echo "剪贴板里的内容不像 AccessKey（应该是 12 到 64 位字母和数字），没有保存" >&2
  exit 1
fi

docker exec -i zixuan-local-db mysql -uroot -p123456 zixuan_esp32_server 2>/dev/null <<SQL
UPDATE sys_params SET param_value = '$value', update_date = NOW() WHERE param_code = '$code';
SQL
docker exec zixuan-local-redis redis-cli del zixuan:sys:params zixuan:server:config >/dev/null
echo "已保存 $code（${#value} 位）"
