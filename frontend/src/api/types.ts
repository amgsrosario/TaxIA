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
  applicabilityReviewedAt: string | null;
  applicabilityReviewedBy: string | null;
  applicabilityExclusions: ApplicabilityExclusion[];
  /** Versão em preparação (não publicada) derivada desta, se existir (ADR-005). */
  draftVersionId: string | null;
  /** A versão anterior (previousVersionId) está publicada. */
  previousVersionPublished: boolean;
}

// ── Âmbito de aplicação (M4-SCOPE-V2, ADR-004) ─────────────────────────────

/** Exclusão: situação que a Q&A declara não cobrir. removalPending = remoção a aguardar validação. */
export interface ApplicabilityExclusion {
  marker: string;
  label: string | null;
  note: string | null;
  createdAt: string;
  createdBy: string | null;
  removalPending: boolean;
  removalRequestedAt: string | null;
  removalRequestedBy: string | null;
}

export interface ApplicabilityMarkerOption {
  code: string;
  label: string;
}

/** GET /{id}/applicability — só admin. */
export interface KnowledgeQaApplicability {
  questionAnswerId: string;
  published: boolean;
  /** true: retirar uma exclusão fica pendente de validação humana (publicada ou VALIDATED). */
  removalRequiresValidation: boolean;
  applicabilityReviewedAt: string | null;
  applicabilityReviewedBy: string | null;
  derivedScope: { taxDomains: string[]; incomeCategories: string[]; operations: string[] };
  exclusions: ApplicabilityExclusion[];
  vocabulary: ApplicabilityMarkerOption[];
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
  /** Versão do caso que o editor carregou (optimistic lock): diferente da actual → 409. */
  expectedVersion: number;
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

// ── Resposta documentada projectada (contrato D3/D7) ──────────────────────────
// Tipos da VISTA de apresentação de projectedAnswer. Modelam apenas os campos
// seguros que o frontend mostra. O backend decide e projecta; o frontend só
// apresenta e traduz rótulos — nunca recalcula nem infere estes valores.
//
// Os campos internos do backend (sourceId, authorityLevel, sourceCore,
// sourceDiversityGroup, sourceDiversity, flags de suporte, excerpt,
// notesInternal, hiddenDiagnostics, internalDiagnostics) são deliberadamente
// omitidos destes tipos: mesmo que cheguem no JSON, não têm forma de serem
// apresentados a partir daqui.

export type AnswerType =
  | "CONSULTA_DOCUMENTADA"
  | "CONSULTA_DOCUMENTADA_COM_LIMITACOES"
  | "RESPOSTA_LIMITE"
  | "PEDIDO_DE_PARECER";

export type ParecerRequirement = "NONE" | "SUGGESTED" | "REQUIRED";

export type VisibilityLevel = "EXTERNAL" | "DEMO" | "INTERNAL" | "CURATION_ONLY";

export type SourceRole = "PRIMARY" | "COMPLEMENTARY" | "DERIVATIVE_REPLICATED";

export type SourceQuality = "STRONG" | "ADEQUATE" | "LIMITED" | "WEAK";

export type FreshnessStatus = "CURRENT" | "STABLE_BUT_OLD" | "UNCERTAIN" | "OUTDATED";

/**
 * Fonte visível de uma projecção (subconjunto seguro de SourceEvidence).
 * Só os campos apresentáveis; os campos internos não são declarados de propósito.
 */
export interface VisibleSource {
  title: string | null;
  sourceType: string | null;
  sourceRole: SourceRole | null;
  sourceQuality: SourceQuality | null;
  freshnessStatus: FreshnessStatus | null;
  legalReference: string | null;
  url: string | null;
}

/**
 * AnswerProjection do backend, na vista de apresentação.
 * hiddenDiagnostics/projectionRulesApplied existem no DTO mas NÃO se mostram.
 */
export interface AnswerProjection {
  targetVisibilityLevel: VisibilityLevel;
  visibleAnswerType: AnswerType;
  visibleAnswer: string | null;
  visibleSources: VisibleSource[];
  visibleWarnings: string[];
  visibleLimitations: string[];
  visibleParecerRequirement: ParecerRequirement;
}

/**
 * Resposta de POST /api/v1/admin/ai/demo/ask: apenas a projecção DEMO, sem resposta
 * documentada completa, diagnóstico, provider, modelo ou tokens.
 */
export interface DemoAskResponse {
  projectedAnswer: AnswerProjection;
}
