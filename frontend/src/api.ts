export interface ApiEnvelope<T> {
  data: T
  meta: Record<string, unknown>
}

export interface Account {
  id: string
  iban: string
  currency: string
  status: 'ACTIVE' | 'INACTIVE' | 'CLOSED'
  balance: string
}

export interface TransferLimits {
  currency: string
  perTransferMaximum: string
  dailyRemaining: string
  monthlyRemaining: string
}

export interface Transfer {
  id: string
  sourceAccountId: string
  sourceIban: string
  receiverIban: string
  direction: 'INCOMING' | 'OUTGOING'
  amount: string
  currency: string
  description?: string
  status: 'COMPLETED'
  createdAt: string
}

export type TransferDirection = 'INCOMING' | 'OUTGOING'

export interface MonthlyTransferStatistic {
  month: string
  completedTransferCount: number
  completedOutgoingAmount: string
}

export interface TransferPageMeta extends Record<string, unknown> {
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId?: string

  constructor(
    message: string,
    status: number,
    code: string,
    traceId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.traceId = traceId
  }
}

async function request<T>(
  path: string,
  accessToken: string,
  options: {
    method?: 'GET' | 'POST'
    body?: unknown
    idempotencyKey?: string
    signal?: AbortSignal
  } = {},
): Promise<ApiEnvelope<T>> {
  const headers = new Headers({
    Authorization: `Bearer ${accessToken}`,
    'X-Trace-Id': crypto.randomUUID(),
  })

  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }
  if (options.idempotencyKey) {
    headers.set('Idempotency-Key', options.idempotencyKey)
  }

  const response = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  })

  if (!response.ok) {
    let payload: { error?: { code?: string; message?: string; traceId?: string } }
    try {
      payload = await response.json()
    } catch (error) {
      if (!(error instanceof SyntaxError)) {
        throw error
      }
      throw new ApiError('The server returned an unreadable error response.', response.status, 'INVALID_RESPONSE')
    }

    throw new ApiError(
      payload.error?.message ?? 'The request could not be completed.',
      response.status,
      payload.error?.code ?? 'REQUEST_FAILED',
      payload.error?.traceId,
    )
  }

  const payload: unknown = await response.json()
  if (!isApiEnvelope<T>(payload)) {
    throw new Error('The server returned an invalid success response.')
  }
  return payload
}

export async function getAccounts(accessToken: string): Promise<Account[]> {
  return (await request<Account[]>('/api/v1/accounts', accessToken)).data
}

export async function getTransferLimits(accessToken: string): Promise<TransferLimits> {
  return (await request<TransferLimits>('/api/v1/accounts/transfer-limits', accessToken)).data
}

export async function getTransfers(
  accessToken: string,
  filters: {
    page: number
    size: number
    startDate?: string
    endDate?: string
    direction?: TransferDirection
  },
): Promise<{ transfers: Transfer[]; meta: TransferPageMeta }> {
  const query = new URLSearchParams({ page: String(filters.page), size: String(filters.size) })
  if (filters.startDate) query.set('startDate', filters.startDate)
  if (filters.endDate) query.set('endDate', filters.endDate)
  if (filters.direction) query.set('direction', filters.direction)
  const response = await request<Transfer[]>(`/api/v1/transfers?${query}`, accessToken)
  if (!isTransferPageMeta(response.meta)) {
    throw new Error('The transfer list response did not contain valid pagination metadata.')
  }
  return { transfers: response.data, meta: response.meta }
}

export async function getTransfer(accessToken: string, transferId: string): Promise<Transfer> {
  return (await request<Transfer>(`/api/v1/transfers/${encodeURIComponent(transferId)}`, accessToken)).data
}

export async function getMonthlyStatistics(accessToken: string): Promise<MonthlyTransferStatistic[]> {
  return (await request<MonthlyTransferStatistic[]>('/api/v1/statistics/monthly-transfers', accessToken)).data
}

export async function createTransfer(
  accessToken: string,
  idempotencyKey: string,
  input: { sourceAccountId: string; receiverIban: string; amount: string; description?: string },
): Promise<Transfer> {
  return (
    await request<Transfer>('/api/v1/transfers', accessToken, {
      method: 'POST',
      body: input,
      idempotencyKey,
    })
  ).data
}

function isTransferPageMeta(value: Record<string, unknown>): value is TransferPageMeta {
  return (
    Number.isInteger(value.page) &&
    Number.isInteger(value.size) &&
    Number.isInteger(value.totalElements) &&
    Number.isInteger(value.totalPages)
  )
}

function isApiEnvelope<T>(value: unknown): value is ApiEnvelope<T> {
  if (typeof value !== 'object' || value === null || !('data' in value) || !('meta' in value)) return false
  const meta = value.meta
  return typeof meta === 'object' && meta !== null && !Array.isArray(meta)
}
