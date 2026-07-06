import type {
  ApiErrorBody,
  AuthUser,
  CurationUpdateRequest,
  KnowledgeCurationStatus,
  KnowledgeQaDetail,
  KnowledgeQaSummary,
  KnowledgeTopic,
  LoginRequest,
  LoginResponse,
  PageResponse,
  SourceReference,
  SourceReferenceRequest,
} from "./types";

const BASE_URL: string =
  (import.meta.env.VITE_TAXIA_API_BASE_URL as string | undefined) ??
  "http://localhost:8081";

/** Erro de API com mensagem apresentável (nunca stack traces). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

let authToken: string | null = null;
let onUnauthorized: (() => void) | null = null;

/** Token mantido em memória no cliente (nunca em logs). */
export function setAuthToken(token: string | null): void {
  authToken = token;
}

export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler;
}

function friendlyMessage(status: number, body: ApiErrorBody | null): string {
  if (body?.message && status !== 500) return body.message;
  switch (status) {
    case 401:
      return "Sessão expirada ou credenciais inválidas. Inicie sessão novamente.";
    case 403:
      return "Sem permissões para esta operação.";
    case 404:
      return "Registo não encontrado.";
    case 400:
      return body?.message ?? "Pedido inválido — verifique os campos.";
    case 409:
      return body?.message ?? "Conflito: a operação não é permitida no estado actual.";
    default:
      return "Erro inesperado do servidor. Tente novamente.";
  }
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: {
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, "NETWORK", "Não foi possível contactar o servidor TaxIA.");
  }

  if (response.status === 401) {
    onUnauthorized?.();
    throw new ApiError(401, "UNAUTHORIZED", friendlyMessage(401, null));
  }

  if (!response.ok) {
    let errorBody: ApiErrorBody | null = null;
    try {
      errorBody = (await response.json()) as ApiErrorBody;
    } catch {
      /* corpo não-JSON — usa mensagem genérica */
    }
    throw new ApiError(
      response.status,
      errorBody?.code ?? "ERROR",
      friendlyMessage(response.status, errorBody),
    );
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

// ── Autenticação ────────────────────────────────────────────────────────────

export function login(payload: LoginRequest): Promise<LoginResponse> {
  return request<LoginResponse>("POST", "/api/v1/auth/login", payload);
}

export function fetchMe(): Promise<AuthUser> {
  return request<AuthUser>("GET", "/api/v1/auth/me");
}

// ── Conhecimento Q&A ───────────────────────────────────────────────────────

export function listQa(params: {
  status?: KnowledgeCurationStatus | "";
  topic?: KnowledgeTopic | "";
  page?: number;
  size?: number;
}): Promise<PageResponse<KnowledgeQaSummary>> {
  const query = new URLSearchParams();
  if (params.status) query.set("status", params.status);
  if (params.topic) query.set("topic", params.topic);
  query.set("page", String(params.page ?? 0));
  query.set("size", String(params.size ?? 50));
  return request<PageResponse<KnowledgeQaSummary>>(
    "GET",
    `/api/v1/admin/knowledge/qa?${query.toString()}`,
  );
}

export function getQaDetail(id: string): Promise<KnowledgeQaDetail> {
  return request<KnowledgeQaDetail>("GET", `/api/v1/admin/knowledge/qa/${id}`);
}

export function updateCuration(
  id: string,
  payload: CurationUpdateRequest,
): Promise<KnowledgeQaDetail> {
  return request<KnowledgeQaDetail>(
    "PATCH",
    `/api/v1/admin/knowledge/qa/${id}/curation`,
    payload,
  );
}

export function markPendingReview(id: string): Promise<void> {
  return request<void>("POST", `/api/v1/admin/knowledge/qa/${id}/pending-review`);
}

export function validateQa(id: string, reviewerName: string): Promise<void> {
  const query = new URLSearchParams({ reviewerName });
  return request<void>(
    "POST",
    `/api/v1/admin/knowledge/qa/${id}/validate?${query.toString()}`,
  );
}

export function rejectQa(
  id: string,
  reviewerName: string,
  reason: string,
): Promise<void> {
  const query = new URLSearchParams({ reviewerName, reason });
  return request<void>(
    "POST",
    `/api/v1/admin/knowledge/qa/${id}/reject?${query.toString()}`,
  );
}

export function markOutdated(id: string): Promise<void> {
  return request<void>("POST", `/api/v1/admin/knowledge/qa/${id}/outdated`);
}

export function archiveQa(id: string): Promise<void> {
  return request<void>("POST", `/api/v1/admin/knowledge/qa/${id}/archive`);
}

export function listSources(id: string): Promise<SourceReference[]> {
  return request<SourceReference[]>("GET", `/api/v1/admin/knowledge/qa/${id}/sources`);
}

export function addSource(
  id: string,
  payload: SourceReferenceRequest,
): Promise<SourceReference> {
  return request<SourceReference>(
    "POST",
    `/api/v1/admin/knowledge/qa/${id}/sources`,
    payload,
  );
}

// NOTA DELIBERADA: os endpoints de publicação (/publish, /unpublish, /reindex)
// e de IA (/admin/ai/ask) EXISTEM no backend mas NÃO têm função neste cliente.
// Nesta etapa o backoffice não publica, não cria embeddings e não chama
// providers — a omissão é um guard rail, não um esquecimento.

// ── Saúde do sistema ────────────────────────────────────────────────────────

export function fetchHealth(): Promise<{ status: string }> {
  // /api/v1/health (permitAll) — o /actuator está fora da configuração CORS.
  return request<{ status: string }>("GET", "/api/v1/health");
}
