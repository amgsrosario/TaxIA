import type { KnowledgeCurationStatus, KnowledgeRiskLevel } from "../api/types";

const STATUS_LABELS: Record<KnowledgeCurationStatus, string> = {
  IMPORTED: "Importado",
  PENDING_REVIEW: "Em revisão",
  VALIDATED: "Validado",
  NEEDS_UPDATE: "Precisa actualização",
  OUTDATED: "Desactualizado",
  REJECTED: "Rejeitado",
  ARCHIVED: "Arquivado",
};

export function StatusBadge({ status }: { status: KnowledgeCurationStatus }) {
  return (
    <span className={`badge status-${status}`}>{STATUS_LABELS[status] ?? status}</span>
  );
}

export function RiskBadge({ risk }: { risk: KnowledgeRiskLevel }) {
  return <span className={`badge risk-${risk}`}>{risk}</span>;
}
