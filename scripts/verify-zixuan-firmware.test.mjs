import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import test from 'node:test'

const read = (path) => existsSync(path) ? readFileSync(path, 'utf8') : ''
const trackedMatches = (pattern) => {
  const result = spawnSync('git', ['grep', '-n', '-I', '-E', pattern, '--', 'firmware'], { encoding: 'utf8' })
  assert.ok(result.status === 0 || result.status === 1, result.stderr)
  return result.stdout
}
const sha256 = (path) => createHash('sha256').update(readFileSync(path)).digest('hex')

test('maintained boards define the complete zixuan release identity', () => {
  for (const board of ['zhengchen-cam', 'bread-compact-wifi-s3cam']) {
    const config = JSON.parse(read(`firmware/main/boards/${board}/config.json`))
    const values = config.builds[0].sdkconfig_append
    assert.ok(values.includes('CONFIG_OTA_URL="http://134.175.173.247/zixuan/ota/"'), board)
    assert.ok(values.includes('CONFIG_CUSTOM_WAKE_WORD="ni hao zi xuan"'), board)
    assert.ok(values.includes('CONFIG_CUSTOM_WAKE_WORD_DISPLAY="你好紫萱"'), board)
    assert.ok(values.includes('CONFIG_USE_CUSTOM_WAKE_WORD=y'), board)
    assert.ok(values.includes('CONFIG_SR_MN_CN_MULTINET7_QUANT=y'), board)
    assert.ok(values.includes('CONFIG_ESPTOOLPY_FLASHSIZE_16MB=y'), board)
    assert.ok(values.includes('CONFIG_PARTITION_TABLE_CUSTOM_FILENAME="partitions/v2/16m.csv"'), board)
  }
})

test('firmware source and build metadata use zixuan identity', () => {
  assert.match(read('firmware/CMakeLists.txt'), /project\(zixuan\)/)
  const kconfig = read('firmware/main/Kconfig.projbuild')
  assert.match(kconfig, /menu "Zixuan Assistant"/)
  assert.match(kconfig, /default "http:\/\/134\.175\.173\.247\/zixuan\/ota\/"/)
  assert.doesNotMatch(kconfig, /\bXIAOZHI_/)
  assert.match(read('firmware/main/boards/common/wifi_board.cc'), /ssid_prefix = "Zixuan"/)
  assert.match(read('firmware/main/boards/common/blufi.cpp'), /BLUFI_DEVICE_NAME "Zixuan-Blufi"/)
  assert.match(read('firmware/.github/workflows/build.yml'), /name: zixuan_\$\{\{ matrix\.full_name \}\}_\$\{\{ github\.sha \}\}/)
  const versions = read('firmware/scripts/versions.py')
  assert.match(versions, /"zixuan\.bin"/)
  assert.doesNotMatch(versions, /"xiaozhi\.bin"/i)
  assert.equal(trackedMatches('CONFIG_XIAOZHI_'), '')
})

test('firmware release manifest binds both board artifact pairs', () => {
  const path = 'firmware/releases/zixuan-release-manifest.json'
  assert.ok(existsSync(path), `${path} must exist`)
  const manifest = JSON.parse(read(path))
  assert.equal(manifest.contractVersion, 'zixuan-cutover-v1')
  assert.equal(manifest.sourceRevision, '6c8e2e0')
  assert.equal(manifest.firmwareVersion, '2.2.7')
  assert.equal(manifest.toolchain.espIdf, '5.5.2')
  assert.deepEqual(manifest.boards.map((board) => board.board).sort(), [
    'bread-compact-wifi-s3cam',
    'zhengchen-cam',
  ])
  for (const board of manifest.boards) {
    assert.equal(board.assetsPartitionSize, 8 * 1024 * 1024)
    assert.equal(board.wakeWord.layoutVersion, 2)
    assert.equal(board.wakeWord.slotSize, 3 * 1024 * 1024)
    assert.equal(board.wakeWord.factoryWord, '你好紫萱')
    assert.equal(board.wakeWord.command, 'ni hao zi xuan')
    assert.match(board.application.sha256, /^[a-f0-9]{64}$/)
    assert.match(board.assets.sha256, /^[a-f0-9]{64}$/)
    assert.equal(sha256(board.application.path), board.application.sha256)
    assert.equal(sha256(board.assets.path), board.assets.sha256)

    const assets = readFileSync(board.assets.path)
    const slotA = assets.length - 2 * board.wakeWord.slotSize
    const slotB = assets.length - board.wakeWord.slotSize
    assert.equal(assets.subarray(slotA, slotA + 4).toString(), 'XZWK')
    assert.equal(assets.readUInt32LE(slotA + 4), 2)
    assert.ok(assets.includes(Buffer.from('ni hao zi xuan')))
    assert.ok(assets.includes(Buffer.from('你好紫萱')))
    assert.ok(assets.subarray(slotB).every((byte) => byte === 0xff))

    assert.deepEqual(board.flash.map((entry) => entry.offset), [0, 0x8000, 0xd000, 0x20000, 0x800000])
    assert.ok(board.flash.every((entry) => !entry.path.includes('merged-binary')))
    assert.ok(board.flash.every((entry) => entry.offset + entry.size <= 0x9000 || entry.offset >= 0xd000))
  }
})
