# TaxIA — Política de risco e visibilidade

> **Documento de política (Bloco C).** Define a política **conceptual** de risco,
> visibilidade, encaminhamento para Pedido de parecer e forma de resposta da TaxIA.
> Nesta fase, as **Decisões C1, C2, C3 e C4** estão fechadas; as restantes ficam
> explicitamente marcadas como **A decidir**.

## 1. Natureza do documento

- Este documento define a **política conceptual** de risco, visibilidade,
  encaminhamento para Pedido de parecer e forma de resposta.
- Deve **orientar futuras decisões** de backend, frontend, RAG, curadoria e
  publicação.
- Nesta fase, **apenas algumas decisões estão fechadas** (C1, C2, C3 e C4).
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

## 3-B. Decisão C2 — `risk_level` condiciona prudência, mas não decide sozinho o `answerType` *(FECHADA)*

> **C2.** O `risk_level` **condiciona o grau de prudência**, mas **não decide
> sozinho** o `answerType`.

O `risk_level` é um **sinal de prudência** — **não** é uma autorização automática
nem uma proibição absoluta. O `answerType` final depende **sempre** da combinação
de:

- `risk_level`;
- `supportStatus`;
- contexto da pergunta;
- qualidade e actualidade das fontes;
- necessidade de apreciação profissional;
- regras mais restritivas aplicáveis (regra da prudência).

### Orientação por `risk_level`

**LOW**
- pode gerar `CONSULTA_DOCUMENTADA` se houver suporte, contexto e fontes
  suficientes;
- pode gerar `CONSULTA_DOCUMENTADA_COM_LIMITACOES` quando existam pequenas reservas;
- pode gerar `RESPOSTA_LIMITE` se faltar contexto/suporte;
- pode encaminhar para `PEDIDO_DE_PARECER` se a pergunta concreta o exigir.

**MEDIUM**
- pode gerar `CONSULTA_DOCUMENTADA` se houver suporte, contexto e fontes
  suficientes;
- **deve explicitar mais condições, limites e fontes** do que LOW;
- pode gerar `CONSULTA_DOCUMENTADA_COM_LIMITACOES`;
- pode gerar `RESPOSTA_LIMITE` se faltar contexto/suporte;
- pode encaminhar para `PEDIDO_DE_PARECER` quando a aplicação ao caso concreto o
  justificar.

**HIGH**
- **não** deve gerar `CONSULTA_DOCUMENTADA` conclusiva em modo automático;
- pode gerar `CONSULTA_DOCUMENTADA_COM_LIMITACOES`;
- pode gerar `RESPOSTA_LIMITE`;
- **deve encaminhar para `PEDIDO_DE_PARECER`** quando a pergunta exigir aplicação
  concreta, decisão fiscal individualizada ou apreciação profissional;
- **nunca** deve fechar automaticamente uma conclusão fiscal sensível sem limitação
  expressa.

### Regra transversal

Em **qualquer** nível de risco, a **falta de suporte, contexto, actualidade ou
qualidade de fontes** prevalece sobre o `risk_level` e pode conduzir a:

- `CONSULTA_DOCUMENTADA_COM_LIMITACOES`;
- `RESPOSTA_LIMITE`;
- `PEDIDO_DE_PARECER`.

Ou seja: o risco pode **descer** a forma da resposta, mas a insuficiência de suporte
**também** a desce — nunca a sobe.

### Exemplo conceptual

| Situação | `answerType` orientado |
|----------|------------------------|
| LOW + pergunta vaga | `RESPOSTA_LIMITE` |
| MEDIUM + boas fontes + pergunta genérica | `CONSULTA_DOCUMENTADA` |
| HIGH + boas fontes + pergunta genérica | `CONSULTA_DOCUMENTADA_COM_LIMITACOES` |
| HIGH + pergunta concreta com impacto fiscal | `RESPOSTA_LIMITE` ou `PEDIDO_DE_PARECER` |

> **Âmbito da C2.** A C2 fixa a relação `risk_level` → `answerType`. **Não** decide
> `parecerRequirement` (ver C3), a agregação de risco (ver C4),
> `freshnessStatus` (C8), `sourceQuality` (C9) nem as transições C5/C6 — ver secção 7.

## 3-C. Decisão C3 — intervenção humana apenas no Pedido de parecer *(FECHADA)*

> **C3.** Fora do circuito de **Pedido de parecer**, a TaxIA **não exige
> intervenção humana caso a caso** para apresentar uma resposta ao utilizador
> profissional.

No **circuito normal de pesquisa assistida**, a TaxIA deve **resolver
automaticamente** a situação através do `answerType`:

