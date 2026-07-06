import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  addSource,
  ApiError,
  archiveQa,
  getQaDetail,
  markOutdated,
  markPendingReview,
  rejectQa,
  updateCuration,
  validateQa,
} from "../api/client";
import type {
  CurationUpdateRequest,
  KnowledgeQaDetail,
  KnowledgeRiskLevel,
  KnowledgeSourceType,
  KnowledgeTopic,
} from "../api/types";
import { RiskBadge, StatusBadge } from "../components/Badges";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { useAuth } from "../auth/AuthContext";

const RISKS: KnowledgeRiskLevel[] = ["LOW", "MEDIUM", "HIGH", "CRITICAL"];
const TOPICS: KnowledgeTopic[] = [
  "IVA", "IRC", "IRS", "SEGURANCA_SOCIAL", "TRABALHO",
  "CONTABILIDADE", "PROCEDIMENTO_TRIBUTARIO", "FATURACAO", "OUTROS",
];
const SOURCE_TYPES: KnowledgeSourceType[] = [
  "LEGISLATION", "ADMINISTRATIVE_GUIDANCE", "CASE_LAW",
  "OFFICIAL_FAQ", "INTERNAL_OPINION", "ACCOUNTING_STANDARD", "OTHER",
];

type PendingAction =
  | { kind: "pending-review" }
  | { kind: "validate" }
  | { kind: "reject" }
  | { kind: "outdated" }
  | { kind: "archive" };

interface CurationForm {
  shortAnswer: string;
  technicalAnswer: string;
  notes: string;
  riskLevel: KnowledgeRiskLevel;
  topic: KnowledgeTopic | "";
  subtopic: string;
  validFrom: string;
  validTo: string;
}

interface SourceForm {
  sourceType: KnowledgeSourceType;
  title: string;
  legalReference: string;
  url: string;
  notes: string;
}

