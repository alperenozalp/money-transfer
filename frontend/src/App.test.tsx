import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('application authentication', () => {
  it('explains that OIDC setup is required and does not offer manual token entry', async () => {
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Money Transfer' })).toBeInTheDocument()
    expect(screen.getByText(/OIDC_ISSUER_URI/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /OIDC ile giriş yap/ })).toBeDisabled()
    await waitFor(() => expect(screen.queryByText(/Güvenli oturum kontrol ediliyor/)).not.toBeInTheDocument())
  })

  it('submits the demo customer ID and password to create a session', async () => {
    vi.stubEnv('VITE_DEMO_AUTH_ENABLED', 'true')
    const fetchMock = vi.fn().mockResolvedValue({ ok: false, status: 401 })
    vi.stubGlobal('fetch', fetchMock)
    const { App: DemoApp } = await import('./App')
    render(<DemoApp />)

    fireEvent.change(await screen.findByLabelText('Müşteri ID'), {
      target: { value: 'demo-customer' },
    })
    fireEvent.change(screen.getByLabelText('Şifre'), { target: { value: 'wrong-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Giriş yap' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Müşteri ID veya şifre hatalı.')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/demo/session',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ customerId: 'demo-customer', password: 'wrong-password' }),
      }),
    )
  })
})
