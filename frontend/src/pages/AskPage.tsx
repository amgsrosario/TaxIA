import { useState } from "react";
import type { FormEvent } from "react";
import { ApiError, askTaxia } from "../api/client";
import type { AnswerProjection } from "../api/types";
import { DocumentedAnswerPanel } from "../components/DocumentedAnswerPanel";

const MAX_QUESTION_LENGTH = 4000;

// Mensagens próprias desta página: as do backend para IA/embeddings são técnicas
// (e em inglês), por isso são escolhidas pelo estado/código e nunca mostradas.
function askErrorMessage(err: unknown): string {
  if (!(err instanceof ApiError)) return "Ocorreu um erro inesperado. Tente novamente.";
  if (err.status === 0 || err.status === 401) return err.message;
  if (err.code.startsWith("EMBEDDING_")) {
    return "A pesquisa na base documental está indisponível de momento.";
  }
  if (err.status === 429) {
    return "O serviço de IA está temporariamente sobrecarregado. Tente novamente dentro de instantes.";
  }
  if (err.code.startsWith("AI_") || [502, 503, 504].includes(err.status)) {
    return "O serviço de IA não está disponível de momento. Tente novamente mais tarde.";
  }
  if (err.status === 400) return "A pergunta é inválida ou demasiado longa.";
  if (err.status === 403) return "Esta funcionalidade está disponível apenas para administradores.";
  return "Ocorreu um erro inesperado. Tente novamente.";
}

export function AskPage() {
  const [question, setQuestion] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [answer, setAnswer] = useState<AnswerProjection | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (busy || question.trim() === "") return;
    setBusy(true);
    setError(null);
    setAnswer(null);
    try {
      const response = await askTaxia(question);
      // Uma Resposta-limite chega aqui como resposta normal (HTTP 200), nunca como erro.
      setAnswer(response.projectedAnswer);
    } catch (err) {
      setError(askErrorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div>
      <h1>Perguntar à TaxIA</h1>
      <p className="muted">
        A resposta baseia-se exclusivamente na base documental validada da TaxIA.
      </p>

      <form className="card" onSubmit={handleSubmit}>
        <label htmlFor="ask-question">Pergunta</label>
        <textarea
          id="ask-question"
          value={question}
          maxLength={MAX_QUESTION_LENGTH}
          readOnly={busy}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="Escreva a sua pergunta fiscal…"
        />
        <div className="actions-row">
          <button type="submit" disabled={busy || question.trim() === ""}>
            Perguntar
          </button>
          {busy && (
            <span className="muted">A consultar a base documental e a preparar a resposta…</span>
          )}
        </div>
      </form>

      {error && <div className="banner error">{error}</div>}
      {answer && <DocumentedAnswerPanel projection={answer} />}
    </div>
  );
}
