#!/usr/bin/env node

import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const retiredPattern = /xiaozhi|小智/giu
const allowedCategories = new Set([
  'upstream',
  'vendor',
  'migration-history',
  'historical-evidence',
])

export function compileAllowlist(entries) {
  if (!Array.isArray(entries)) throw new TypeError('allowlist must be an array')
  return entries.map((entry, index) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) {
      throw new TypeError(`allowlist entry ${index} must be an object`)
    }
    if (!allowedCategories.has(entry.category)) {
      throw new TypeError(`allowlist entry ${index} has unsupported category`)
    }
    if (typeof entry.pathPattern !== 'string'
      || !entry.pathPattern.startsWith('^')
      || entry.pathPattern === '^.*$'
      || entry.pathPattern === '.*') {
      throw new TypeError(`allowlist entry ${index} requires a narrow anchored pathPattern`)
    }
    if (typeof entry.contentPattern !== 'string' || !entry.contentPattern.trim()) {
      throw new TypeError(`allowlist entry ${index} requires contentPattern`)
    }
    return {
      category: entry.category,
      note: typeof entry.note === 'string' ? entry.note : '',
      pathRegex: new RegExp(entry.pathPattern, 'u'),
      contentRegex: new RegExp(entry.contentPattern, 'iu'),
    }
  })
}

function classify(candidate, allowlist) {
  return allowlist.find((entry) => entry.pathRegex.test(candidate.path)
    && entry.contentRegex.test(candidate.text))
}

function candidatesFor(entry) {
  const candidates = []
  for (const match of entry.path.matchAll(retiredPattern)) {
    candidates.push({
      path: entry.path,
      line: 0,
      column: match.index + 1,
      kind: 'path',
      match: match[0],
      text: entry.path,
    })
  }
  entry.text.split(/\r?\n/u).forEach((line, lineIndex) => {
    for (const match of line.matchAll(retiredPattern)) {
      candidates.push({
        path: entry.path,
        line: lineIndex + 1,
        column: match.index + 1,
        kind: 'content',
        match: match[0],
        text: line,
      })
    }
  })
  return candidates
}

export function scanEntries(entries, allowlist) {
  const violations = []
  const allowed = []
  const allowedByCategory = {}
  for (const entry of entries) {
    for (const candidate of candidatesFor(entry)) {
      const classification = classify(candidate, allowlist)
      if (!classification) {
        violations.push(candidate)
        continue
      }
      allowed.push({
        ...candidate,
        category: classification.category,
        note: classification.note,
      })
      allowedByCategory[classification.category] = (allowedByCategory[classification.category] ?? 0) + 1
    }
  }
  return {
    ok: violations.length === 0,
    violations,
    allowed,
    counts: {
      entries: entries.length,
      violations: violations.length,
      allowed: allowed.length,
      allowedByCategory,
    },
  }
}

function trackedEntries(root) {
  const output = execFileSync('git', ['ls-files', '-z'], { cwd: root })
  const paths = output.toString('utf8').split('\0').filter(Boolean)
  return paths.flatMap((path) => {
    const content = readFileSync(resolve(root, path))
    if (content.includes(0)) return []
    return [{ path, text: content.toString('utf8') }]
  })
}

function argumentValue(name) {
  const index = process.argv.indexOf(name)
  return index === -1 ? null : process.argv[index + 1]
}

function run() {
  const scriptDir = dirname(fileURLToPath(import.meta.url))
  const root = resolve(argumentValue('--root') ?? resolve(scriptDir, '..'))
  const allowlistPath = resolve(argumentValue('--allowlist') ?? resolve(scriptDir, 'zixuan-brand-allowlist.json'))
  const allowlist = compileAllowlist(JSON.parse(readFileSync(allowlistPath, 'utf8')))
  const result = scanEntries(trackedEntries(root), allowlist)
  if (process.argv.includes('--json')) {
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
  } else {
    process.stdout.write(`Zixuan brand scan: ${result.counts.violations} violations, ${result.counts.allowed} allowed matches\n`)
    result.violations.slice(0, 200).forEach((item) => {
      process.stdout.write(`${item.path}:${item.line}:${item.column} ${item.kind} ${item.match}\n`)
    })
    if (result.violations.length > 200) {
      process.stdout.write(`... ${result.violations.length - 200} more violations; rerun with --json for the complete report\n`)
    }
  }
  process.exitCode = result.ok ? 0 : 1
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) run()
