# TaxIA — Política de risco e visibilidade

> **Documento de política (Bloco C).** Define a política **conceptual** de risco,
> visibilidade, actualidade, suporte documental, encaminhamento para Pedido de parecer
> e forma de resposta da TaxIA. As **Decisões C1 a C9** estão **fechadas**; a **C9
> fecha o Bloco C** quanto aos princípios conceptuais de risco, visibilidade,
> actualidade e suporte documental. A materialização técnica (thresholds, scoring,
> ranking, enums definitivos) fica para fase posterior.

## 1. Natureza do documento

- Este documento define a **política conceptual** de risco, visibilidade,
  encaminhamento para Pedido de parecer e forma de resposta.
- Deve **orientar futuras decisões** de backend, frontend, RAG, curadoria e
  publicação.
- As **Decisões C1 a C9** estão **fechadas** — o Bloco C está conceptualmente
  encerrado quanto aos seus princípios.
- Não há decisões conceptuais em aberto neste bloco; a materialização técnica
  (thresholds, scoring, ranking, enums definitivos) fica para fase posterior.
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

## 3-E. Decisão C5 — fronteira entre Consulta documentada com limitações e Resposta-limite *(FECHADA)*

> **C5.** A TaxIA distingue claramente `CONSULTA_DOCUMENTADA_COM_LIMITACOES` de
> `RESPOSTA_LIMITE`: a primeira **ainda aponta uma orientação prudente**
> (condicionada); a segunda **já não aponta conclusão aplicável**, apresentando
> apenas o enquadramento geral suportado.

A **Resposta-limite não é uma falha nem um "não sei"**: é uma **saída profissional
documentada** para quando falta segurança para concluir. "Não concluir" pode ser a
resposta correcta.

### `CONSULTA_DOCUMENTADA_COM_LIMITACOES`

- existe **orientação/conclusão prudente possível**;
- há **suporte suficiente** para uma resposta **condicionada**;
- há **fontes relevantes**;
- há **limites, condições, pressupostos ou excepções**;
- a resposta **deve explicitar** esses limites e **não** ser apresentada como
  garantia absoluta.

### `RESPOSTA_LIMITE`

- **não** existe segurança para apontar **conclusão aplicável**;
- existe **apenas enquadramento geral suportado**;
- **faltam factos/documentos essenciais**;
- ou as **fontes não respondem directamente** à pergunta;
- ou a aplicação ao caso exigiria **extrapolação** para além das fontes;
- deve **indicar os elementos em falta** e **preparar eventual Pedido de parecer**.

### Regra curta

> Se ainda há **orientação prudente**, `CONSULTA_DOCUMENTADA_COM_LIMITACOES`.
> Se só há **enquadramento sem conclusão aplicável**, `RESPOSTA_LIMITE`.

### Gatilhos principais para Resposta-limite

- **factos essenciais em falta**;
- **fontes que não respondem directamente** à pergunta;
- **fontes contraditórias ou insuficientes**;
- **pergunta concreta com dados incompletos**;
- **`aggregatedRiskLevel` HIGH** dependente de **pressupostos não confirmados**;
- **actualidade duvidosa**;
- **necessidade de extrapolar** para além das fontes.

### Exemplos conceptuais

**Exemplo 1 — genérica com condições relevantes**
- Pergunta genérica, fontes suficientes, mas com condições relevantes.
- `answerType = CONSULTA_DOCUMENTADA_COM_LIMITACOES`.

**Exemplo 2 — concreta com informação essencial em falta**
- Pergunta concreta, falta informação essencial.
- `answerType = RESPOSTA_LIMITE`.

**Exemplo 3 — HIGH dependente de factos não fornecidos**
- Tema HIGH, existem fontes gerais, mas a conclusão depende de factos não
  fornecidos.
- `answerType = RESPOSTA_LIMITE`.

**Exemplo 4 — regra geral existe, situação específica não coberta**
- As fontes sustentam a regra geral, mas não a situação específica perguntada.
- `answerType = RESPOSTA_LIMITE` **ou** `CONSULTA_DOCUMENTADA_COM_LIMITACOES`,
  conforme **ainda seja possível apontar orientação prudente**.

