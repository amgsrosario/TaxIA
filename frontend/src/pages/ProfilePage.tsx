import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, logoutAllSessions } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { PasswordChangeForm } from "../components/PasswordChangeForm";

export function ProfilePage() {
  const { session, signOut } = useAuth();
  const navigate = useNavigate();
  const [confirmLogoutAll, setConfirmLogoutAll] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function endSession(message: string) {
    signOut();
    navigate("/login", { replace: true, state: { message } });
  }

  async function handleLogoutAll() {
    setConfirmLogoutAll(false);
    try {
      await logoutAllSessions();
      endSession("Todas as sessões foram terminadas. Inicie sessão novamente.");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Não foi possível terminar as sessões.");
    }
  }

  return (
    <div>
      <div className="toolbar">
        <h1>Perfil</h1>
      </div>
      {error && <div className="banner error">{error}</div>}
      <div className="card">
        <div><strong>{session?.fullName}</strong></div>
        <div>{session?.email}</div>
        <div>Papéis: {session?.roles.join(", ") || "—"}</div>
      </div>
      <div className="card">
        <h2>Alterar password</h2>
        <p>Ao alterar a password, todas as sessões abertas terminam, incluindo esta.</p>
        <PasswordChangeForm
          onChanged={() => endSession("Password alterada. Inicie sessão com a nova password.")}
        />
      </div>
      <div className="card">
        <h2>Sessões</h2>
        <p>Termina todas as sessões deste utilizador em todos os dispositivos, incluindo esta.</p>
        <button className="secondary" onClick={() => setConfirmLogoutAll(true)}>
          Terminar todas as sessões
        </button>
      </div>
      {confirmLogoutAll && (
        <ConfirmDialog
          title="Terminar todas as sessões?"
          confirmLabel="Terminar sessões"
          danger
          onConfirm={handleLogoutAll}
          onCancel={() => setConfirmLogoutAll(false)}
        >
          Todas as sessões terminam imediatamente, incluindo esta. Terá de iniciar sessão novamente.
        </ConfirmDialog>
      )}
    </div>
  );
}
