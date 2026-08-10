import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import http from '../api/http'
import * as subscriptionApi from '../api/subscription'
import { SubscriptionPage } from './SubscriptionPage'

const subscription: subscriptionApi.CompanionSubscription = {
  planId: 'plan-a', planCode: 'pro', planName: '陪伴版', maxDevices: 3, maxProfiles: 5,
  longTermMemory: true, advancedVoice: true, expiresAt: '2026-08-01T12:00:00',
}

describe('subscription API adapter', () => {
  it('rejects an invalid expiry timestamp', async () => {
    vi.spyOn(http, 'get').mockResolvedValue({ data: { code: 0, msg: 'success', data: { ...subscription, expiresAt: 'not-a-date' } } })

    await expect(subscriptionApi.getSubscription()).rejects.toMatchObject({ name: 'ApiProtocolError' })
  })
})

describe('SubscriptionPage', () => {
  beforeEach(() => {
    vi.spyOn(subscriptionApi, 'getSubscription').mockResolvedValue(subscription)
  })

  it('shows a noon expiry with local date, time, and timezone meaning', async () => {
    const expected = new Date(subscription.expiresAt!).toLocaleString('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
      timeZoneName: 'short',
    })

    render(<SubscriptionPage />)

    expect(screen.getByRole('heading', { name: '订阅', level: 1 })).toBeVisible()
    expect(await screen.findByText(`有效期至 ${expected}（本地时间）`)).toBeVisible()
    expect(expected).toMatch(/12:00:00/)
  })
})