- `CONSULTA_DOCUMENTADA`;
- `CONSULTA_DOCUMENTADA_COM_LIMITACOES`;
- `RESPOSTA_LIMITE`;
- `PEDIDO_DE_PARECER`.

A TaxIA **não deve** ficar num estado intermédio de "aguarda revisão humana" para
responder. Quando **não pode concluir automaticamente**, deve:

- **limitar** a resposta (`CONSULTA_DOCUMENTADA_COM_LIMITACOES`);
- **emitir Resposta-limite** (`RESPOSTA_LIMITE`);
- ou **encaminhar para Pedido de parecer** (`PEDIDO_DE_PARECER`).

A **intervenção humana pertence ao serviço de Pedido de parecer** — não ao circuito
normal de pesquisa assistida. Isto **preserva a autonomia operacional** da TaxIA:
ela resolve sempre, sozinha, para uma das quatro formas de resposta.

### Curadoria interna ≠ revisão humana da resposta

A **curadoria humana da base de conhecimento** pode existir como **processo interno
de governação** (validar casos, fontes, qualidade editorial). **Não** deve ser
confundida com **revisão humana da resposta concreta** no fluxo normal: a resposta
ao utilizador nunca fica pendente de um curador. São planos distintos.

### Substituição conceptual: `reviewRequirement` → `parecerRequirement`

Para evitar a ideia de uma **fila invisível de revisão humana**, o conceito
`reviewRequirement` é substituído por **`parecerRequirement`**. Este **não**
representa uma fila de revisão interna; representa **se a resposta deve ou não
encaminhar para Pedido de parecer**.

Valores conceptuais:

- **`NONE`** — a resposta automática pode ser apresentada **sem** encaminhamento
  especial para parecer.
- **`SUGGESTED`** — a resposta automática pode ser apresentada, mas **deve sugerir**
  Pedido de parecer.
- **`REQUIRED`** — a resposta automática **não deve tentar fechar a conclusão**;
  deve **encaminhar para Pedido de parecer**, podendo apresentar Resposta-limite ou
  enquadramento preparatório.

> **Âmbito da C3.** A C3 fixa que a intervenção humana vive apenas no Pedido de
> parecer e substitui `reviewRequirement` por `parecerRequirement`. **Não** decide
> a agregação de risco (C4), os limiares exactos por combinação de sinais (C5+),
> `freshnessStatus` (C8) nem `sourceQuality` (C9).

## 3-D. Decisão C4 — agregação de risco por fundamentos usados *(FECHADA)*

> **C4.** A TaxIA agrega o risco pelo **sinal mais restritivo dos fundamentos
> relevantes efectivamente usados na resposta**. Casos recuperados pelo RAG mas
> irrelevantes, residuais ou não usados **não** contaminam o risco agregado.

### O que o risco agregado NÃO é

O risco agregado da resposta **não** é:

- a **média** dos riscos recuperados;
- o risco do **primeiro** resultado do RAG;
- o risco **máximo de tudo** o que o RAG recuperou (incluindo ruído).

### O que o risco agregado É

O risco agregado é o **risco mais restritivo** entre os **fundamentos relevantes
efectivamente usados** na resposta.

Regra curta:

```text
riskAggregated = risco máximo dos fundamentos relevantes usados
```

Um caso ou fonte **HIGH materialmente relevante** eleva a prudência da resposta,
**mesmo que não seja o primeiro resultado** recuperado. Inversamente, um caso HIGH
que seja apenas **ruído** (recuperado mas não usado) **não** eleva o risco.

### O que conta como "fundamento usado"

Um caso, fonte ou fragmento considera-se **usado** quando:

- é **citado** na resposta;
- **sustenta uma regra** apresentada;
- **sustenta uma excepção** relevante;
- **justifica uma limitação**;
- **justifica uma Resposta-limite**;
- **justifica encaminhamento** para Pedido de parecer.

Casos recuperados mas **irrelevantes, residuais, descartados ou não usados** ficam
fora do cálculo — não sobem nem descem o `riskAggregated`.

### Exemplos conceptuais

**Exemplo 1 — HIGH era ruído**
- Recuperados: LOW + MEDIUM + HIGH.
- Usados: LOW + MEDIUM (o HIGH era ruído, não foi usado).
- `riskAggregated = MEDIUM`.

**Exemplo 2 — HIGH sustenta uma excepção relevante**
- Recuperados: LOW + MEDIUM + HIGH.
- O HIGH **sustenta uma excepção** materialmente relevante à resposta.
- `riskAggregated = HIGH`.

