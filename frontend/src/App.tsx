import { useEffect, useRef, useState, type FormEvent } from "react";

type Scenario = "created" | "duplicate" | "validation" | "validation-general" | "server" | "network" | "unexpected";
type ApiBody = { resultado?: string; mensagem?: string; campos?: Array<{ campo: string; mensagem?: string }> };
type ApiResponse = { status: number; body?: ApiBody };
type Message = { text: string; kind: "info" | "success" | "error" } | null;

const apiMode = import.meta.env.VITE_API_MODE === "mock" ? "mock" : "http";
const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL || "http://localhost:8080").replace(/\/+$/, "");

const EDGE_WHITE_SPACE = /^[\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+|[\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+$/g;
const captions = ["Muito insatisfeito", "Insatisfeito", "Insatisfeito", "Insatisfeito", "Insatisfeito", "Satisfação intermediária", "Satisfação intermediária", "Satisfeito", "Satisfeito", "Muito satisfeito", "Muito satisfeito"];
const records = new Map<string, { resultado: string; id: string; descricao: string; nota: number }>();

function normalizeDescription(value: string) { return value.replace(EDGE_WHITE_SPACE, ""); }
function countCodePoints(value: string) { return Array.from(value).length; }
function newUuid() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (character) => {
    const random = Math.floor(Math.random() * 16);
    const value = character === "x" ? random : (random & 0x3) | 0x8;
    return value.toString(16);
  });
}

async function mockPostEvaluation(key: string, payload: { descricao: string; nota: number }, scenario: Scenario): Promise<ApiResponse> {
  await new Promise((resolve) => window.setTimeout(resolve, 550));
  const existing = records.get(key);
  if (existing) return { status: 200, body: { ...existing, resultado: "ja_recebida" } };
  if (scenario === "validation") return { status: 400, body: { mensagem: "Confira os dados informados.", campos: [{ campo: "descricao", mensagem: "A descrição deve ter de 1 a 2.000 caracteres." }] } };
  if (scenario === "validation-general") return { status: 400, body: { mensagem: "Confira os dados informados.", campos: [] } };
  if (scenario === "server") return { status: 500, body: { mensagem: "Não foi possível processar a avaliação. Tente novamente.", campos: [] } };
  if (scenario === "unexpected") return { status: 502, body: { mensagem: "Resposta não reconhecida." } };
  const record = { resultado: "criada", id: key, descricao: payload.descricao, nota: payload.nota };
  records.set(key, record);
  if (scenario === "network") throw new TypeError("Falha de rede simulada após persistência.");
  if (scenario === "duplicate") return { status: 200, body: { ...record, resultado: "ja_recebida" } };
  return { status: 201, body: record };
}

async function postEvaluation(key: string, payload: { descricao: string; nota: number }, scenario: Scenario): Promise<ApiResponse> {
  if (apiMode === "mock") return mockPostEvaluation(key, payload, scenario);

  const url = new URL("avaliacao", `${apiBaseUrl}/`);
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": key,
    },
    body: JSON.stringify(payload),
  });

  let body: ApiBody | undefined;
  try {
    body = await response.json() as ApiBody;
  } catch {
    // A resposta HTTP pode não conter JSON; o status ainda permite exibir uma falha recuperável.
  }
  return { status: response.status, body };
}

