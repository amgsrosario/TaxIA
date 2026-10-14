import { useState } from "react";
import { revokeSourceVerification, setSourceStableMatter, verifySource } from "../api/client";
import type { FreshnessStatus, SourceReference } from "../api/types";

// ─────────────────────────────────────────────────────────────────────────────
// Actualidade da fonte (C8, ADR-008). O estado é CALCULADO pelo servidor a partir de
// factos governados — não há escolha directa de CURRENT/STABLE_BUT_OLD/UNCERTAIN/OUTDATED.
// As acções só registam esses factos: verificação humana estruturada (data e responsável
// do servidor), confirmação da versão consolidada (legislação) e matéria estável com
// justificação obrigatória.
// ─────────────────────────────────────────────────────────────────────────────

const LABELS: Record<FreshnessStatus, string> = {
  CURRENT: "Actual",
  STABLE_BUT_OLD: "Antiga mas estável",
  UNCERTAIN: "Actualidade não confirmada",
  OUTDATED: "Desactualizada/histórica",
};

const ELIGIBLE = new Set(["LEGISLATION", "OFFICIAL_FAQ", "ADMINISTRATIVE_GUIDANCE"]);

/** Datas no fuso de Lisboa, o mesmo em que o servidor conta as janelas (ADR-008). */
function formatDate(value: string | null): string {
  if (!value) return "—";
  return value.length > 10
    ? new Date(value).toLocaleDateString("pt-PT", { timeZone: "Europe/Lisbon" })
    : value;
}

export function SourceFreshnessControls({
  qaId,
  source,
  disabled,
  onChanged,
  onError,
}: {
  qaId: string;
  source: SourceReference;
  disabled: boolean;
  onChanged: () => void;
  onError: (message: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [consolidated, setConsolidated] = useState(false);
  const [stableReason, setStableReason] = useState("");
  const [showStable, setShowStable] = useState(false);
  const isLegislation = source.sourceType === "LEGISLATION";
  const eligible = ELIGIBLE.has(source.sourceType);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    try {
      await action();
      onChanged();
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const status = source.freshnessStatus;
  return (
    <div className="source-freshness">
      <div>
        <span className={`badge freshness-${status}`}>{LABELS[status] ?? status}</span>
        {source.revalidationNeeded && <span className="badge risk-HIGH" style={{ marginLeft: 6 }}>Revalidar</span>}
      </div>
      <div className="muted small">{source.freshnessReason}</div>
      {source.verifiedAt && (
        <div className="muted small">
          Verificada em {formatDate(source.verifiedAt)}
          {isLegislation && (source.consolidatedVersionConfirmed ? " · versão consolidada confirmada" : " · versão consolidada NÃO confirmada")}
          {source.currentUntil && ` · actual até ${formatDate(source.currentUntil)}`}
          {source.stableUntil && ` · estável até ${formatDate(source.stableUntil)}`}
        </div>
      )}
      {source.stableMatter && <div className="muted small">Matéria estável: {source.stableMatterReason}</div>}

      {eligible ? (
        <div className="freshness-actions">
          {isLegislation && (
            <label className="small">
              <input type="checkbox" checked={consolidated} disabled={disabled || busy}
                onChange={(e) => setConsolidated(e.target.checked)} />{" "}
              Versão oficial/consolidada confirmada
            </label>
          )}
          <button className="secondary" disabled={disabled || busy}
            onClick={() => run(() => verifySource(qaId, source.id, isLegislation && consolidated))}>
            Registar verificação hoje
          </button>
          {source.verifiedAt && (
            <button className="secondary" disabled={disabled || busy}
              onClick={() => run(() => revokeSourceVerification(qaId, source.id))}>
              Revogar verificação
            </button>
          )}
          {source.stableMatter ? (
            <button className="secondary" disabled={disabled || busy}
              onClick={() => run(() => setSourceStableMatter(qaId, source.id, false, null))}>
              Desmarcar matéria estável
            </button>
          ) : source.verifiedAt && (
            showStable ? (
              <span>
                <input type="text" placeholder="Justificação (obrigatória)" value={stableReason}
                  disabled={disabled || busy} onChange={(e) => setStableReason(e.target.value)} />
                <button className="secondary" disabled={disabled || busy || stableReason.trim() === ""}
                  onClick={() => run(() => setSourceStableMatter(qaId, source.id, true, stableReason.trim()))}>
                  Confirmar matéria estável
                </button>
              </span>
            ) : (
              <button className="secondary" disabled={disabled || busy} onClick={() => setShowStable(true)}>
                Marcar matéria estável…
              </button>
            )
          )}
        </div>
      ) : (
        <div className="muted small">Tipo de fonte não elegível para verificação de actualidade.</div>
      )}
    </div>
  );
}
