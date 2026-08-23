import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('console global palette', () => {
  it('uses enterprise blue and does not retain the retired gray-green palette', () => {
    const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

    expect(styles).toContain('#1677ff')
    expect(styles).toContain('.ant-pro-sider-menu .ant-pro-base-menu-inline-group .ant-menu-item-group-title')
    expect(styles).not.toMatch(/#(?:075a52|167c72|176c64|183f3a|20302e|5d706d|f2f6f4)/i)
  })

  it('uses tokenized surfaces and preserves narrow-layout interaction rules', () => {
    const styles = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8')

    expect(styles).toContain('overflow-x: clip')
    expect(styles).toContain('var(--console-color-page)')
    expect(styles).toContain('var(--console-color-focus)')
    expect(styles).toContain('font-variant-numeric: tabular-nums')
    expect(styles).not.toContain('transition: all')
  })
})
