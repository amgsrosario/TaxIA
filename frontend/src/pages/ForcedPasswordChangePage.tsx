import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext";
import { PasswordChangeForm } from "../components/PasswordChangeForm";

/**
 * Ecrã obrigatório após reposição da password por ADMIN/break-glass. Não há
 * navegação funcional: o backend recusa qualquer rota além da mudança de password.
 */
export function ForcedPasswordChangePage() {
  const { session, signOut } = useAuth();
  const navigate = useNavigate();

  function finish(message?: string) {
    signOut();
    navigate("/login", { replace: true, state: message ? { message } : undefined });
  }

  return (
    <div className="login-wrap">
      <div className="login-card">
        <div className="brand">
          Tax<span>IA</span>
        </div>
        <div className="subtitle">Mudança de password obrigatória</div>
        <div className="banner warning">
          A password de {session?.email} foi reposta. Defina uma nova password para continuar;
          depois terá de iniciar sessão novamente.
        </div>
        <PasswordChangeForm
          submitLabel="Definir nova password"
          onChanged={() => finish("Password alterada. Inicie sessão com a nova password.")}
        />
        <button className="secondary" style={{ marginTop: 10, width: "100%" }} onClick={() => finish()}>
          Terminar sessão
        </button>
      </div>
    </div>
  );
}
