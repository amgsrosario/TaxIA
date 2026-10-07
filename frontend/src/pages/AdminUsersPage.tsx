import { useCallback, useEffect, useState, type FormEvent } from "react";
import {
  ApiError,
  disableStaffUser,
  listStaffUsers,
  reactivateStaffUser,
  resetStaffPassword,
  revokeStaffSessions,
} from "../api/client";
import type { StaffUserSummary } from "../api/types";

const MIN_LENGTH = 12;

type Action = "revoke" | "reset" | "disable" | "reactivate";

const ACTION_LABEL: Record<Action, string> = {
  revoke: "Invalidar sessões",
  reset: "Repor password",
  disable: "Desactivar",
  reactivate: "Reactivar",
};

const ACTION_HELP: Record<Action, string> = {
  revoke: "Todas as sessões deste utilizador terminam imediatamente.",
  reset:
    "Defina uma password temporária e entregue-a por um canal separado. O utilizador terá de a mudar no próximo login; todas as sessões terminam.",
  disable: "O utilizador deixa de poder entrar e todas as sessões terminam imediatamente.",
  reactivate: "O utilizador volta a poder entrar com um novo login. Sessões antigas continuam inválidas.",
};

/** Administração mínima de contas staff da organização (ADR-006). Só ADMIN. */
export function AdminUsersPage() {
  const [users, setUsers] = useState<StaffUserSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pending, setPending] = useState<{ user: StaffUserSummary; action: Action } | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listStaffUsers()
      .then(setUsers)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Erro ao carregar utilizadores."))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function done(message: string) {
    setPending(null);
    setNotice(message);
    load();
  }

  return (
    <div>
      <div className="toolbar">
        <h1>Utilizadores</h1>
        <button className="secondary" onClick={load} disabled={loading}>
          {loading ? "A actualizar…" : "Refrescar"}
        </button>
      </div>
      {error && <div className="banner error">{error}</div>}
      {notice && <div className="banner success">{notice}</div>}
      <div className="card table-scroll">
        <table>
          <thead>
            <tr>
              <th>Email</th>
              <th>Nome</th>
              <th>Estado</th>
              <th>Papéis</th>
              <th>Acções</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td>
                  {u.email}
                  {u.self && " (eu)"}
                </td>
                <td>{u.fullName}</td>
                <td>
                  {u.status === "ACTIVE" ? "Activo" : "Desactivado"}
                  {u.mustChangePassword && " · mudança de password pendente"}
                </td>
                <td>{u.roles.join(", ")}</td>
                <td>
                  <div className="actions-row" style={{ marginTop: 0 }}>
                    <button className="secondary" onClick={() => setPending({ user: u, action: "revoke" })}>
                      Invalidar sessões
                    </button>
                    {/* ADR-006: contas ADMIN não são repostas por outro ADMIN (backend devolve 409). */}
                    {!u.self && !u.roles.includes("ADMIN") && (
                      <button className="secondary" onClick={() => setPending({ user: u, action: "reset" })}>
                        Repor password
                      </button>
                    )}
                    {!u.self && u.status === "ACTIVE" && (
                      <button className="danger" onClick={() => setPending({ user: u, action: "disable" })}>
                        Desactivar
                      </button>
                    )}
                    {u.status === "DISABLED" && (
                      <button className="secondary" onClick={() => setPending({ user: u, action: "reactivate" })}>
                        Reactivar
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {!loading && users.length === 0 && (
              <tr>
                <td colSpan={5}>Sem utilizadores.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {pending && (
        <UserActionDialog
          user={pending.user}
          action={pending.action}
          onCancel={() => setPending(null)}
          onDone={done}
        />
      )}
    </div>
  );
}

interface UserActionDialogProps {
  user: StaffUserSummary;
  action: Action;
  onCancel: () => void;
  onDone: (message: string) => void;
}

/** Motivo obrigatório; no reset, password temporária nunca pré-preenchida nem guardada. */
function UserActionDialog({ user, action, onCancel, onDone }: UserActionDialogProps) {
  const [reason, setReason] = useState("");
  const [temporary, setTemporary] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!reason.trim()) {
      setError("Indique o motivo.");
      return;
    }
    if (action === "reset") {
      if ([...temporary].length < MIN_LENGTH) {
        setError(`A password temporária deve ter pelo menos ${MIN_LENGTH} caracteres.`);
        return;
      }
      if (temporary !== confirm) {
        setError("A confirmação não coincide.");
        return;
      }
    }
    setBusy(true);
    try {
      if (action === "revoke") await revokeStaffSessions(user.id, reason.trim());
      if (action === "reset") await resetStaffPassword(user.id, temporary, reason.trim());
      if (action === "disable") await disableStaffUser(user.id, reason.trim());
      if (action === "reactivate") await reactivateStaffUser(user.id, reason.trim());
      setTemporary("");
      setConfirm("");
      onDone(`${ACTION_LABEL[action]}: ${user.email} — concluído.`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Operação não concluída.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="dialog-backdrop" onClick={onCancel}>
      <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={handleSubmit}>
        <h3>
          {ACTION_LABEL[action]} — {user.email}
        </h3>
        <p>{ACTION_HELP[action]}</p>
        {error && <div className="banner error">{error}</div>}
        {action === "reset" && (
          <>
            <label htmlFor="temporary-password">Password temporária (mín. {MIN_LENGTH} caracteres)</label>
            <input id="temporary-password" type="password" autoComplete="new-password" required
              value={temporary} onChange={(e) => setTemporary(e.target.value)} />
            <label htmlFor="temporary-confirm">Confirmar password temporária</label>
            <input id="temporary-confirm" type="password" autoComplete="new-password" required
              value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          </>
        )}
        <label htmlFor="action-reason">Motivo (fica registado na auditoria)</label>
        <input id="action-reason" maxLength={500} required value={reason}
          onChange={(e) => setReason(e.target.value)} autoFocus={action !== "reset"} />
        <div className="dialog-actions">
          <button type="button" className="secondary" onClick={onCancel}>
            Cancelar
          </button>
          <button type="submit" className={action === "disable" ? "danger" : "gold"} disabled={busy}>
            {busy ? "A processar…" : ACTION_LABEL[action]}
          </button>
        </div>
      </form>
    </div>
  );
}
