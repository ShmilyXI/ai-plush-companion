import { gzipSync } from 'node:zlib'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const dist = new URL('../dist/', import.meta.url)
const html = readFileSync(new URL('index.html', dist), 'utf8')
const manifest = JSON.parse(readFileSync(new URL('.vite/manifest.json', dist), 'utf8'))
const initialAssets = [...html.matchAll(/(?:src|href)="\/([^"?#]+)"/g)].map((match) => match[1])
const initialJavascript = initialAssets.filter((asset) => asset.endsWith('.js'))
const initialGzipBytes = initialAssets.reduce((total, asset) => {
  const contents = readFileSync(join(dist.pathname, asset))
  return total + gzipSync(contents).byteLength
}, 0)

const loginKey = 'src/pages/LoginPage.tsx'
const loginAssets = new Set()
function collectManifestAssets(key) {
  const item = manifest[key]
  if (!item || loginAssets.has(item.file)) return
  loginAssets.add(item.file)
  for (const css of item.css ?? []) loginAssets.add(css)
  for (const dependency of item.imports ?? []) collectManifestAssets(dependency)
}
collectManifestAssets(loginKey)
for (const asset of initialAssets) loginAssets.add(asset)
const loginGzipBytes = [...loginAssets].reduce((total, asset) => {
  const contents = readFileSync(join(dist.pathname, asset))
  return total + gzipSync(contents).byteLength
}, 0)

if (initialJavascript.some((asset) => /AppShell|AdminPlaceholder|sm2/i.test(asset))) {
  throw new Error(`登录首屏预加载了延迟模块: ${initialJavascript.join(', ')}`)
}
if (loginGzipBytes >= 320_800) {
  throw new Error(`登录首屏 gzip ${loginGzipBytes} bytes，未低于原始 320800 bytes`)
}
if (!Object.keys(manifest).some((key) => key.endsWith('AppShell.tsx'))) {
  throw new Error('构建清单缺少 AppShell 延迟 chunk')
}

if ([...loginAssets].some((asset) => /AppShell|AdminPlaceholder|crypto-/i.test(asset))) {
  throw new Error(`登录首屏加载了延迟模块: ${[...loginAssets].join(', ')}`)
}

console.log(`entry gzip: ${initialGzipBytes} bytes; login gzip: ${loginGzipBytes} bytes`)