> **Âmbito da C5.** A C5 fixa a **fronteira** entre `CONSULTA_DOCUMENTADA_COM_LIMITACOES`
> e `RESPOSTA_LIMITE`. **Não** decide o grau de recomendação de parecer (C6),
> `freshnessStatus` (C8), `sourceQuality` (C9), nem quaisquer thresholds, scoring ou
> algoritmo de ranking técnico.

## 3-F. Decisão C6 — `parecerRequirement` e Pedido de parecer *(FECHADA)*

> **C6.** O **Pedido de parecer** é uma **funcionalidade estrutural e economicamente
> relevante** da TaxIA. O `parecerRequirement` **não** mede se o parecer "tem valor"
> — o parecer pode ter valor em qualquer situação. Indica **apenas o grau de
> recomendação/encaminhamento** para Pedido de parecer **naquela resposta concreta**.

A decisão final de avançar para Pedido de parecer **cabe ao utilizador
profissional**, **salvo** quando a TaxIA classifica a resposta como **`REQUIRED`** —
nesse caso a TaxIA **não** deve apresentar conclusão fiscal automática final, mas
encaminhar.

### Valores de `parecerRequirement`

**`NONE`**
- a resposta automática é **suficiente** para a finalidade normal da consulta;
- **sem** necessidade especial de sugerir parecer;
- o utilizador profissional pode **sempre** pedir parecer se quiser — a TaxIA não o
  empurra.

**`SUGGESTED`**
- a resposta automática é **útil** e pode orientar;
- a situação concreta **pode beneficiar** de Pedido de parecer;
- a TaxIA **deve sugerir** o Pedido de parecer como **opção prudente**;
- a decisão de avançar **cabe ao utilizador profissional**.

**`REQUIRED`**
- a TaxIA **não** deve tentar fechar conclusão automática;
- deve **encaminhar claramente** para Pedido de parecer;
- pode apresentar **enquadramento, fontes, limites e factos em falta**;
- **não** deve apresentar conclusão fiscal final.

### Relação com `answerType` *(orientadora, não equivalência rígida)*

- `CONSULTA_DOCUMENTADA` tende a `parecerRequirement = NONE`;
- `CONSULTA_DOCUMENTADA_COM_LIMITACOES` pode ter `NONE` ou `SUGGESTED`;
- `RESPOSTA_LIMITE` tende a `SUGGESTED` ou `REQUIRED`;
- `PEDIDO_DE_PARECER` corresponde a `REQUIRED`.

`answerType` e `parecerRequirement` são **eixos relacionados, não sinónimos** — esta
relação é **orientadora**, não uma equivalência rígida absoluta.

### Exemplos conceptuais

**Exemplo 1 — genérica com fontes fortes**
- `answerType = CONSULTA_DOCUMENTADA`; `parecerRequirement = NONE`.

**Exemplo 2 — útil mas dependente de elementos concretos complementares**
- `answerType = CONSULTA_DOCUMENTADA_COM_LIMITACOES`; `parecerRequirement = SUGGESTED`.

**Exemplo 3 — concreta sem elementos suficientes para concluir**
- `answerType = RESPOSTA_LIMITE`; `parecerRequirement = SUGGESTED` **ou** `REQUIRED`,
  conforme gravidade e necessidade de apreciação profissional.

**Exemplo 4 — exige decisão fiscal individualizada**
- `answerType = PEDIDO_DE_PARECER` (ou `RESPOSTA_LIMITE` preparatória);
  `parecerRequirement = REQUIRED`.

> **Âmbito da C6.** A C6 fixa o **significado e a graduação** de `parecerRequirement`
> (`NONE`/`SUGGESTED`/`REQUIRED`) e a sua relação orientadora com `answerType`.
> **Não** decide os detalhes visíveis por nível de visibilidade (C7),
> `freshnessStatus` (C8), `sourceQuality` (C9), nem quaisquer thresholds, scoring ou
> algoritmo de ranking técnico.

