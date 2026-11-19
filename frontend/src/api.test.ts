import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createTransfer, getTransfers } from './api'

const fetchMock = vi.fn<typeof fetch>()

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('API client', () => {
  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('sends the bearer token and idempotency key with decimal-safe transfer data', async () => {
    const transfer = {
      id: 'transfer-id',
      sourceAccountId: 'source-id',
      sourceIban: 'TR0000000000000000000000001',
      receiverIban: 'TR0000000000000000000000002',
      direction: 'OUTGOING' as const,
      amount: '125.00',
      currency: 'TRY',
      status: 'COMPLETED' as const,
      createdAt: '2026-11-18T18:11:24Z',
    }
    fetchMock.mockResolvedValue(jsonResponse({ data: transfer, meta: {} }, 201))

    await expect(createTransfer('access-token', 'retry-key', {
      sourceAccountId: 'source-id',
      receiverIban: transfer.receiverIban,
      amount: '125.00',
    })).resolves.toEqual(transfer)

    const [url, options] = fetchMock.mock.calls[0]
    if (!options) throw new Error('Expected request options to be passed to fetch.')
    const headers = new Headers(options.headers)
    expect(url).toBe('/api/v1/transfers')
    expect(headers.get('Authorization')).toBe('Bearer access-token')
    expect(headers.get('Idempotency-Key')).toBe('retry-key')
    expect(JSON.parse(String(options.body))).toEqual({
      sourceAccountId: 'source-id',
      receiverIban: transfer.receiverIban,
      amount: '125.00',
    })
  })

  it('encodes date filters and rejects malformed pagination metadata', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      data: [],
      meta: { page: 0, size: 10, totalElements: 0, totalPages: 0 },
    }))

    await expect(getTransfers('access-token', {
      page: 0,
      size: 10,
      startDate: '2026-11-01',
      endDate: '2026-11-30',
      direction: 'INCOMING',
    })).resolves.toMatchObject({ transfers: [], meta: { totalElements: 0 } })
    expect(String(fetchMock.mock.calls[0][0])).toContain('startDate=2026-11-01')
    expect(String(fetchMock.mock.calls[0][0])).toContain('endDate=2026-11-30')
    expect(String(fetchMock.mock.calls[0][0])).toContain('direction=INCOMING')

    fetchMock.mockResolvedValueOnce(jsonResponse({ data: [], meta: {} }))
    await expect(getTransfers('access-token', { page: 0, size: 10 }))
      .rejects.toThrow('valid pagination metadata')
  })

  it('surfaces stable business error codes from the API', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      error: {
        code: 'INSUFFICIENT_FUNDS',
        message: 'The source account has insufficient funds.',
        traceId: 'trace-123',
      },
    }, 422))

    await expect(createTransfer('access-token', 'retry-key', {
      sourceAccountId: 'source-id',
      receiverIban: 'TR0000000000000000000000002',
      amount: '125.00',
    })).rejects.toMatchObject({
      status: 422,
      code: 'INSUFFICIENT_FUNDS',
      traceId: 'trace-123',
    })
  })

  it('does not convert network failures into successful responses', async () => {
    fetchMock.mockRejectedValue(new TypeError('Network unavailable'))
    await expect(getTransfers('access-token', { page: 0, size: 10 }))
      .rejects.toThrow('Network unavailable')
  })
})