export default function App() {
  const [description, setDescription] = useState("");
  const [rating, setRating] = useState(5);
  const [scenario, setScenario] = useState<Scenario>("created");
  const [descriptionError, setDescriptionError] = useState("");
  const [ratingError, setRatingError] = useState("");
  const [status, setStatus] = useState<Message>(null);
  const [error, setError] = useState<Message>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isRetryLocked, setIsRetryLocked] = useState(false);
  const [focusTarget, setFocusTarget] = useState<"description" | "rating" | "submit" | null>(null);
  const descriptionRef = useRef<HTMLTextAreaElement>(null);
  const ratingRef = useRef<HTMLInputElement>(null);
  const submitRef = useRef<HTMLButtonElement>(null);
  const idempotencyKey = useRef<string | null>(null);
  const hasSubmitted = useRef(false);
  const normalizedDescription = normalizeDescription(description);
  const characterCount = countCodePoints(normalizedDescription);

  useEffect(() => {
    if (!isSubmitting && focusTarget) {
      const targets = { description: descriptionRef, rating: ratingRef, submit: submitRef };
      targets[focusTarget].current?.focus();
      setFocusTarget(null);
    }
  }, [focusTarget, isSubmitting]);

  function changeRating(next: number) {
    setRating(Math.max(0, Math.min(10, next)));
    setRatingError("");
  }

  function validateForm() {
    let valid = true;
    if (characterCount === 0) { setDescriptionError("Escreva uma descrição da sua experiência."); valid = false; }
    else if (characterCount > 2000) { setDescriptionError("Use no máximo 2.000 caracteres na descrição."); valid = false; }
    else setDescriptionError("");
    if (!Number.isInteger(rating) || rating < 0 || rating > 10) { setRatingError("A nota deve ser um número inteiro de 0 a 10."); valid = false; }
    else setRatingError("");
    if (!valid) setFocusTarget(characterCount === 0 || characterCount > 2000 ? "description" : "rating");
    return valid;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isSubmitting) return;
    setStatus(null);
    setError(null);
    if (hasSubmitted.current) { idempotencyKey.current = null; hasSubmitted.current = false; }
    if (!validateForm()) return;
    const requestKey = idempotencyKey.current ||= newUuid();
    setIsSubmitting(true);
    setStatus({ text: "Enviando avaliação.", kind: "info" });
    try {
      const response = await postEvaluation(requestKey, { descricao: normalizedDescription, nota: rating }, scenario);
      setStatus(null);
      if (response.status === 201 || (response.status === 200 && response.body?.resultado === "ja_recebida")) {
        setDescription(""); setRating(5); idempotencyKey.current = null; hasSubmitted.current = true;
        setIsRetryLocked(false);
        setDescriptionError(""); setRatingError("");
        setStatus({ text: response.status === 201 ? "Avaliação enviada com sucesso." : "Avaliação enviada com sucesso. Esta tentativa já havia sido recebida; o registro original foi mantido.", kind: "success" });
        return;
      }
      if (response.status === 400 && response.body) {
        setIsRetryLocked(false);
        const fieldErrors = response.body.campos ?? [];
        const descriptionProblem = fieldErrors.find((problem) => problem.campo === "descricao");
        const ratingProblem = fieldErrors.find((problem) => problem.campo === "nota");
        if (descriptionProblem) { setDescriptionError(descriptionProblem.mensagem || "Confira a descrição e tente novamente."); setFocusTarget("description"); }
        else if (ratingProblem) { setRatingError(ratingProblem.mensagem || "Confira a nota e tente novamente."); setFocusTarget("rating"); }
        else { setError({ text: response.body.mensagem || "Confira os dados informados e tente novamente.", kind: "error" }); setFocusTarget("submit"); }
        return;
      }
      setIsRetryLocked(true);
      setError({ text: response.status === 500 ? "Não foi possível concluir o envio. Tente novamente com os mesmos dados." : "Não foi possível confirmar o envio. Tente novamente com os mesmos dados.", kind: "error" });
      setFocusTarget("submit");
    } catch {
      setStatus(null);
      setIsRetryLocked(true);
      setError({ text: "Não foi possível confirmar o envio. Tente novamente com os mesmos dados e a mesma chave. Sua tentativa não criará uma avaliação duplicada.", kind: "error" });
      setFocusTarget("submit");
    } finally { setIsSubmitting(false); }
  }

  function resetSimulation() {
    records.clear(); setDescription(""); setRating(5); setDescriptionError(""); setRatingError("");
    setStatus(null); setError(null); idempotencyKey.current = null; hasSubmitted.current = false; setIsRetryLocked(false);
  }

  return (
    <>
      <a className="skip-link" href="#conteudo">Ir para o formulário</a>
      <main className="page" id="conteudo">
        <header className="brand" aria-label="Avaliação de aulas"><span className="brand-mark" aria-hidden="true">a</span><span>avaliação de aulas</span></header>
        <section className="intro" aria-labelledby="page-title">
          <p className="eyebrow">Sua opinião importa</p><h1 id="page-title">Como foi a aula?</h1>
          <p className="intro-copy">Compartilhe sua experiência e ajude a melhorar as próximas aulas.</p>
          <p className="privacy-note"><span className="privacy-icon" aria-hidden="true">✓</span><span><strong>Avaliação anônima.</strong> Não pedimos nome nem e-mail.</span></p>
        </section>
        <section className="form-card" aria-labelledby="form-title">
          <h2 id="form-title">Conte como foi para você</h2><p className="required-note" id="required-note">Todos os campos são obrigatórios.</p>
          <form id="feedback-form" noValidate aria-describedby="required-note" onSubmit={handleSubmit}>
            <div className="field">
              <div className="label-row"><label htmlFor="description">Descrição</label><span className="character-count" id="character-count" aria-hidden="true">{characterCount.toLocaleString("pt-BR")} / 2.000</span></div>
              <textarea ref={descriptionRef} id="description" name="descricao" rows={5} required readOnly={isSubmitting || isRetryLocked} aria-invalid={descriptionError ? "true" : undefined} aria-describedby="description-help description-error" placeholder="O que funcionou bem? O que poderia melhorar?" value={description} onChange={(event) => { setDescription(event.target.value); if (descriptionError) setDescriptionError(""); }} />
              <p className="field-help" id="description-help">De 1 a 2.000 caracteres após remover espaços das extremidades.</p>
              <p className="field-error" id="description-error" hidden={!descriptionError}>{descriptionError}</p>
            </div>
            <fieldset className="rating-fieldset" disabled={isSubmitting || isRetryLocked}>
              <legend>Nota</legend><label className="rating-label" htmlFor="rating">Qual nota você dá?</label>
              <p className="rating-help" id="rating-help">A nota começa em 5. Arraste a barra ou use as setas do teclado; 0 é a menor nota e 10 é a maior.</p>
              <div className="rating-summary"><div><output className="rating-value" id="rating-value" htmlFor="rating">Nota {rating} de 10</output><p className="rating-caption" id="rating-caption">{captions[rating]}</p></div><img className="rating-face" id="rating-face" src={`/assets/faces/carinha-nota-${rating}.svg`} alt="" width="80" height="88" aria-hidden="true" /></div>
              <div className="slider-row">
                <button className="step-button" id="decrease-rating" type="button" aria-label="Diminuir nota" onClick={() => changeRating(rating - 1)}><span aria-hidden="true">−</span></button>
                <input ref={ratingRef} id="rating" name="nota" type="range" min="0" max="10" step="1" value={rating} required aria-valuetext={`Nota ${rating} de 10`} aria-invalid={ratingError ? "true" : undefined} aria-describedby="rating-help rating-error" onChange={(event) => changeRating(Number(event.target.value))} />
                <button className="step-button" id="increase-rating" type="button" aria-label="Aumentar nota" onClick={() => changeRating(rating + 1)}><span aria-hidden="true">+</span></button>
              </div>
              <div className="scale-labels" aria-hidden="true"><span>0</span><span>5</span><span>10</span></div><p className="field-error" id="rating-error" hidden={!ratingError}>{ratingError}</p>
            </fieldset>
            <button ref={submitRef} className="submit-button" id="submit-button" type="submit" disabled={isSubmitting} aria-busy={isSubmitting}><span id="submit-label">{isSubmitting ? "Enviando…" : isRetryLocked ? "Tentar novamente" : "Enviar avaliação"}</span>{!isSubmitting && <span id="submit-icon" aria-hidden="true">↗</span>}</button>
            <p className="submit-status" id="submit-status" role="status" aria-live="polite" aria-atomic="true">{status && <span className={`feedback-message feedback-message--${status.kind}`}>{status.text}</span>}</p>
            <p className="submit-error" id="submit-error" role="alert" aria-atomic="true" tabIndex={-1}>{error && <span className={`feedback-message feedback-message--${error.kind}`}>{error.text}</span>}</p>
          </form>
        </section>
      {apiMode === "mock" && <details className="simulation-panel">
          <summary>Simulação local</summary>
          <p className="simulation-copy">Os envios são simulados localmente e não são enviados à API nem à AWS. Escolha o retorno para revisar os estados do formulário.</p>
          <label htmlFor="simulation-result">Resultado do próximo envio</label>
          <select id="simulation-result" value={scenario} disabled={isSubmitting} onChange={(event) => setScenario(event.target.value as Scenario)}>
            <option value="created">Sucesso — avaliação criada</option><option value="duplicate">Sucesso — tentativa já recebida</option>
            <option value="validation">Erro 400 — validação por campo</option><option value="validation-general">Erro 400 — mensagem geral</option>
            <option value="server">Erro 500 — falha interna</option><option value="network">Falha de rede — confirmação perdida</option><option value="unexpected">Resposta inesperada</option>
          </select>
          <button className="reset-button" id="reset-simulation" type="button" disabled={isSubmitting} onClick={resetSimulation}>Reiniciar simulação</button>
      </details>}
        <footer className="page-footer">Compartilhe sua experiência sem incluir dados pessoais.</footer>
      </main>
    </>
  );
}
