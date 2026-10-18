import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import test from 'node:test'

const read = (path) => existsSync(path) ? readFileSync(path, 'utf8') : ''

test('shared runtime contract defines zixuan mqtt topics', () => {
  const contract = JSON.parse(read('contracts/zixuan-runtime.json'))

  assert.deepEqual(contract.mqtt, {
    uplink: 'zixuan/device-server',
    downlinkPrefix: 'zixuan/devices/p2p/',
  })
})

test('gateway package and process metadata use zixuan', () => {
  const pkg = JSON.parse(read('mqtt-gateway/package.json'))
  const ecosystem = read('mqtt-gateway/ecosystem.config.js')
  const example = read('mqtt-gateway/config/mqtt.json.example')

  assert.equal(pkg.name, 'zixuan-mqtt-gateway')
  assert.match(ecosystem, /"name": "zixuan-mqtt-gateway"/)
  assert.match(example, /"name": "ZixuanMqttClient"/)
})

test('manager and python runtime issue only zixuan topics', () => {
  const java = read('server/main/manager-api/src/main/java/zixuan/common/constant/ProductIdentity.java')
    + read('server/main/manager-api/src/main/java/zixuan/modules/device/service/impl/DeviceServiceImpl.java')
  const python = read('server/main/zixuan-server/config/product_identity.py')
    + read('server/main/zixuan-server/core/api/ota_handler.py')

  assert.match(java, /MQTT_UPLINK_TOPIC = "zixuan\/device-server"/)
  assert.match(java, /MQTT_DOWNLINK_PREFIX = "zixuan\/devices\/p2p\/"/)
  assert.match(python, /MQTT_UPLINK_TOPIC = "zixuan\/device-server"/)
  assert.match(python, /MQTT_DOWNLINK_PREFIX = "zixuan\/devices\/p2p\/"/)
})

test('generated api documents expose zixuan mqtt topics', () => {
  const api = JSON.parse(read('docs/api/mqtt-gateway-openapi.json'))
  assert.deepEqual(api['x-mqtt-topics'].topics.map((topic) => topic.name), [
    'zixuan/device-server',
    'zixuan/devices/p2p/{mac}',
  ])
})
