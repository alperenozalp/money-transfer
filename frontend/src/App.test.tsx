import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

function apiResponse(data: unknown, meta: Record<string, unknown> = {}) {
  return new Response(JSON.stringify({ data, meta }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  cleanup()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('application authentication', () => {
  it('explains that OIDC setup is required and does not offer manual token entry', async () => {
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Money Transfer' })).toBeInTheDocument()
    expect(screen.getByText(/OIDC_ISSUER_URI/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Sign in with OIDC/ })).toBeDisabled()
    await waitFor(() => expect(screen.queryByText(/Checking secure session/)).not.toBeInTheDocument())
  })

  it('submits the demo customer ID and password to create a session', async () => {
    vi.stubEnv('VITE_DEMO_AUTH_ENABLED', 'true')
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401 })
    vi.stubGlobal('fetch', fetchMock)
    const { App: DemoApp } = await import('./App')
    render(<DemoApp />)

    fireEvent.change(await screen.findByLabelText('Customer ID'), {
      target: { value: 'demo-customer' },
    })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid customer ID or password.')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/demo/session',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ customerId: 'demo-customer', password: 'wrong-password' }),
      }),
    )
  })

  it('shows incoming transfers and identifies the sender in history and details', async () => {
    vi.stubEnv('VITE_DEMO_AUTH_ENABLED', 'true')
    const transfer = {
      id: 'incoming-transfer',
      sourceAccountId: 'source-account',
      sourceIban: 'TR0000000000000000000000001',
      receiverIban: 'TR0000000000000000000000003',
      direction: 'INCOMING',
      amount: '2500.00',
      currency: 'TRY',
      description: 'Test transfer',
      status: 'COMPLETED',
      createdAt: '2026-11-19T10:00:00Z',
    }
    const fetchMock = vi.fn<typeof fetch>()
    fetchMock.mockImplementation(async (input) => {
      const path = String(input)
      if (path === '/api/v1/demo/session') {
        return apiResponse({
          accessToken: 'demo-token',
          tokenType: 'Bearer',
          expiresIn: 1800,
          customerId: 'demo-recipient',
          customerName: 'Demo User 2',
        })
      }
      if (path === '/api/v1/accounts') {
        return apiResponse([{
          id: 'destination-account',
          iban: transfer.receiverIban,
          currency: 'TRY',
          status: 'ACTIVE',
          balance: '7500.00',
        }])
      }
      if (path === '/api/v1/accounts/transfer-limits') {
        return apiResponse({
          currency: 'TRY',
          perTransferMaximum: '50000.00',
          dailyRemaining: '100000.00',
          monthlyRemaining: '500000.00',
        })
      }
      if (path === '/api/v1/statistics/monthly-transfers') return apiResponse([])
      if (path.startsWith('/api/v1/transfers?')) {
        return apiResponse([transfer], { page: 0, size: 10, totalElements: 1, totalPages: 1 })
      }
      if (path === `/api/v1/transfers/${transfer.id}`) return apiResponse(transfer)
      throw new Error(`Unexpected API request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const { App: DemoApp } = await import('./App')
    render(<DemoApp />)

    fireEvent.change(await screen.findByLabelText('Customer ID'), {
      target: { value: 'demo-recipient' },
    })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'transfer1234' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Incoming')).toBeInTheDocument()
    expect(screen.getByText(transfer.receiverIban)).toBeInTheDocument()
    expect(screen.getByText(/0001$/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Transfers' }))
    fireEvent.click(screen.getByRole('button', { name: 'Incoming' }))
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('direction=INCOMING'))).toBe(true)
    await screen.findByRole('button', { name: 'Details' })
    fireEvent.click(screen.getByRole('button', { name: 'Details' }))
    expect(await screen.findByText('Sender IBAN')).toBeInTheDocument()
    expect(within(screen.getByRole('dialog')).getByText('Recipient IBAN')).toBeInTheDocument()
  })
})
