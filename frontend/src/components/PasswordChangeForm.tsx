import { useState, type FormEvent } from "react";
import { ApiError, changeOwnPassword } from "../api/client";

/** Mínimo aprovado (ADR-006); o backend valida sempre e é a fonte de verdade. */
const MIN_LENGTH = 12;

interface PasswordChangeFormProps {
  /** Chamado após 204: todas as sessões terminaram, incluindo a actual. */
  onChanged: () => void;
  submitLabel?: string;
}

/**
 * Formulário de mudança da própria password. Os valores vivem só no estado do
 * componente (nunca em storage) e são limpos após cada submissão.
 */
export function PasswordChangeForm({ onChanged, submitLabel = "Alterar password" }: PasswordChangeFormProps) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if ([...next].length < MIN_LENGTH) {
      setError(`A nova password deve ter pelo menos ${MIN_LENGTH} caracteres.`);
      return;
    }
    if (next !== confirm) {
      setError("A confirmação não coincide com a nova password.");
      return;
    }
    setBusy(true);
    try {
      await changeOwnPassword(current, next);
      setCurrent("");
      setNext("");
      setConfirm("");
      onChanged();
    } catch (err) {
      setCurrent("");
      setError(err instanceof ApiError ? err.message : "Não foi possível alterar a password.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="password-form">
      {error && <div className="banner error">{error}</div>}
      <div>
        <label htmlFor="current-password">Password actual</label>
        <input id="current-password" type="password" autoComplete="current-password" required
          value={current} onChange={(e) => setCurrent(e.target.value)} />
      </div>
      <div>
        <label htmlFor="new-password">Nova password (mín. {MIN_LENGTH} caracteres, diferente do email)</label>
        <input id="new-password" type="password" autoComplete="new-password" required
          value={next} onChange={(e) => setNext(e.target.value)} />
      </div>
      <div>
        <label htmlFor="confirm-password">Confirmar nova password</label>
        <input id="confirm-password" type="password" autoComplete="new-password" required
          value={confirm} onChange={(e) => setConfirm(e.target.value)} />
      </div>
      <button type="submit" disabled={busy}>{busy ? "A alterar…" : submitLabel}</button>
    </form>
  );
}
