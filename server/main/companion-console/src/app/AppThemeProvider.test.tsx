import { describe, expect, it } from 'vitest'
import { consoleLayoutToken, consoleTheme } from './AppThemeProvider'

describe('consoleTheme', () => {
  it('uses the standard Ant Design enterprise palette and density', () => {
    expect(consoleTheme.token?.colorPrimary).toBe('#1677ff')
    expect(consoleTheme.token?.colorBgLayout).toBe('#f5f7fa')
    expect(consoleTheme.token?.borderRadius).toBe(6)
    expect(consoleTheme.token?.controlHeight).toBe(32)
  })

  it('uses the light sidebar workspace contract', () => {
    expect(consoleLayoutToken.sider.colorMenuBackground).toBe('#f7f9fc')
    expect(consoleLayoutToken.sider.colorTextMenuTitle).toBe('#12263a')
    expect(consoleLayoutToken.sider.colorTextMenu).toBe('#5d7185')
    expect(consoleLayoutToken.sider.colorTextMenuSelected).toBe('#12263a')
    expect(consoleLayoutToken.sider.colorBgMenuItemSelected).toBe('#e7edf5')
  })
})
