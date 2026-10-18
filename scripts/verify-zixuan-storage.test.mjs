import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

test('released service configuration uses the zixuan database', () => {
  const application = readFileSync('server/main/manager-api/src/main/resources/application-dev.yml', 'utf8')
  const compose = readFileSync('server/main/zixuan-server/docker-compose_all.yml', 'utf8')
  const gateway = readFileSync('mqtt-gateway/start-local.sh', 'utf8')

  assert.match(application, /jdbc:mysql:[^\n]*\/zixuan_esp32_server/)
  assert.match(compose, /MYSQL_DATABASE=zixuan_esp32_server/)
  assert.match(compose, /jdbc:mysql:[^\n]*\/zixuan_esp32_server/)
  assert.match(gateway, /FROM zixuan_esp32_server\.sys_params/)
  assert.match(gateway, /UPDATE zixuan_esp32_server\.sys_params/)
})

test('manager redis keys use the zixuan namespace', () => {
  const redisKeys = readFileSync('server/main/manager-api/src/main/java/zixuan/common/redis/RedisKeys.java', 'utf8')
  const publicBundles = readFileSync('server/main/manager-api/src/main/java/zixuan/modules/conversation/service/impl/RedisPublicConversationRuntimeBundleStore.java', 'utf8')

  assert.match(redisKeys, /private static final String PREFIX = "zixuan:";/)
  assert.match(redisKeys, /return PREFIX \+ "sys:params";/)
  assert.match(redisKeys, /return PREFIX \+ "device:debug:logs:" \+ deviceId;/)
  assert.match(publicBundles, /KEY_PREFIX = "zixuan:public-conversation:runtime-bundle:"/)
})
