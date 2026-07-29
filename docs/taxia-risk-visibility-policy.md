# TaxIA — Política de risco e visibilidade

> **Documento de política (Bloco C).** Define a política **conceptual** de risco,
> visibilidade, revisão humana e forma de resposta da TaxIA. Nesta fase, apenas a
> **Decisão C1** está fechada; as restantes ficam explicitamente marcadas como
> **A decidir**.

## 1. Natureza do documento

- Este documento define a **política conceptual** de risco, visibilidade, revisão
  humana e forma de resposta.
- Deve **orientar futuras decisões** de backend, frontend, RAG, curadoria e
  publicação.
- Nesta fase, **apenas algumas decisões estão fechadas** (ver secção 3).
- As decisões não fechadas ficam marcadas como **"A decidir"** (secções 7 e 8) e
  **não devem ser presumidas** enquanto não forem decididas explicitamente.
- Não implementa nada — é desenho e governação, não código nem migrations.

## 2. Princípios herdados

Esta política deve respeitar, sem excepção:

- a TaxIA como produto **100% profissional** (não há "TaxIA-lite");
- a **resposta documentada** como forma base;
- a **Resposta-limite** como último patamar automático antes do Pedido de parecer;
- o **Pedido de parecer** como circuito com intervenção humana obrigatória;
- a **regra da prudência** — quando os sinais entram em conflito, prevalece o mais
  restritivo;
- a **ausência de garantia absoluta de 100%** — mesmo respostas bem suportadas
  saem qualificadas.

Ver [taxia-core-principles.md](taxia-core-principles.md),
[taxia-product-vision.md](taxia-product-vision.md) e
[taxia-boundary-answer.md](taxia-boundary-answer.md).

## 3. Decisão C1 — Visibilidade externa é profissional *(FECHADA)*

> **C1.** Na TaxIA, **visibilidade externa significa visível a utilizador
> profissional externo**. Não existe uma categoria conceptual de resposta para
> cliente leigo.

Corolários:

- **CLIENT**, se existir tecnicamente, deve ser entendido como **utilizador
  externo**;
- CLIENT **não** significa utilizador leigo;
- CLIENT **não** implica resposta simplificada;
- **DEMO não** implica TaxIA-lite;
- a demo apenas limita **condições comerciais, volume, tempo, permissões ou
  acesso**;
- a demo **mantém a qualidade profissional** da resposta.

## 4. Níveis conceptuais de visibilidade

Níveis conceptuais preferidos (nomes de implementação a fixar mais tarde):

- **EXTERNAL** — utilizador profissional externo.
  - Pode receber resposta automática **quando a política o permitir**.
  - **Não** deve receber ruído interno de curadoria nem diagnóstico.

- **DEMO** — utilizador profissional em contexto demonstrativo.
  - A resposta mantém a **mesma natureza profissional**.
  - Pode haver **limites comerciais/operacionais**, nunca conceptuais.

- **INTERNAL** — utilização interna profissional.
  - Pode expor **mais detalhe técnico**, diagnóstico moderado e notas de análise,
    conforme permissões.

- **CURATION_ONLY** — apenas curadoria/admin.
  - **Não** é resposta automática ao utilizador externo.
  - Usado para casos, fontes ou respostas que ainda **não devem sair** do ambiente
    de governação.

> Relação com os enums de desenho já existentes
> ([taxia-documented-response-dto.md](taxia-documented-response-dto.md)): estes
> níveis conceptuais consolidam o `VisibilityLevel`/`AnswerMode` actuais. A
> reconciliação técnica dos nomes é decisão de implementação futura.

## 5. Relação com o `answerType`

Os `answerType` já definidos (ver
[taxia-documented-response-dto.md](taxia-documented-response-dto.md) e
[taxia-boundary-answer.md](taxia-boundary-answer.md)):

- `CONSULTA_DOCUMENTADA`
- `CONSULTA_DOCUMENTADA_COM_LIMITACOES`
- `RESPOSTA_LIMITE`
- `PEDIDO_DE_PARECER`

Separação de responsabilidades:

- **`answerType`** define a **forma** da resposta ao utilizador;
- **`visibilityLevel`** define **onde** essa resposta pode ser apresentada;
- **`supportStatus`** explica o **suporte técnico** (sinal do *grounding*);
- **`risk_level`** ajuda a determinar **prudência, revisão e visibilidade**.

> A **matriz completa** que combina estes eixos **ainda não está decidida** — ver
> secções 7 e 8.

## 6. Relação com a Resposta-limite

- A **Resposta-limite pode ser visível externamente** a profissionais.
- **Não** é uma falha.
- **Não** é uma resposta simplificada.
- É uma **saída profissional** para quando não há segurança para conclusão.
- Pode **preparar o Pedido de parecer**, organizando enquadramento, fontes e factos
  em falta.

## 7. Decisões ainda em aberto *(A decidir)*

As decisões seguintes **não estão tomadas** e não devem ser presumidas:

- **C2 — A decidir:** Que `answerType` é permitido por `risk_level`?
- **C3 — A decidir:** Quando é exigida revisão humana?
- **C4 — A decidir:** Como agregamos risco quando vários casos são recuperados?
- **C5 — A decidir:** Quando é que uma resposta passa de
  `CONSULTA_DOCUMENTADA_COM_LIMITACOES` para `RESPOSTA_LIMITE`?
- **C6 — A decidir:** Quando é que uma Resposta-limite deve converter directamente
  em Pedido de parecer?
- **C7 — A decidir:** Que detalhes são ocultados em `EXTERNAL`/`DEMO` e mantidos em
  `INTERNAL`/`ADMIN`?
- **C8 — A decidir:** Como é que o `freshnessStatus` influencia a visibilidade?
- **C9 — A decidir:** Como é que a qualidade/quantidade de fontes influencia o
  `answerType`?

## 8. Matriz futura *(esqueleto — não preencher sem decisão explícita)*

Eixos previstos para a matriz de decisão:

| `risk_level` | `supportStatus` | `freshnessStatus` | `sourceQuality` | `reviewRequirement` | `answerType` | `visibilityLevel` |
|---|---|---|---|---|---|---|
| *(a decidir)* | *(a decidir)* | *(a decidir)* | *(a decidir)* | *(a decidir)* | *(a decidir)* | *(a decidir)* |

> **Matriz a decidir em fase posterior. Não preencher sem decisão explícita.**

## 9. Relação com documentos existentes

- [taxia-core-principles.md](taxia-core-principles.md) — princípios basilares.
- [taxia-product-vision.md](taxia-product-vision.md) — visão de produto.
- [taxia-response-model.md](taxia-response-model.md) — modelo de resposta documentada.
- [taxia-documented-response-dto.md](taxia-documented-response-dto.md) — contrato técnico e enums.
- [taxia-boundary-answer.md](taxia-boundary-answer.md) — Resposta-limite.
- [grounding-policy.md](grounding-policy.md) — política de *grounding* e suporte.
- [roadmap.md](roadmap.md) — roadmap e Bloco C.
