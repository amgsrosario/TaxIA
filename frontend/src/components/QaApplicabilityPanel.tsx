import { useCallback, useEffect, useState } from "react";
import {
  addApplicabilityExclusion,
  ApiError,
  approveApplicabilityRemoval,
  cancelApplicabilityRemoval,
  getApplicability,
  markApplicabilityReviewed,
  removeApplicabilityExclusion,
} from "../api/client";
import type { ApplicabilityExclusion, KnowledgeQaApplicability } from "../api/types";
import { ConfirmDialog } from "./ConfirmDialog";

const MAX_NOTE = 500;

function formatDate(value: string | null): string {
  return value ? new Date(value).toLocaleString("pt-PT") : "—";
}

/**
 * Âmbito de aplicação (M4-SCOPE-V2, ADR-004): âmbito derivado (só leitura), exclusões governadas
 * ("Não se aplica a") e marca de revisão. Só backoffice admin; nada disto chega a DEMO/EXTERNAL.
 */
export function QaApplicabilityPanel({
  qaId,
  reviewer,
  onChanged,
}: {
  qaId: string;
  reviewer: string;
  /** Alterações efectivas mudam a versão da Q&amp;A (ADR-005): a página recarrega o detalhe. */
  onChanged?: () => void;
}) {
  const [state, setState] = useState<KnowledgeQaApplicability | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [marker, setMarker] = useState("");
  const [note, setNote] = useState("");
  const [toApprove, setToApprove] = useState<ApplicabilityExclusion | null>(null);

  const load = useCallback(() => {
    getApplicability(qaId)
      .then(setState)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Erro ao carregar o âmbito."));
  }, [qaId]);

  useEffect(() => {
    load();
  }, [load]);

  /** A mensagem é derivada da resposta do servidor (fonte de verdade), não do estado anterior. */
  async function run(
    action: () => Promise<KnowledgeQaApplicability>,
    message: (result: KnowledgeQaApplicability) => string,
  ) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const result = await action();
      setState(result);
      setNotice(message(result));
      onChanged?.();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Operação falhou.");
    } finally {
      setBusy(false);
    }
  }

  if (!state) {
    return (
      <div className="card">
        <h2>Âmbito de aplicação</h2>
        {error ? <div className="banner error">{error}</div> : <div className="muted">A carregar…</div>}
      </div>
    );
  }

  const used = new Set(state.exclusions.map((e) => e.marker));
  const available = state.vocabulary.filter((m) => !used.has(m.code));
  const scope = state.derivedScope;
  const list = (values: string[]) => (values.length ? values.join(", ") : "—");

  return (
    <div className="card">
      <h2>Âmbito de aplicação</h2>
      {error && <div className="banner error">{error}</div>}
      {notice && <div className="banner success">{notice}</div>}

      {state.applicabilityReviewedAt ? (
        <div className="item">
          <span>Âmbito revisto:</span> {state.applicabilityReviewedBy} · {formatDate(state.applicabilityReviewedAt)}
        </div>
      ) : (
        <div className="banner warning">Âmbito não revisto — a Q&amp;A continua válida e publicável.</div>
      )}

      <div className="item">
        <span>Âmbito derivado (só leitura):</span> imposto {list(scope.taxDomains)} · categoria{" "}
        {list(scope.incomeCategories)} · operação {list(scope.operations)}
      </div>

      <h3 style={{ marginTop: 18 }}>Não se aplica a</h3>
      {state.exclusions.length === 0 ? (
        <p className="muted">Sem exclusões declaradas.</p>
      ) : (
        <ul>
          {state.exclusions.map((e) => (
            <li key={e.marker}>
              <strong>{e.label ?? e.marker}</strong>
              {e.note && <> — {e.note}</>}
              {e.removalPending ? (
                <>
                  {" "}
                  <span className="badge status-PENDING_REVIEW">remoção pendente de validação</span>{" "}
                  <button className="secondary" disabled={busy} onClick={() => setToApprove(e)}>
                    Validar remoção
                  </button>{" "}
                  <button
                    className="secondary"
                    disabled={busy}
                    onClick={() => run(() => cancelApplicabilityRemoval(qaId, e.marker), () => "Pedido de remoção cancelado.")}
                  >
                    Cancelar pedido
                  </button>
                </>
              ) : (
                <>
                  {" "}
                  <button
                    className="secondary"
                    disabled={busy}
                    onClick={() =>
                      run(
                        () => removeApplicabilityExclusion(qaId, e.marker),
                        (result) =>
                          result.exclusions.some((x) => x.marker === e.marker && x.removalPending)
                            ? "Remoção pedida: a exclusão continua efectiva até validação humana."
                            : "Exclusão removida.",
                      )
                    }
                  >
                    {state.removalRequiresValidation ? "Pedir remoção" : "Remover"}
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {state.removalRequiresValidation && (
        <p className="muted">
          Q&amp;A {state.published ? "publicada" : "validada"}: acrescentar uma exclusão é imediato; retirar uma
          exclusão alarga o âmbito e só fica efectivo depois de validação humana.
        </p>
      )}

      {available.length > 0 && (
        <div className="form-grid">
          <div>
            <label>Acrescentar exclusão</label>
            <select value={marker} onChange={(e) => setMarker(e.target.value)} disabled={busy}>
              <option value="">— escolher —</option>
              {available.map((m) => (
                <option key={m.code} value={m.code}>{m.label}</option>
              ))}
            </select>
          </div>
          <div>
            <label>Justificação (opcional)</label>
            <input value={note} maxLength={MAX_NOTE} onChange={(e) => setNote(e.target.value)} disabled={busy} />
          </div>
        </div>
      )}
      <div className="actions-row">
        {available.length > 0 && (
          <button
            disabled={busy || !marker}
            onClick={() =>
              run(async () => {
                const result = await addApplicabilityExclusion(qaId, marker, note.trim() || null);
                setMarker("");
                setNote("");
                return result;
              }, () => "Exclusão acrescentada (efectiva de imediato).")
            }
          >
            Acrescentar
          </button>
        )}
        <button
          className="secondary"
          disabled={busy}
          onClick={() => run(() => markApplicabilityReviewed(qaId, reviewer), () => "Âmbito marcado como revisto.")}
        >
          Marcar âmbito como revisto
        </button>
      </div>

      {toApprove && (
        <ConfirmDialog
          title="Validar remoção de exclusão"
          confirmLabel="Validar remoção"
          danger
          onCancel={() => setToApprove(null)}
          onConfirm={() => {
            const target = toApprove;
            setToApprove(null);
            run(
              () => approveApplicabilityRemoval(qaId, target.marker, reviewer),
              () => "Remoção validada: a exclusão deixou de ser aplicada.",
            );
          }}
        >
          <p>
            Retirar «{toApprove.label ?? toApprove.marker}» alarga o âmbito desta Q&amp;A: perguntas com esta
            situação passam a poder recebê-la como fonte.
          </p>
          <p className="muted">
            Pedido por {toApprove.removalRequestedBy ?? "—"} · validação registada como {reviewer}.
          </p>
        </ConfirmDialog>
      )}
    </div>
  );
}
