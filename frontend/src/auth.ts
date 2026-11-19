import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'

export interface AuthSession {
  access_token: string
  profile: {
    name?: string
    email?: string
    customerId?: string
  }
}

export interface DemoCredentials {
  customerId: string
  password: string
}

interface DemoTokenResponse {
  data: {
    accessToken: string
    tokenType: string
    expiresIn: number
    customerId: string
    customerName: string
  }
}

const authority = import.meta.env.VITE_OIDC_AUTHORITY?.trim()
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID?.trim()
const redirectUri =
  import.meta.env.VITE_OIDC_REDIRECT_URI?.trim() || `${window.location.origin}/auth/callback`

export const isDemoAuthEnabled = import.meta.env.VITE_DEMO_AUTH_ENABLED === 'true'
export const isOidcConfigured = Boolean(authority && clientId)
export const isAuthenticationEnabled = isDemoAuthEnabled || isOidcConfigured
export const isAuthCallback = !isDemoAuthEnabled && window.location.pathname === '/auth/callback'

let manager: UserManager | undefined
let demoSession: (AuthSession & { expiresAt: number }) | undefined

function getManager(): UserManager {
  if (!isOidcConfigured || !authority || !clientId) {
    throw new Error('OIDC authority and client ID must be configured before authentication.')
  }

  if (!manager) {
    const additionalScopes = import.meta.env.VITE_OIDC_SCOPE?.trim()
    manager = new UserManager({
      authority,
      client_id: clientId,
      redirect_uri: redirectUri,
      post_logout_redirect_uri: window.location.origin,
      response_type: 'code',
      scope: ['openid', 'profile', additionalScopes].filter(Boolean).join(' '),
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: false,
    })
  }

  return manager
}

export async function getCurrentUser(): Promise<AuthSession | null> {
  if (isDemoAuthEnabled) {
    if (demoSession && demoSession.expiresAt > Date.now()) return demoSession
    demoSession = undefined
    return null
  }

  if (!isOidcConfigured) return null
  return getManager().getUser()
}

export async function signIn(credentials?: DemoCredentials): Promise<AuthSession | void> {
  if (isDemoAuthEnabled) {
    if (!credentials) {
      throw new Error('Müşteri ID ve şifre gereklidir.')
    }
    const response = await fetch('/api/v1/demo/session', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Trace-Id': crypto.randomUUID(),
      },
      body: JSON.stringify(credentials),
    })
    if (response.status === 401) {
      throw new Error('Müşteri ID veya şifre hatalı.')
    }
    if (!response.ok) {
      throw new Error('Demo oturumu oluşturulamadı. Backend’in çalıştığını kontrol edin.')
    }

    const payload = (await response.json()) as DemoTokenResponse
    demoSession = {
      access_token: payload.data.accessToken,
      profile: { name: payload.data.customerName, customerId: payload.data.customerId },
      expiresAt: Date.now() + payload.data.expiresIn * 1000,
    }
    return demoSession
  }

  return getManager().signinRedirect()
}

export function finishSignIn(): Promise<User> {
  return getManager().signinRedirectCallback()
}

export async function signOut(): Promise<void> {
  if (isDemoAuthEnabled) {
    demoSession = undefined
    return
  }

  return getManager().signoutRedirect()
}

export async function clearSessionUser(): Promise<void> {
  demoSession = undefined
  if (isOidcConfigured) await getManager().removeUser()
}
