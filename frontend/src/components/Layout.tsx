import { useEffect, useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { fetchHealth } from "../api/client";
import { useAuth } from "../auth/AuthContext";

export function Layout() {
  const { session, signOut } = useAuth();
  const navigate = useNavigate();
  const [health, setHealth] = useState<string>("…");

  useEffect(() => {
    fetchHealth()
      .then((h) => setHealth(h.status))
      .catch(() => setHealth("INDISPONÍVEL"));
  }, []);

  function handleLogout() {
    signOut();
    navigate("/login", { replace: true });
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          Tax<span>IA</span>
        </div>
        <div className="org">Org: {session?.organizationId ?? "—"}</div>
        <nav>
          <NavLink to="/qa">Conhecimento Q&amp;A</NavLink>
        </nav>
        <div className="spacer" />
        <div className="user">
          <div>{session?.fullName}</div>
          <div>{session?.email}</div>
          <div>Sistema: {health}</div>
          <button
            className="secondary"
            style={{ marginTop: 10, width: "100%" }}
            onClick={handleLogout}
          >
            Terminar sessão
          </button>
        </div>
      </aside>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