## 3-G. Decisão C7 — detalhe visível por nível *(FECHADA)*

> **C7.** A TaxIA **não reduz a qualidade da resposta** em `EXTERNAL` nem em `DEMO`.
> A diferença entre níveis de visibilidade **não** é a **profundidade profissional**
> da resposta, mas o **grau de exposição dos bastidores** (diagnóstico, curadoria e
> governação).

Corolários fundamentais:

- `EXTERNAL` e `DEMO` **não** têm resposta simplificada nem menor qualidade — a
  resposta é sempre profissional, documentada, fundamentada e estruturada.
- `EXTERNAL` significa **utilizador profissional externo** (conforme C1), **não**
  utilizador leigo.
- `DEMO` é uma **demonstração profissional** — **não** é "TaxIA-lite".
- A diferença real entre níveis é **quanto dos bastidores** (scores, ranking, chunks,
  notas de curadoria, diagnóstico, IDs técnicos) fica visível.

### `EXTERNAL` — produto profissional limpo

Apresenta uma **resposta profissional limpa**, com:

- resposta;
- enquadramento técnico;
- fundamentos legais;
- fontes relevantes;
- limitações;
- pressupostos;
- factos/documentos em falta;
- `answerType`;
- `parecerRequirement`;
- indicação de **risco/prudência em linguagem legível**;
- aviso de **ausência de garantia absoluta**;
- **sem ruído técnico interno**.

### `DEMO` — mesma qualidade profissional, limites comerciais

- tem a **mesma qualidade e lógica profissional** de `EXTERNAL`;
- **pode** limitar volume, tempo, histórico, exportações, permissões, acesso ou a
  **criação efectiva de Pedido de parecer**;
- **não pode** limitar a **qualidade conceptual**, as **fontes**, a **fundamentação**,
  a **Resposta-limite** nem o `parecerRequirement`.

### `INTERNAL` — produto profissional + detalhe técnico

Mostra **tudo o que `EXTERNAL` mostra**, mais:

- maior detalhe técnico;
- avaliação de suporte;
- fontes completas;
- notas de análise;
- comparação entre fundamentos;
- sinais de risco;
- recomendações operacionais;
- diagnóstico moderado.

### `CURATION_ONLY` — bastidores e governação

Pode mostrar:

- chunks recuperados;
- scores;
- ranking;
- estados de curadoria;
- notas internas;
- erros de fonte;
- qualidade de ingestão;
- versões;
- auditoria;
- elegibilidade RAG;
- motivos de exclusão;
- dados técnicos internos que **nunca** devem aparecer ao utilizador externo.

### Proibição expressa

Informação interna de curadoria, diagnóstico, prompts internos, scores, chunks,
logs, ranking bruto ou IDs técnicos **não** deve aparecer em `EXTERNAL`/`DEMO` —
**salvo** quando **transformada** numa **explicação profissional legível** (por
exemplo, um score cru nunca é exposto, mas a prudência que dele resulta pode ser
comunicada em linguagem profissional).

### Regra curta

> `EXTERNAL` e `DEMO` mostram **produto profissional limpo**.
> `INTERNAL` e `CURATION_ONLY` mostram **bastidores, diagnóstico e governação**.

> **Âmbito da C7.** A C7 fixa **que detalhe é visível por nível de visibilidade** e a
> fronteira entre produto profissional limpo (`EXTERNAL`/`DEMO`) e bastidores
> (`INTERNAL`/`CURATION_ONLY`). **Não** decide `freshnessStatus` (C8),
> `sourceQuality` (C9), nem quaisquer thresholds, scoring ou algoritmo de ranking
> técnico. Mantém inalteradas C1–C6.

## 3-H. Decisão C8 — actualidade e origem temporal da resposta *(FECHADA)*

> **C8.** A actualidade das fontes **não** deve produzir automaticamente **ausência
> de resposta**. A actualidade **gradua** a **força** da resposta, a sua **origem
> temporal**, o **grau de limitação**, o **tipo de aviso** e o eventual
> **encaminhamento para Pedido de parecer**.

