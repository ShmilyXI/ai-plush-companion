import { describe, expect, it } from 'vitest'
import { consoleLayoutToken, consoleTheme } from './AppThemeProvider'

describe('consoleTheme', () => {
  it('uses the standard Ant Design enterprise palette and density', () => {
    expect(consoleTheme.token?.colorPrimary).toBe('#1677ff')
    expect(consoleTheme.token?.colorBgLayout).toBe('#f5f5f5')
    expect(consoleTheme.token?.borderRadius).toBe(6)
    expect(consoleTheme.token?.controlHeight).toBe(32)
  })

  it('keeps the dark sider brand and navigation readable', () => {
    expect(consoleLayoutToken.sider.colorMenuBackground).toBe('#001529')
    expect(consoleLayoutToken.sider.colorTextMenuTitle).toBe('#ffffff')
    expect(consoleLayoutToken.sider.colorTextMenu).toBe('#d9e6f2')
    expect(consoleLayoutToken.sider.colorTextMenuSelected).toBe('#ffffff')
  })
})