export function QaDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { session } = useAuth();

  const [detail, setDetail] = useState<KnowledgeQaDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
  const [rejectReason, setRejectReason] = useState("");

  const [form, setForm] = useState<CurationForm | null>(null);
  const [sourceForm, setSourceForm] = useState<SourceForm>({
    sourceType: "ADMINISTRATIVE_GUIDANCE",
    title: "",
    legalReference: "",
    url: "",
    notes: "",
  });

  function formFrom(d: KnowledgeQaDetail): CurationForm {
    return {
      shortAnswer: d.shortAnswer ?? "",
      technicalAnswer: d.technicalAnswer ?? "",
      notes: d.notes ?? "",
      riskLevel: d.riskLevel,
      topic: d.topic ?? "",
      subtopic: d.subtopic ?? "",
      validFrom: d.validFrom ?? "",
      validTo: d.validTo ?? "",
    };
  }

  /** Aplica a resposta do servidor como única fonte de verdade (detalhe + formulário). */
  function applyDetail(d: KnowledgeQaDetail) {
    setDetail(d);
    setForm(formFrom(d));
  }

  const load = useCallback(() => {
    if (!id) return;
    setLoading(true);
    setError(null);
    getQaDetail(id)
      .then((d) => {
        setDetail(d);
        setForm(formFrom(d));
      })
      .catch((err) =>
        setError(err instanceof ApiError ? err.message : "Erro ao carregar o caso."),
      )
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  if (loading) return <div className="empty-state">A carregar…</div>;
  if (error && !detail) {
    return (
      <div>
        <div className="banner error">{error}</div>
        <button className="secondary" onClick={() => navigate("/qa")}>← Voltar à lista</button>
      </div>
    );
  }
  if (!detail || !form || !id) return null;

  const isArchived = detail.curationStatus === "ARCHIVED";
  const highRisk = detail.riskLevel === "HIGH" || detail.riskLevel === "CRITICAL";

  // ── Alterações não guardadas ───────────────────────────────────────────────
  // Mudanças de estado com texto por gravar perdiam-no silenciosamente
  // (o reload repunha o formulário a partir da BD). Enquanto houver
  // diferenças entre o formulário e o que está persistido, TODAS as acções
  // de estado ficam bloqueadas.
  const baseline = formFrom(detail);
  const dirty = (Object.keys(baseline) as (keyof CurationForm)[]).some(
    (k) => form[k] !== baseline[k],
  );

  // ── Guard rails avaliados sobre os valores PERSISTIDOS (nunca o formulário) ─
  const persistedShortAnswer = (detail.shortAnswer ?? "").trim();
  const hasSources = detail.sources.length > 0;
  const hasUsableSource = detail.sources.some(
    (s) => (s.url && s.url.trim() !== "") || (s.legalReference && s.legalReference.trim() !== ""),
  );

  const reviewBlockers: string[] = [];
  if (dirty) reviewBlockers.push("Há alterações não guardadas — guarde primeiro.");
  if (!persistedShortAnswer)
    reviewBlockers.push("Falta a resposta curada curta (guardada) antes de passar para revisão.");

  const validationBlockers: string[] = [];
  if (dirty) validationBlockers.push("Há alterações não guardadas — guarde primeiro.");
  if (!persistedShortAnswer)
    validationBlockers.push("Falta a resposta curada curta (guardada na base de dados).");
  if (!hasSources) validationBlockers.push("O caso não tem nenhuma fonte associada.");
  if (hasSources && !hasUsableSource)
    validationBlockers.push("Nenhuma fonte tem URL ou referência legal suficiente.");
  if (detail.curationStatus !== "PENDING_REVIEW")
    validationBlockers.push("O caso tem de estar Em revisão antes de ser validado.");

  function buildCurationPayload(f: CurationForm, d: KnowledgeQaDetail): CurationUpdateRequest {
    return {
      normalizedQuestion: d.normalizedQuestion,
      shortAnswer: f.shortAnswer.trim() || null,
      technicalAnswer: f.technicalAnswer.trim() || null,
      topic: f.topic || null,
      subtopic: f.subtopic.trim() || null,
      jurisdiction: d.jurisdiction ?? "PT",
      riskLevel: f.riskLevel,
      requiresHumanValidation: d.requiresHumanValidation,
      validFrom: f.validFrom || null,
      validTo: f.validTo || null,
      notes: f.notes.trim() || null,
    };
  }

  async function run(action: () => Promise<unknown>, successMessage: string) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      setNotice(successMessage);
      load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Erro inesperado.");
    } finally {
      setBusy(false);
      setPendingAction(null);
      setRejectReason("");
    }
  }

  // Guardar usa a resposta do PATCH como fonte de verdade — o que fica no ecrã
  // é exactamente o que o backend confirmou ter persistido.
  const saveCuration = async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const saved = await updateCuration(id, buildCurationPayload(form, detail));
      applyDetail(saved);
      setNotice("Alterações de curadoria guardadas na base de dados.");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Erro inesperado ao guardar.");
    } finally {
      setBusy(false);
    }
  };

  const submitSource = () => {
    if (!sourceForm.title.trim()) {
      setError("A fonte precisa de um título.");
      return;
    }
    if (sourceForm.url && !/^https?:\/\//.test(sourceForm.url.trim())) {
      setError("A URL da fonte tem de começar por http:// ou https://.");
      return;
    }
    return run(
      () =>
        addSource(id, {
          sourceType: sourceForm.sourceType,
          title: sourceForm.title.trim(),
          legalReference: sourceForm.legalReference.trim() || null,
          url: sourceForm.url.trim() || null,
          documentId: null,
          fragmentId: null,
          validFrom: null,
          validTo: null,
          notes: sourceForm.notes.trim() || null,
        }),
      "Fonte associada.",
    ).then(() =>
      setSourceForm({ sourceType: "ADMINISTRATIVE_GUIDANCE", title: "", legalReference: "", url: "", notes: "" }),
    );
  };

  function executePendingAction() {
    if (!pendingAction) return;
    const reviewer = session?.email ?? "curador";
    switch (pendingAction.kind) {
      case "pending-review":
        return run(() => markPendingReview(id!), "Caso passado para revisão.");
      case "validate":
        return run(() => validateQa(id!, reviewer), "Caso validado.");
      case "reject":
        return run(() => rejectQa(id!, reviewer, rejectReason), "Caso rejeitado.");
      case "outdated":
        return run(() => markOutdated(id!), "Caso marcado como desactualizado.");
      case "archive":
        return run(() => archiveQa(id!), "Caso arquivado.");
    }
  }

  return (
    <div>
      <div className="toolbar">
        <div>
          <button className="secondary" onClick={() => navigate("/qa")}>← Lista</button>
        </div>
        <div className="meta-row">
          <StatusBadge status={detail.curationStatus} />
          <RiskBadge risk={detail.riskLevel} />
          {detail.published && <span className="badge status-VALIDATED">PUBLICADO</span>}
        </div>
      </div>

      <h1>{detail.externalKey ?? detail.id}</h1>
      <div className="meta-row" style={{ marginBottom: 14 }}>
        <div className="item"><span>Tema:</span> {detail.topic ?? "—"} {detail.subtopic ? `· ${detail.subtopic}` : ""}</div>
        <div className="item"><span>Jurisdição:</span> {detail.jurisdiction ?? "—"}</div>
        <div className="item"><span>Revisão humana:</span> {detail.requiresHumanValidation ? "Obrigatória" : "Não"}</div>
        <div className="item"><span>Origem:</span> {detail.sourceSystem ?? "—"}</div>
        <div className="item"><span>Validade:</span> {detail.validFrom ?? "?"} → {detail.validTo ?? "?"}</div>
        {detail.reviewedBy && (
          <div className="item"><span>Revisto por:</span> {detail.reviewedBy}</div>
        )}
      </div>

      {error && <div className="banner error">{error}</div>}
      {notice && <div className="banner success">{notice}</div>}
      {isArchived && (
        <div className="banner warning">
          Caso arquivado — alterações exigem confirmação e, em regra, não devem acontecer.
        </div>
      )}

      <div className="detail-grid">
        <div className="card">
          <h2>Conteúdo importado (imutável)</h2>
          <div className="field-block">
            <label>Pergunta original</label>
            <div className="value">{detail.originalQuestion}</div>
          </div>
          <div className="field-block">
            <label>Resposta importada (fonte — não é resposta canónica)</label>
            <div className="value">{detail.originalAnswer}</div>
          </div>
        </div>

        <div className="card">
          <h2>Curadoria TaxIA</h2>
          <div className="field-block">
            <label>Resposta curada — curta</label>
            <textarea
              value={form.shortAnswer}
              onChange={(e) => setForm({ ...form, shortAnswer: e.target.value })}
              placeholder="Síntese própria da TaxIA (obrigatória antes de validar)"
              disabled={busy}
            />
          </div>
          <div className="field-block">
            <label>Resposta curada — técnica</label>
            <textarea
              value={form.technicalAnswer}
              onChange={(e) => setForm({ ...form, technicalAnswer: e.target.value })}
              placeholder="Fundamentação técnica detalhada (opcional)"
              disabled={busy}
            />
          </div>
          <div className="field-block">
            <label>Notas de curadoria</label>
            <textarea
              value={form.notes}
              onChange={(e) => setForm({ ...form, notes: e.target.value })}
              disabled={busy}
            />
          </div>
          <div className="form-grid">
            <div>
              <label>Risco</label>
              <select
                value={form.riskLevel}
                onChange={(e) => setForm({ ...form, riskLevel: e.target.value as KnowledgeRiskLevel })}
                disabled={busy}
              >
                {RISKS.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            <div>
              <label>Tema</label>
              <select
                value={form.topic}
                onChange={(e) => setForm({ ...form, topic: e.target.value as KnowledgeTopic | "" })}
                disabled={busy}
              >
                <option value="">—</option>
                {TOPICS.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            <div>
              <label>Válido de</label>
              <input type="date" value={form.validFrom}
                onChange={(e) => setForm({ ...form, validFrom: e.target.value })} disabled={busy} />
            </div>
            <div>
              <label>Válido até</label>
              <input type="date" value={form.validTo}
                onChange={(e) => setForm({ ...form, validTo: e.target.value })} disabled={busy} />
            </div>
            <div className="full">
              <label>Subtema</label>
              <input value={form.subtopic}
                onChange={(e) => setForm({ ...form, subtopic: e.target.value })} disabled={busy} />
            </div>
          </div>
          <div className="actions-row">
            <button onClick={saveCuration} disabled={busy}>Guardar alterações</button>
          </div>
        </div>
      </div>

      <div className="card">
        <h2>Fontes ({detail.sources.length})</h2>
        {detail.sources.length === 0 ? (
          <div className="banner warning">
            Sem fontes associadas — a validação está bloqueada até existir pelo menos
            uma fonte com URL ou referência legal.
          </div>
        ) : (
          <table>
            <thead>
              <tr><th>Tipo</th><th>Título</th><th>Referência legal</th><th>URL</th><th>Notas</th></tr>
            </thead>
            <tbody>
              {detail.sources.map((s) => (
                <tr key={s.id}>
                  <td>{s.sourceType}</td>
                  <td>{s.title}</td>
                  <td>{s.legalReference ?? "—"}</td>
                  <td>
                    {s.url
                      ? <a href={s.url} target="_blank" rel="noreferrer">{s.url.slice(0, 60)}…</a>
                      : <span className="badge risk-HIGH">URL pendente</span>}
                  </td>
                  <td className="muted">{s.notes ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <h3 style={{ marginTop: 18 }}>Adicionar fonte</h3>
        <p className="muted">
          O backend não expõe edição de fontes existentes — correcções fazem-se
          adicionando uma fonte completa e correcta.
        </p>
        <div className="form-grid">
          <div>
            <label>Tipo</label>
            <select
              value={sourceForm.sourceType}
              onChange={(e) => setSourceForm({ ...sourceForm, sourceType: e.target.value as KnowledgeSourceType })}
              disabled={busy}
            >
              {SOURCE_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <div>
            <label>Título *</label>
            <input value={sourceForm.title}
              onChange={(e) => setSourceForm({ ...sourceForm, title: e.target.value })} disabled={busy} />
          </div>
          <div>
            <label>Referência legal</label>
            <input value={sourceForm.legalReference}
              onChange={(e) => setSourceForm({ ...sourceForm, legalReference: e.target.value })}
              placeholder="ex.: CIVA, art. 19.º" disabled={busy} />
          </div>
          <div>
            <label>URL oficial</label>
            <input value={sourceForm.url}
              onChange={(e) => setSourceForm({ ...sourceForm, url: e.target.value })}
              placeholder="https://…" disabled={busy} />
          </div>
          <div className="full">
            <label>Notas da fonte</label>
            <input value={sourceForm.notes}
              onChange={(e) => setSourceForm({ ...sourceForm, notes: e.target.value })} disabled={busy} />
          </div>
        </div>
        <div className="actions-row">
          <button className="secondary" onClick={submitSource} disabled={busy}>Associar fonte</button>
        </div>
      </div>

      <div className="card">
        <h2>Acções de estado</h2>
        {dirty && (
          <div className="banner warning">
            <strong>Alterações não guardadas.</strong> Guarde primeiro — mudar de
            estado sem guardar descartaria o texto escrito.
          </div>
        )}
        {reviewBlockers.length > 0 &&
          (detail.curationStatus === "IMPORTED" || detail.curationStatus === "NEEDS_UPDATE") && (
          <div className="banner info">
            <strong>Passagem para revisão bloqueada:</strong>
            <ul style={{ margin: "6px 0 0 18px" }}>
              {reviewBlockers.map((b) => <li key={b}>{b}</li>)}
            </ul>
          </div>
        )}
        {validationBlockers.length > 0 && detail.curationStatus === "PENDING_REVIEW" && (
          <div className="banner info">
            <strong>Validação bloqueada:</strong>
            <ul style={{ margin: "6px 0 0 18px" }}>
              {validationBlockers.map((b) => <li key={b}>{b}</li>)}
            </ul>
          </div>
        )}
        <div className="actions-row">
          <button
            disabled={
              busy ||
              reviewBlockers.length > 0 ||
              (detail.curationStatus !== "IMPORTED" && detail.curationStatus !== "NEEDS_UPDATE")
            }
            onClick={() => setPendingAction({ kind: "pending-review" })}
          >
            Passar para revisão
          </button>
          <button
            className="gold"
            disabled={busy || validationBlockers.length > 0}
            onClick={() => setPendingAction({ kind: "validate" })}
          >
            Validar
          </button>
          <button
            className="danger"
            disabled={busy || dirty || detail.curationStatus !== "PENDING_REVIEW"}
            onClick={() => setPendingAction({ kind: "reject" })}
          >
            Rejeitar
          </button>
          <button
            className="secondary"
            disabled={busy || dirty || (detail.curationStatus !== "VALIDATED" && detail.curationStatus !== "PENDING_REVIEW")}
            onClick={() => setPendingAction({ kind: "outdated" })}
          >
            Precisa de actualização
          </button>
          <button
            className="secondary"
            disabled={busy || dirty || !["REJECTED", "OUTDATED", "NEEDS_UPDATE"].includes(detail.curationStatus)}
            onClick={() => setPendingAction({ kind: "archive" })}
          >
            Arquivar
          </button>
        </div>
        <p className="muted" style={{ marginTop: 10 }}>
          Publicação, reindexação e chamadas a IA estão intencionalmente fora deste
          backoffice nesta etapa — nenhum caso é publicado nem indexado a partir daqui.
        </p>
      </div>

      {pendingAction?.kind === "pending-review" && (
        <ConfirmDialog
          title="Passar para revisão?"
          confirmLabel="Passar para revisão"
          onConfirm={executePendingAction}
          onCancel={() => setPendingAction(null)}
        >
          <p>O caso <strong>{detail.externalKey}</strong> fica em PENDING_REVIEW, pronto para curadoria final.</p>
        </ConfirmDialog>
      )}

      {pendingAction?.kind === "validate" && (
        <ConfirmDialog
          title="Validar caso?"
          confirmLabel="Validar"
          requireTypedText={highRisk ? "VALIDAR" : undefined}
          onConfirm={executePendingAction}
          onCancel={() => setPendingAction(null)}
        >
          <p>
            Vai validar <strong>{detail.externalKey}</strong> como conhecimento aprovado da TaxIA
            (revisor: {session?.email}).
          </p>
          {highRisk && (
            <div className="banner warning">
              Caso de risco <strong>{form.riskLevel}</strong> — confirmação reforçada obrigatória.
            </div>
          )}
          <p className="muted">A validação não publica nem cria embeddings.</p>
        </ConfirmDialog>
      )}

      {pendingAction?.kind === "reject" && (
        <ConfirmDialog
          title="Rejeitar caso?"
          confirmLabel="Rejeitar"
          danger
          onConfirm={executePendingAction}
          onCancel={() => setPendingAction(null)}
        >
          <p>O caso <strong>{detail.externalKey}</strong> será marcado como REJECTED.</p>
          <label>Motivo</label>
          <textarea value={rejectReason} onChange={(e) => setRejectReason(e.target.value)} />
        </ConfirmDialog>
      )}

      {pendingAction?.kind === "outdated" && (
        <ConfirmDialog
          title="Marcar como desactualizado?"
          confirmLabel="Marcar"
          onConfirm={executePendingAction}
          onCancel={() => setPendingAction(null)}
        >
          <p>O caso <strong>{detail.externalKey}</strong> deixa de poder sustentar respostas até ser actualizado.</p>
        </ConfirmDialog>
      )}

      {pendingAction?.kind === "archive" && (
        <ConfirmDialog
          title="Arquivar caso?"
          confirmLabel="Arquivar"
          danger
          requireTypedText="ARQUIVAR"
          onConfirm={executePendingAction}
          onCancel={() => setPendingAction(null)}
        >
          <p>O caso <strong>{detail.externalKey}</strong> fica arquivado, apenas para histórico e auditoria.</p>
        </ConfirmDialog>
      )}
    </div>
  );
}