Princípios fundamentais:

- A actualidade/*freshness* **não é uma guilhotina de resposta** — não transforma
  fonte antiga em bloqueio automático.
- Uma **fonte antiga pode continuar útil**: matéria estável não deixa de ser
  aplicável só por a fonte ter data recuada.
- O problema **não é a idade isolada** da fonte, mas a **confiança na sua validade
  actual**.
- Conhecimento desactualizado pode ser útil como **histórico, contraste ou alerta** —
  **não** como fundamento de **conclusão fiscal actual**.
- `OUTDATED` **não** implica **silêncio automático**.

### Estados de `freshnessStatus`

**`CURRENT`**
- fonte actual ou validade confirmada;
- pode sustentar **resposta principal**;
- pode permitir `CONSULTA_DOCUMENTADA`, se os restantes sinais também forem
  suficientes.

**`STABLE_BUT_OLD`**
- fonte antiga, **mas sem indício de alteração** e aplicável a **matéria estável**;
- pode sustentar **resposta principal**;
- **deve incluir nota de actualidade/estabilidade**;
- pode permitir `CONSULTA_DOCUMENTADA` ou `CONSULTA_DOCUMENTADA_COM_LIMITACOES`,
  conforme os restantes sinais.

**`UNCERTAIN`**
- **não** é possível confirmar se a fonte ainda reflecte o regime actual;
- pode sustentar **enquadramento limitado**;
- **não** deve sustentar **conclusão actual segura sem aviso forte**;
- tende a `CONSULTA_DOCUMENTADA_COM_LIMITACOES`, `RESPOSTA_LIMITE` ou
  `parecerRequirement = SUGGESTED`, conforme o contexto.

**`OUTDATED`**
- fonte **revogada, caducada, substituída ou materialmente desactualizada**;
- **não** deve sustentar **conclusão fiscal actual**;
- pode sustentar **enquadramento histórico, contraste ou alerta de cautela**;
- tende a `RESPOSTA_LIMITE`, enquadramento histórico ou `parecerRequirement =
  SUGGESTED`/`REQUIRED`, conforme a gravidade;
- **não** implica silêncio automático.

### Regra curta

> A actualidade **não decide apenas se** a TaxIA responde.
> Decide **com que força, origem temporal e limitações** a TaxIA responde.

### Frase-modelo (origem temporal incerta/histórica)

> Com base na fonte disponível, o enquadramento histórico era este.
>
> No entanto, a fonte não permite confirmar a aplicação actual do regime.
>
> Para uma conclusão actual, é necessário confirmar a legislação vigente ou submeter
> Pedido de parecer.

### Exemplos conceptuais

**Exemplo 1 — `CURRENT` + restantes sinais suficientes**
- `answerType` provável = `CONSULTA_DOCUMENTADA`.

**Exemplo 2 — `STABLE_BUT_OLD` + matéria estável**
- `answerType` provável = `CONSULTA_DOCUMENTADA` ou
  `CONSULTA_DOCUMENTADA_COM_LIMITACOES`, **com nota de actualidade**.

**Exemplo 3 — `UNCERTAIN`**
- `answerType` provável = `CONSULTA_DOCUMENTADA_COM_LIMITACOES` ou `RESPOSTA_LIMITE`,
  **sem conclusão actual segura** (aviso forte).

**Exemplo 4 — `OUTDATED`**
- `answerType` provável = `RESPOSTA_LIMITE` ou **enquadramento histórico**;
- **não** sustenta conclusão fiscal actual; pode servir de histórico/contraste/alerta.

> **Âmbito da C8.** A C8 fixa como a **actualidade/origem temporal** gradua a resposta
> (força, origem temporal, limites, avisos e encaminhamento) e os quatro estados de
> `freshnessStatus`. **Não** decide `sourceQuality`, quantidade mínima de fontes nem a
> hierarquia definitiva de fontes (C9), nem quaisquer thresholds, scoring ou algoritmo
> de ranking técnico. Mantém inalteradas C1–C7.

## 3-I. Decisão C9 — qualidade, quantidade e diversidade material das fontes *(FECHADA — fecha o Bloco C)*

> **C9.** A TaxIA **não** mede robustez documental pela **contagem bruta de fontes**.
> Mede o suporte documental por **força, autoridade, aplicabilidade directa,
> actualidade, coerência, ligação ao fundamento legal, diversidade material e
> capacidade de sustentar a conclusão proposta**.

Princípios fundamentais:

- **Qualidade > quantidade.**
- Uma fonte **forte, oficial e directamente aplicável** pode valer mais do que várias
  fontes fracas.
- A **contagem bruta de fontes é insuficiente** e pode ser **enganadora** — não é
  irrelevante, mas fica **subordinada** à qualidade e à diversidade material.
- Fontes que **repetem o mesmo núcleo informativo** **não** contam como confirmações
  independentes.
- A robustez **aumenta quando há diversidade material real**.
- Fontes **externas não oficiais** podem ajudar a **contextualizar**, mas **não** devem
  sustentar **sozinhas** uma conclusão fiscal actual.
- **Divergência relevante** entre fontes **degrada** a resposta para
  `CONSULTA_DOCUMENTADA_COM_LIMITACOES`, `RESPOSTA_LIMITE` ou Pedido de parecer.
- Várias fontes fracas **não substituem** uma fonte forte quando a matéria **exige
  suporte oficial/legal**.

### Regra curta

> A TaxIA mede suporte documental por **força, aplicabilidade e diversidade
> material**, **não** por **volume aparente** de fontes.

### Conceitos

**Fonte principal**
- **sustenta directamente** a resposta;
- deve ser **autoridade forte**, **aplicável** e **actual** ou suficientemente
  **estável**.

**Fonte complementar**
- acrescenta **fundamento materialmente diferente**;
- pode acrescentar **detalhe, excepção, interpretação, contexto ou confirmação
  independente**.

**Fonte derivada/replicada**
- **reproduz, resume ou reformula** o mesmo **núcleo informativo**;
- **não** conta como **confirmação independente**;
- pode apenas reforçar, de modo **limitado**, que o entendimento **circula** ou foi
  replicado.

**Núcleo material comum**
- conteúdo **substancialmente igual**, ainda que com **linguagem diferente**;
- várias fontes com o **mesmo núcleo** **não** equivalem a várias confirmações
  independentes.

### Hierarquia orientadora de fontes

Orientadora — **não** rígida nem mecânica:

1. **Legislação em vigor.**
2. **Doutrina administrativa oficial** — instruções, informações vinculativas, ofícios
   circulados ou orientações oficiais da autoridade competente.
3. **FAQ oficial** da AT ou de entidade pública competente.
4. **Jurisprudência relevante.**
5. **Informação oficial complementar.**
6. **Conteúdo interno curado** pela TaxIA.
7. **Fontes externas não oficiais.**

> **Nota obrigatória sobre a hierarquia.** A hierarquia **orienta** a avaliação, mas
> **não substitui** a análise da **aplicabilidade directa, actualidade, coerência e
> diversidade material**. Uma **FAQ oficial directamente aplicável** pode ser mais útil
> para uma pergunta prática do que um **artigo legal genérico isolado**. **Legislação
> isolada** pode bastar para questões **literais**, mas pode exigir **limitações** se a
> resposta depender de **interpretação ou aplicação concreta**.

### Exemplos conceptuais

**Exemplo 1 — fonte oficial + fundamento legal**
- FAQ oficial da AT **+** artigo legal correspondente = **suporte forte e diverso**.

**Exemplo 2 — eco documental**
- FAQ oficial da AT **+** vários artigos privados que a copiam ou resumem = **uma
  fonte forte + eco documental**, **não** várias confirmações independentes.

**Exemplo 3 — legislação isolada**
- pode **bastar** para questão **literal**;
- pode **exigir limitações** se a aplicação prática depender de **interpretação**.

**Exemplo 4 — só fontes externas não oficiais**
- podem **contextualizar**;
- **não** devem sustentar **sozinhas** uma conclusão fiscal actual.

**Exemplo 5 — fontes divergentes**
- degradam para `CONSULTA_DOCUMENTADA_COM_LIMITACOES`, `RESPOSTA_LIMITE` ou
  `parecerRequirement = SUGGESTED`/`REQUIRED`, conforme o contexto.

> **Âmbito da C9.** A C9 fixa **como se avalia a robustez documental** (qualidade,
> autoridade, aplicabilidade, coerência e diversidade material — não volume), os
> papéis de fonte (principal/complementar/derivada) e uma **hierarquia orientadora**.
> **Não** define thresholds numéricos, scoring nem algoritmo de ranking; **não**
> transforma a hierarquia numa regra mecânica; **não** substitui C2, C5, C6, C7 ou C8.
> A C9 **fecha o Bloco C** quanto aos princípios conceptuais.

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

## 7. Decisões ainda em aberto

> **Não existem decisões em aberto neste bloco. O Bloco C fica fechado quanto aos
> princípios conceptuais documentados.** A materialização técnica (thresholds,
> scoring, ranking, enums definitivos e limiares exactos por sinal) fica para fase
> posterior.

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

### Orientação C5 sobre a transição para Resposta-limite *(fronteira — sem thresholds)*

A C5 fixa a **fronteira qualitativa** entre `CONSULTA_DOCUMENTADA_COM_LIMITACOES` e
`RESPOSTA_LIMITE`: se ainda há **orientação prudente**, a primeira; se só há
**enquadramento sem conclusão aplicável**, a segunda (ver secção 3-E para gatilhos e
exemplos). A C5 **não** decide o grau de recomendação de parecer (C6),
`freshnessStatus` (C8), `sourceQuality` (C9), nem quaisquer thresholds, scoring ou
algoritmo de ranking.

### Orientação C6 sobre a coluna `parecerRequirement` da matriz *(graduação — sem thresholds)*

A C6 fixa o **significado e a graduação** da coluna `parecerRequirement`
(`NONE`/`SUGGESTED`/`REQUIRED`) — grau de recomendação/encaminhamento para Pedido de
parecer, **não** o valor abstracto do parecer — e a sua **relação orientadora** com
`answerType` (ver secção 3-F). A C6 **não** define os **limiares exactos por
combinação de sinais** (que continuam a depender de trabalho técnico posterior), nem
`freshnessStatus` (C8), `sourceQuality` (C9) ou detalhes de visibilidade (C7).

### Orientação C7 sobre a coluna `visibilityLevel` da matriz *(detalhe por nível — sem thresholds)*

A C7 fixa **que detalhe é visível por nível de visibilidade**: `EXTERNAL` e `DEMO`
recebem **produto profissional limpo** (resposta, enquadramento, fundamentos, fontes,
limitações, pressupostos, factos em falta, `answerType`, `parecerRequirement`, risco
em linguagem legível, aviso de ausência de garantia — **sem** ruído técnico interno);
`INTERNAL` e `CURATION_ONLY` recebem, **por cima**, os **bastidores** (detalhe
técnico, avaliação de suporte, notas de análise, e — só em `CURATION_ONLY` — chunks,
scores, ranking, estados de curadoria, auditoria, elegibilidade RAG, motivos de
exclusão). `DEMO` pode ter limites **comerciais/operacionais**, nunca de qualidade
conceptual (ver secção 3-G). A C7 **não** decide `freshnessStatus` (C8),
`sourceQuality` (C9) nem quaisquer thresholds, scoring ou algoritmo de ranking técnico.

### Orientação C8 sobre a coluna `freshnessStatus` da matriz *(graduação temporal — sem thresholds)*

A C8 fixa que o `freshnessStatus` **não** é uma coluna de "responde/não responde",
mas um eixo que **gradua** força, origem temporal, limites, avisos e encaminhamento:
`CURRENT` pode sustentar resposta principal (`CONSULTA_DOCUMENTADA` se os restantes
sinais bastarem); `STABLE_BUT_OLD` pode sustentar resposta principal **com nota de
actualidade** (`CONSULTA_DOCUMENTADA` ou `CONSULTA_DOCUMENTADA_COM_LIMITACOES`);
`UNCERTAIN` tende a `CONSULTA_DOCUMENTADA_COM_LIMITACOES`/`RESPOSTA_LIMITE` (sem
conclusão actual segura sem aviso forte) e pode elevar `parecerRequirement` a
`SUGGESTED`; `OUTDATED` **não** sustenta conclusão fiscal actual, mas pode servir de
**enquadramento histórico, contraste ou alerta**, tendendo a
`RESPOSTA_LIMITE`/histórico e a `parecerRequirement` `SUGGESTED`/`REQUIRED` — **nunca**
silêncio automático (ver secção 3-H). A C8 **não** decide `sourceQuality`, quantidade
mínima de fontes, hierarquia definitiva de fontes (C9), nem quaisquer thresholds,
scoring ou algoritmo de ranking técnico.

### Orientação C9 sobre a coluna `sourceQuality` da matriz *(qualidade e diversidade — sem thresholds)*

A C9 fixa que a coluna `sourceQuality` **não** é uma contagem de fontes, mas uma
avaliação de **força, autoridade, aplicabilidade directa, actualidade, coerência,
ligação ao fundamento legal e diversidade material**. Orientações que entram na
matriz: uma fonte **forte, oficial e directamente aplicável** pode sustentar
`CONSULTA_DOCUMENTADA` se os restantes sinais bastarem; **várias fontes fracas** ou
que apenas **replicam o mesmo núcleo** (eco documental) **não** elevam o suporte e
tendem a `CONSULTA_DOCUMENTADA_COM_LIMITACOES`/`RESPOSTA_LIMITE`; **fontes externas
não oficiais** isoladas **não** sustentam conclusão fiscal actual; **divergência
relevante** entre fontes **degrada** o `answerType` e pode elevar `parecerRequirement`
a `SUGGESTED`/`REQUIRED`. Os papéis de fonte (**principal/complementar/derivada-
replicada**) e a **hierarquia orientadora** (ver secção 3-I) informam esta avaliação,
mas **não** são regra mecânica. A C9 **não** define thresholds numéricos, scoring nem
algoritmo de ranking.

> Coluna `visibilityLevel`: nível externo = profissional **fixado pela C1**; detalhe
> visível por nível **fixado pela C7**. Regra de agregação de risco: **fixada pela
> C4** (máximo dos fundamentos relevantes usados). Fronteira
> `CONSULTA_DOCUMENTADA_COM_LIMITACOES` ↔ `RESPOSTA_LIMITE`: **fixada pela C5**
> (qualitativa). Significado/graduação de `parecerRequirement`: **fixado pela C6**.
> Graduação temporal por `freshnessStatus` (`CURRENT`/`STABLE_BUT_OLD`/`UNCERTAIN`/
> `OUTDATED`): **fixada pela C8**. Avaliação de `sourceQuality` por força,
> aplicabilidade e diversidade material — não por volume — e a hierarquia orientadora
> de fontes: **fixadas pela C9**. Os **limiares exactos por sinal** e o scoring/ranking
> técnico: **a decidir** (fase técnica posterior). **O Bloco C fica conceptualmente
> fechado (C1–C9).**

## 9. Relação com documentos existentes

- [taxia-core-principles.md](taxia-core-principles.md) — princípios basilares.
- [taxia-product-vision.md](taxia-product-vision.md) — visão de produto.
- [taxia-response-model.md](taxia-response-model.md) — modelo de resposta documentada.
- [taxia-documented-response-dto.md](taxia-documented-response-dto.md) — contrato técnico e enums.
- [taxia-boundary-answer.md](taxia-boundary-answer.md) — Resposta-limite.
- [grounding-policy.md](grounding-policy.md) — política de *grounding* e suporte.
- [roadmap.md](roadmap.md) — roadmap e Bloco C.
