import { readFile, writeFile } from 'node:fs/promises'
import process from 'node:process'

const [templatePath = '/data/config/tdai-gateway.template.yaml', outputPath = '/tmp/tdai-gateway.yaml'] = process.argv.slice(2)
const dimensionsText = process.env.TENCENTDB_MEMORY_EMBEDDING_DIMENSIONS ?? ''
const proxyText = process.env.TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL ?? ''

if (!/^[1-9]\d*$/.test(dimensionsText)) {
  throw new Error('TENCENTDB_MEMORY_EMBEDDING_DIMENSIONS must be a positive integer')
}

let proxyUrl
try {
  proxyUrl = new URL(proxyText)
} catch {
  throw new Error('TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL must be an absolute HTTP(S) URL')
}
if (!['http:', 'https:'].includes(proxyUrl.protocol) || proxyUrl.username || proxyUrl.password) {
  throw new Error('TENCENTDB_MEMORY_MODEL_PROXY_BASE_URL must be an absolute HTTP(S) URL')
}

let template = await readFile(templatePath, 'utf8')
if ((template.match(/__EMBEDDING_DIMENSIONS__/g) ?? []).length !== 1) {
  throw new Error('gateway template must contain exactly one dimension token')
}
if ((template.match(/__MODEL_PROXY_BASE_URL__/g) ?? []).length !== 2) {
  throw new Error('gateway template must contain exactly two proxy URL tokens')
}

template = template
  .replace('__EMBEDDING_DIMENSIONS__', dimensionsText)
  .replaceAll('__MODEL_PROXY_BASE_URL__', proxyUrl.toString().replace(/\/$/, ''))

await writeFile(outputPath, template, { encoding: 'utf8', mode: 0o600 })
