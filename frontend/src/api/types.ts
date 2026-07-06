// Tipos alinhados 1:1 com os DTOs reais do backend (não inventar campos).

export type KnowledgeCurationStatus =
  | "IMPORTED"
  | "PENDING_REVIEW"
  | "VALIDATED"
  | "NEEDS_UPDATE"
  | "OUTDATED"
  | "REJECTED"
  | "ARCHIVED";

export type KnowledgeRiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export type KnowledgeTopic =
  | "IVA"
  | "IRC"
  | "IRS"
  | "SEGURANCA_SOCIAL"
  | "TRABALHO"
  | "CONTABILIDADE"
  | "PROCEDIMENTO_TRIBUTARIO"
  | "FATURACAO"
  | "OUTROS";

export type KnowledgeSourceType =
  | "LEGISLATION"
  | "ADMINISTRATIVE_GUIDANCE"
  | "CASE_LAW"
  | "OFFICIAL_FAQ"
  | "INTERNAL_OPINION"
  | "ACCOUNTING_STANDARD"
  | "OTHER";

export interface LoginRequest {
  email: string;
  password: string;
}

/** AuthResponse do backend. */
export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  userId: string;
  organizationId: string;
  email: string;
  fullName: string;
  roles: string[];
}

/** GET /api/v1/auth/me */
export interface AuthUser {
  userId: string;
  organizationId: string;
  email: string;
  roles: string[];
}

/** KnowledgeQaResponse (lista). */
export interface KnowledgeQaSummary {
  id: string;
  externalKey: string | null;
  sourceSystem: string | null;
  originalQuestion: string;
  topic: KnowledgeTopic | null;
  subtopic: string | null;
  riskLevel: KnowledgeRiskLevel;
  curationStatus: KnowledgeCurationStatus;
  canonical: boolean;
  requiresHumanValidation: boolean;
  validFrom: string | null;
  validTo: string | null;
  published: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SourceReference {
  id: string;
  sourceType: KnowledgeSourceType;
  title: string;
  legalReference: string | null;
  url: string | null;
  documentId: string | null;
  fragmentId: string | null;
  validFrom: string | null;
  validTo: string | null;
  notes: string | null;
  createdAt: string;
}

/** KnowledgeQaDetailResponse. */
export interface KnowledgeQaDetail {
  id: string;
  externalKey: string | null;
  sourceSystem: string | null;
  originalQuestion: string;
  originalAnswer: string;
  normalizedQuestion: string | null;
  shortAnswer: string | null;
  technicalAnswer: string | null;
  topic: KnowledgeTopic | null;
  subtopic: string | null;
  jurisdiction: string | null;
  riskLevel: KnowledgeRiskLevel;
  requiresHumanValidation: boolean;
  curationStatus: KnowledgeCurationStatus;
  canonical: boolean;
  validFrom: string | null;
  validTo: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  notes: string | null;
  published: boolean;
  publishedAt: string | null;
  publishedBy: string | null;
  previousVersionId: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  sources: SourceReference[];
}

/** PATCH /{id}/curation — todos os campos são enviados (o backend substitui). */
export interface CurationUpdateRequest {
  normalizedQuestion: string | null;
  shortAnswer: string | null;
  technicalAnswer: string | null;
  topic: KnowledgeTopic | null;
  subtopic: string | null;
  jurisdiction: string | null;
  riskLevel: KnowledgeRiskLevel;
  requiresHumanValidation: boolean;
  validFrom: string | null;
  validTo: string | null;
  notes: string | null;
}

/** POST /{id}/sources */
export interface SourceReferenceRequest {
  sourceType: KnowledgeSourceType;
  title: string;
  legalReference: string | null;
  url: string | null;
  documentId: string | null;
  fragmentId: string | null;
  validFrom: string | null;
  validTo: string | null;
  notes: string | null;
}

/** Resposta paginada do Spring Data. */
export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/** Corpo de erro do GlobalExceptionHandler. */
export interface ApiErrorBody {
  code: string;
  message: string;
  path?: string;
  timestamp?: string;
  details?: Record<string, unknown>;
}