**Exemplo 3 — HIGH sustenta cautela na aplicação concreta**
- MEDIUM sustenta a **regra geral**; HIGH sustenta a **necessidade de cautela** na
  aplicação concreta.
- `riskAggregated = HIGH`.
- `answerType` provável: `CONSULTA_DOCUMENTADA_COM_LIMITACOES` ou `RESPOSTA_LIMITE`.
- `parecerRequirement` provável: `SUGGESTED` ou `REQUIRED` (sem decidir C5/C6 em
  definitivo — ver secção 7).

> **Âmbito da C4.** A C4 fixa **como se agrega** o risco (máximo dos fundamentos
> relevantes usados). **Não** decide os limiares de transição entre formas de
> resposta (C5/C6), nem `freshnessStatus` (C8), nem `sourceQuality` (C9), nem
> qualquer **algoritmo de ranking, threshold ou scoring técnico** — a definição do
> que é "relevante"/"usado" em termos de implementação fica para fase posterior.

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
- **`risk_level`** ajuda a determinar **prudência, encaminhamento e visibilidade** —
  e é o **`riskAggregated`** (risco máximo dos fundamentos relevantes usados), não o
  risco de todo o conjunto recuperado (ver Decisão C4).

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

| `risk_level` | `supportStatus` | `freshnessStatus` | `sourceQuality` | `parecerRequirement` | `answerType` | `visibilityLevel` |
|---|---|---|---|---|---|---|
| *(a decidir)* | *(a decidir)* | *(a decidir)* | *(a decidir)* | *(a decidir)* | *(a decidir)* | *(a decidir)* |

> **Matriz a decidir em fase posterior. Não preencher sem decisão explícita.**
> (A coluna `reviewRequirement` foi renomeada para `parecerRequirement` — ver C3.)

### Orientação C2 por `risk_level` *(parcial — só o eixo `risk_level` → `answerType`)*

A C2 preenche **apenas** a relação `risk_level` → `answerType` (com suporte/contexto
suficientes). `parecerRequirement`, `visibilityLevel`, agregação de risco,
`freshnessStatus` e `sourceQuality` **continuam a decidir**.

| `risk_level` | `answerType` possíveis (com suporte suficiente) | `answerType` por insuficiência |
|---|---|---|
| **LOW** | `CONSULTA_DOCUMENTADA` (ou com limitações se houver reservas) | `RESPOSTA_LIMITE` / `PEDIDO_DE_PARECER` |
| **MEDIUM** | `CONSULTA_DOCUMENTADA` (com mais condições/limites/fontes) ou com limitações | `RESPOSTA_LIMITE` / `PEDIDO_DE_PARECER` |
| **HIGH** | `CONSULTA_DOCUMENTADA_COM_LIMITACOES` ou `RESPOSTA_LIMITE` (**nunca** conclusiva automática) | `RESPOSTA_LIMITE` / `PEDIDO_DE_PARECER` |

### Orientação C3 sobre `parecerRequirement` *(conceptual — sem limiares)*

A C3 fixa o **significado** de `parecerRequirement` (encaminhamento para Pedido de
parecer, **não** revisão humana invisível) e os seus valores `NONE` / `SUGGESTED` /
`REQUIRED`. **Não** decide os limiares exactos por combinação de sinais — isso
depende de C5+.

### Orientação C4 sobre a coluna `risk_level` da matriz *(agregação — sem thresholds)*

A C4 fixa que o `risk_level` que entra na matriz é o **`riskAggregated`** — o risco
**máximo dos fundamentos relevantes usados** na resposta, **não** o risco de todo o
conjunto recuperado pelo RAG. A C4 **não** preenche C5/C6, **não** decide
`freshnessStatus` (C8) nem `sourceQuality` (C9), e **não** define qualquer
threshold, scoring ou algoritmo de ranking técnico.

> Colunas `visibilityLevel` (além da C1): **a decidir** (C7). Regra de agregação de
> risco: **fixada pela C4** (máximo dos fundamentos relevantes usados). Limiares de
> `parecerRequirement` por sinal: **a decidir** (C5+).

## 9. Relação com documentos existentes

- [taxia-core-principles.md](taxia-core-principles.md) — princípios basilares.
- [taxia-product-vision.md](taxia-product-vision.md) — visão de produto.
- [taxia-response-model.md](taxia-response-model.md) — modelo de resposta documentada.
- [taxia-documented-response-dto.md](taxia-documented-response-dto.md) — contrato técnico e enums.
- [taxia-boundary-answer.md](taxia-boundary-answer.md) — Resposta-limite.
- [grounding-policy.md](grounding-policy.md) — política de *grounding* e suporte.
- [roadmap.md](roadmap.md) — roadmap e Bloco C.
