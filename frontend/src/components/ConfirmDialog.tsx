import { useState, type ReactNode } from "react";

interface ConfirmDialogProps {
  title: string;
  children: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  /** Se definido, o utilizador tem de escrever este texto para confirmar (acções de alto risco). */
  requireTypedText?: string;
  onConfirm: () => void;
  onCancel: () => void;
}

/** Diálogo de confirmação para acções sensíveis — nada acontece sem clique explícito. */
export function ConfirmDialog({
  title,
  children,
  confirmLabel,
  danger,
  requireTypedText,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const [typed, setTyped] = useState("");
  const blocked = requireTypedText !== undefined && typed !== requireTypedText;

  return (
    <div className="dialog-backdrop" onClick={onCancel}>
      <div className="dialog" onClick={(e) => e.stopPropagation()}>
        <h3>{title}</h3>
        <div>{children}</div>
        {requireTypedText !== undefined && (
          <div style={{ marginTop: 12 }}>
            <label>
              Para confirmar, escreva <strong>{requireTypedText}</strong>:
            </label>
            <input
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              autoFocus
            />
          </div>
        )}
        <div className="dialog-actions">
          <button className="secondary" onClick={onCancel}>
            Cancelar
          </button>
          <button
            className={danger ? "danger" : "gold"}
            disabled={blocked}
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
