# C9 — Expansão do corpus piloto · WAVE 1

> **Estado: gate editorial humano (2026-10-14).** Nada foi publicado, verificado ou alterado na
> `knowledgeflow_pilot`. Inventário e testes feitos só em leitura (transacções `READ ONLY` e
> simulação local de embeddings). Decisões fiscais e de publicação pertencem ao António.

## 1. Baseline (confirmada em 2026-10-14)

Flyway 18 · 23 Q&A · 6 publicadas · 6 embeddings (768 dimensões, 0 órfãos) · 33 fontes ·
7 exclusões · auditoria 116 · 5 fontes verificadas, 5 com versão consolidada, 0 matéria estável ·
tabelas de ingestão AT-FAQ (`at_faq_*`) vazias. O corpus disponível para a WAVE 1 é, portanto,
apenas o das 23 Q&A.

## 2. Inventário das 23 Q&A

`rhv` = `requiresHumanValidation`. `parecerRequirement` e `visibilityLevel` não são persistidos
por Q&A: são calculados por resposta (M4B, ADR-007; C7). Fontes marcadas com `*` estão verificadas
(ADR-008, CURRENT até 2027-01-12); as restantes estão UNCERTAIN.

| externalKey | Imposto/área | Estado | Pub. | Risco | rhv | Fontes | Emb. | Exclusões |
|---|---|---|---|---|---|---|---|---|
| CIRC-CONSERVACAO-10A-001 (G1) | IRC | VALIDATED | sim | LOW | sim | 2 LEGISLATION* | 1 | — (revisto) |
| CIVA-FATURA-PRAZO-EMISSAO-001 (G2) | IVA | VALIDATED | sim | MEDIUM | sim | 2 LEGISLATION* | 1 | — (revisto) |
| CIVA-CONSERVACAO-10A-001_v2 | IVA | VALIDATED | sim | LOW | não | 1 LEGISLATION* | 1 | — (revisto) |
| AT-FAQ-2721 | AIMI | VALIDATED | sim | MEDIUM | sim | LEGISLATION, OFFICIAL_FAQ | 1 | HERANCA_INDIVISA, RECLAMACAO, PAGAMENTO, CALCULO |
| AT-FAQ-5795 | IRS cat. H | VALIDATED | sim | MEDIUM | sim | 2 LEGISLATION, OFFICIAL_FAQ | 1 | RETENCAO_OBRIGATORIA |
| AT-FAQ-5930 | IRS cat. F | VALIDATED | sim | MEDIUM | sim | LEGISLATION, OFFICIAL_FAQ | 1 | INQUILINO, HABITACAO_PROPRIA |
| AT-FAQ-0959 | IVA dedução | VALIDATED | não | HIGH | sim | 3 LEGISLATION, OFFICIAL_FAQ | 0 | — (não revisto) |
| AT-FAQ-4624 | IVA inversão | VALIDATED | não | HIGH | sim | LEGISLATION, OFFICIAL_FAQ | 0 | — (não revisto) |
| CIVA-CONSERVACAO-10A-001 | IVA | VALIDATED | não | LOW | não | 1 LEGISLATION | 0 | — (versão substituída pela _v2) |
| AT-FAQ-0969 | IVA isenções | IMPORTED | não | HIGH | sim | INTERNAL_OPINION | 0 | — |
| AT-FAQ-1256 | IVA localização | IMPORTED | não | HIGH | sim | INTERNAL_OPINION | 0 | — |
| INT-IVA-INTRACOM-AMBIGUO-001 | IVA | IMPORTED | não | CRITICAL | sim | INTERNAL_OPINION | 0 | — |
| INT-PROC-RECLAMACAO-GRACIOSA-001 | Procedimento | IMPORTED | não | HIGH | sim | INTERNAL_OPINION | 0 | — |
| OCC-IRC-TA-2022-04 | IRC | IMPORTED | não | HIGH | sim | INTERNAL_OPINION | 0 | — (validTo 2021-12-31) |
| OCC-PT27478-2023-03 | IRC | IMPORTED | não | CRITICAL | sim | INTERNAL_OPINION | 0 | — |
| OCC-IRS-BF-2026-06 | IRS | IMPORTED | não | HIGH | sim | INTERNAL_OPINION | 0 | — |
| CNC-FAQ-24-2012-03-07 | Contabilidade | IMPORTED | não | MEDIUM | sim | INTERNAL_OPINION | 0 | — |
| OCC-MEP-2021-08-04 | Contabilidade | IMPORTED | não | CRITICAL | sim | INTERNAL_OPINION | 0 | — |
| OCC-PT20085-2017-12-01 | Contabilidade | IMPORTED | não | HIGH | sim | INTERNAL_OPINION | 0 | — |
| DR-LEX-FORMACAO-CONTINUA | Trabalho | IMPORTED | não | MEDIUM | sim | INTERNAL_OPINION | 0 | — |
| OCC-FERIAS-ADMISSAO-2023-07 | Trabalho | IMPORTED | não | HIGH | sim | INTERNAL_OPINION | 0 | — |
| ISS-GP2041-2023-PRIMEIRO-EMPREGO | Segurança Social | IMPORTED | não | MEDIUM | sim | INTERNAL_OPINION | 0 | — |
| OCC-CULTURA-2022-10-07-RETENCAO | Segurança Social | IMPORTED | não | HIGH | sim | INTERNAL_OPINION | 0 | — |

As 14 IMPORTED não têm resposta curta nem técnica (só pergunta e notas). A sua única fonte é
INTERNAL_OPINION, sem URL ("SOURCE_URL_PENDING").

## 3. AT-FAQ publicadas ainda UNCERTAIN — dossier para reconfirmação futura

Não marcar CURRENT sem verificação humana estruturada nova (ADR-008). As notas existentes **não**
são verificação. Pré-verificação automática (2026-10-14): todos os URL respondem 200 e cada página
de FAQ contém o número da FAQ.

| Q&A | Fontes (tipo · referência · URL) | Notas antigas que não contam como verificação |
|---|---|---|
| AT-FAQ-2721 | OFFICIAL_FAQ · CIMI art. 135.º-D · `…/questoes_frequentes/pages/faqs-00624.aspx`; LEGISLATION · CIMI art. 135.º-D · `…/codigos_tributarios/cimi/Pages/cimi135d.aspx` | "Confirmado prazo normal… Fonte oficial AT a associar/confirmar" |
| AT-FAQ-5795 | OFFICIAL_FAQ · CIRS arts. 98.º, n.º 6, e 99.º-D, n.º 3 · `…/faqs-00074.aspx`; LEGISLATION · CIRS art. 98.º · `…/cirs_rep/Pages/irs98.aspx`; LEGISLATION · CIRS art. 99.º-D · `…/cirs_rep/Pages/irs99d.aspx` | "Curadoria M5 (2026-10-01): fontes confirmadas no Portal das Finanças" |
| AT-FAQ-5930 | LEGISLATION · CIRS art. 41.º · `…/cirs_rep/Pages/irs41.aspx`; OFFICIAL_FAQ · CIRS art. 41.º · `…/faqs-00358.aspx` | "não publicar enquanto a fonte/URL oficial não estiver estruturada" (desactualizada: as fontes já estão estruturadas) |

Pontos a confirmar na reconfirmação humana:
- AT-FAQ-2721: o URL da legislação usa o caminho `cimi/` e não um caminho de código republicado.
  Confirmar se é a versão em vigor do art. 135.º-D (ver o caso `civa/` vs `civa_rep/` em §5).
- As FAQ têm janela CURRENT de 60 dias e a legislação de 90. Para legislação, confirmar também a
  versão consolidada.

## 4. Classificação das 17 não publicadas

| Q&A | Classe | Motivo |
|---|---|---|
| AT-FAQ-0959 | A — READY_CANDIDATE, com ajuste de fonte | VALIDATED; conteúdo coerente com a FAQ oficial 0959; 4 fontes oficiais. O URL do CIVA art. 19.º aponta para a versão **não republicada** do código (§5). |
| AT-FAQ-4624 | D — NEEDS_CONTENT_REVIEW | VALIDATED, mas a resposta diverge no enquadramento da FAQ oficial 4624 (§5). Há também risco de âmbito com o caso "adquirente residente". |
| CIVA-CONSERVACAO-10A-001 | E — DUPLICATE_OR_REDUNDANT | Versão histórica substituída pela `_v2` (ADR-005). Nunca republicar. |
| AT-FAQ-0969 | B — NEEDS_SOURCE_WORK (+D) | Sem resposta; fonte INTERNAL_OPINION sem URL; matéria de nicho (isenção, embarcações). |
| AT-FAQ-1256 | B — NEEDS_SOURCE_WORK (+D) | Sem resposta; fonte sem URL; localização/isenção/devedor por separar (nota). |
| INT-PROC-RECLAMACAO-GRACIOSA-001 | B — NEEDS_SOURCE_WORK (+D) | Tema útil e frequente, mas sem resposta. A nota pede correcção de terminologia. Candidato forte para uma vaga com redacção nova (LGT/CPPT). |
| OCC-IRS-BF-2026-06 | D — NEEDS_CONTENT_REVIEW | Sem resposta; regime recente e volátil; a nota pede instruções posteriores da AT. |
| OCC-PT27478-2023-03 | D — NEEDS_CONTENT_REVIEW / F | CRITICAL; a nota indica referências contraditórias na fonte original. |
| INT-IVA-INTRACOM-AMBIGUO-001 | F — HOLD | Caso deliberadamente incompleto (teste de clarificação/resposta-limite), CRITICAL. Não é conhecimento publicável. |
| OCC-IRC-TA-2022-04 | F — HOLD | Caso histórico limitado a 2021 (`validTo` 2021-12-31): inelegível para publicação. |
| CNC-FAQ-24-2012-03-07 | F — HOLD | Contabilidade, referencial histórico (IAS 11 substituída pela IFRS 15). Fora do núcleo fiscal. |
| OCC-MEP-2021-08-04 | F — HOLD | Contabilidade, CRITICAL, referencial por indicar. |
| OCC-PT20085-2017-12-01 | F — HOLD | Contabilidade (activos biológicos), fora do núcleo fiscal. |
| DR-LEX-FORMACAO-CONTINUA | F — HOLD | Direito do trabalho, fora do âmbito fiscal. |
| OCC-FERIAS-ADMISSAO-2023-07 | F — HOLD | Direito do trabalho; a nota indica fonte incompatível com o art. 239.º do CT. |
| ISS-GP2041-2023-PRIMEIRO-EMPREGO | F — HOLD | Segurança social; incentivo volátil; fora do âmbito fiscal. |
| OCC-CULTURA-2022-10-07-RETENCAO | F — HOLD | Segurança social; a nota indica fonte parcialmente desactualizada. |

Nenhuma das 17 está em C — NEEDS_SCOPE_WORK isoladamente. Os riscos de âmbito de 0959 e 4624
estão em §5.

## 5. Candidatos existentes — análise

### AT-FAQ-0959 — acto isolado: dedução do IVA

- FAQ oficial (`faqs-00929.aspx`, entrada 0959) lida em 2026-10-14. A conclusão da Q&A coincide com
  a da FAQ: dedução do imposto de aquisições inequivocamente relacionadas com o acto isolado, com
  factura em forma legal, em nome e na posse do sujeito passivo, e respeitando as exclusões do
  art. 21.º.
- A resposta técnica acrescenta uma cautela sem fonte na FAQ ("procedimento declarativo…, crédito
  de IVA"). Está formulada como condição a confirmar e não como afirmação.
- **Problema de fonte (material):** o CIVA art. 19.º está registado com
  `…/codigos_tributarios/civa/Pages/iva19.aspx`. Essa página mostra a redacção anterior à
  republicação ("deduzirão", notas da Lei n.º 21/2007). A versão em vigor está em
  `…/civa_rep/Pages/iva19.aspx` (redacção do DL n.º 85/2022). Os arts. 20.º e 21.º já usam
  `civa_rep`.
  - Corrigir o URL é uma alteração EXPANSIVA (fonte editada, ADR-005): a Q&A volta a
    PENDING_REVIEW e exige nova validação humana.
- Retrieval simulado: pergunta canónica 0.9069 (≥ 0.88). Paráfrase "Fiz uma operação isolada…
  posso recuperar o IVA…?" 0.8608 (abaixo do limiar, logo resposta-limite).
- **Risco de âmbito:** "Que IVA posso deduzir na minha actividade?" recupera 0959 com 0.8827 (≥ 0.88).
  - Uma pergunta genérica de dedução seria servida com a resposta do acto isolado.
  - Nenhum marcador do vocabulário ADR-004 cobre "actividade regular vs acto isolado".
  - Mitigação existente: risco HIGH → no máximo "com limitações" + SUGGESTED (M4B).
- Risco HIGH, `rhv=true`, `reviewedBy` presente: elegível depois de revalidada. Responderá sempre
  "com limitações" + SUGGESTED. As suas 4 fontes ficam UNCERTAIN até verificação C8.

### AT-FAQ-4624 — inversão do sujeito passivo entre não residentes

- FAQ oficial (`faqs-00924.aspx`, entrada 4624) lida em 2026-10-14. Responde **"Não"**: aplica-se
  o mecanismo regra, o fornecedor liquida o imposto e, não estando registado, deve registar-se.
- A Q&A diz que a inversão "não opera automaticamente" e que "é necessário confirmar se o
  adquirente é sujeito passivo…". Diz ainda que "pode caber ao fornecedor… eventual registo".
- **Divergência de enquadramento:** a fonte é categórica e a Q&A é condicional. Alinhar ou não a
  conclusão com a FAQ é decisão fiscal humana. Qualquer alteração é MATERIAL (volta a
  PENDING_REVIEW).
- Retrieval simulado: pergunta canónica 0.9054. Paráfrase "empresa estrangeira… a outra empresa
  estrangeira… quem liquida?" 0.8498 (abaixo do limiar).
- **Risco de âmbito:** "Uma empresa portuguesa compra serviços a um não residente. Há autoliquidação?"
  recupera 4624 com 0.8645, abaixo de 0.88 por uma margem de 0.0155.
  - É precisamente o caso em que a conclusão se inverte (adquirente residente).
  - Nenhum marcador ADR-004 o cobre.

### Redundância com o corpus publicado (simulação, 2026-10-14)

Simulação local feita sem escrita: passagens dos candidatos embebidas como na publicação
(`Tema/Pergunta/Resposta validada`, e5-base, 768 dimensões) e comparadas com os 6 embeddings
publicados.

- Nenhuma pergunta do corpus existente muda de top-1: G1 0.9065, G2 0.8861, DEMO1 0.9384,
  5930 0.9186, 2721 0.9206, 5795 0.8915.
- O valor mais alto de um candidato numa pergunta existente é 0.8677 (4624 em DEMO1), abaixo do
  limiar e longe do top-1.
- Residuais conhecidos mantêm-se:
  - "Tributação conjunta: prazo?" → 2721 0.8855;
  - "Que despesas posso deduzir aos rendimentos?" → 5930 0.8936;
  - subarrendamento → 5930 0.8826;
  - divórcio/AIMI → 2721 0.8931;
  - "para o adicional" → 5930 0.8713 (abaixo do limiar).
  Os candidatos não os resolvem nem os agravam.

## 6. Conclusão: o corpus existente não sustenta 6–10 candidatos

Só 2 das 17 Q&A não publicadas têm conteúdo e fontes oficiais. Ambas são IVA HIGH e ambas precisam
de uma decisão humana antes de publicar. A WAVE 1 a partir do corpus existente tem, no máximo,
**1 candidato publicável após ajuste de fonte (0959)** e **1 dependente de revisão de conteúdo
(4624)**. Para atingir 6–10 casos com diversidade (IVA, IRC, IRS, obrigações declarativas,
facturação, procedimento) é preciso **conteúdo novo** redigido a partir de fontes oficiais, pelo
mesmo processo do pack G1+G2: rascunho do agente, revisão e validação humana, publicação humana.
A escolha dos temas é decisão humana.

## 7. Processo repetível por vaga (WAVE n)

1. Baseline só de leitura: contagens, md5 dos embeddings, verificações C8, órfãos.
2. Inventário e classificação A–F, com a pergunta, a resposta, as fontes, o estado e as notas.
3. Leitura das fontes oficiais: o URL responde, a entrada existe, a conclusão coincide e o caminho
   do código é a versão republicada/consolidada.
4. Simulação de retrieval só de leitura: canónica, paráfrase, próxima fora de âmbito, pergunta de
   outra Q&A, contraponto por imposto/contexto e regressão das publicadas e dos residuais.
5. Gate editorial humano único, com decisão por caso: PUBLICAR / PUBLICAR COM AJUSTE / NÃO
   PUBLICAR AINDA.
6. Pós-aprovação, só pelos caminhos governados:
   - ajustes aprovados → revalidação humana;
   - revisão de âmbito/exclusões;
   - backup;
   - publicação humana;
   - embedding (768, sem órfãos);
   - auditoria.
7. Gate C8: verificação humana das fontes no backoffice. Nunca CURRENT automático.
8. E2E real por script com a sessão do António: casos novos e regressão do corpus e dos
   residuais.

## 8. Decisões do gate editorial (António, 2026-10-14)

- **D1 — AT-FAQ-0959:** corrigir a fonte do CIVA art. 19.º para `civa_rep` e revalidar; **não publicar**.
- **D2 — âmbito de 0959:** rejeitado aceitar o enquadramento errado mitigado por HIGH. Antes de publicar
  é preciso uma distinção governada entre acto isolado e actividade regular.
- **D3 — AT-FAQ-4624:** alinhar a conclusão com a FAQ oficial (não se aplica a al. g); mecanismo regra);
  alteração material, com revalidação; **não publicar**.
- **D4 — C9-A:** extensão governada do âmbito (ADR-004), com nota de decisão antes de qualquer
  alteração material ao ADR.
- **D5 — WAVE 1-B:** redacção governada de B1 (IRS, prazo da declaração anual), B2 (IRC, Modelo 22),
  B4 (IVA, declaração periódica), B6 (CPPT, reclamação graciosa) e B7 (IRS cat. B, faturas/recibos).
  B3 e B5 adiados.
- **D6:** reconfirmação C8 das AT-FAQ publicadas incluída nesta missão.
- **D8:** nenhuma publicação nova até C9-A, revalidações, gate de conteúdo e gate C8.

## 9. C9-A — estado (2026-10-14): cenário de opções

Exclusões novas, a extensão natural do ADR-004, não chegam. Medido em simulação só de leitura:

- "Em que condições posso deduzir o IVA suportado?" → 0959 com 0.8853, sem sinal de actividade
  regular;
- "Quando se aplica a inversão do sujeito passivo com fornecedores não residentes?" → 4624 com 0.8858,
  sem sinal sobre o adquirente.

Uma exclusão só actua quando a pergunta contém o sinal contrário. Para resolver é preciso **contexto
obrigatório**, que o ADR-004 §1 recusou ("não há appliesTo positivo").

Opções:

- A: só exclusões;
- B: contexto obrigatório;
- C: A + B;
- D: solução editorial sem código;
- E: clarificação.

Recomendação: B. Aguarda decisão humana; nenhum código foi alterado.

## 10. Achados das fontes das AT-FAQ publicadas (gate C8)

- **AT-FAQ-5930:** o CIRS art. 41.º, n.º 6, nega qualquer dedução na sublocação, e a Q&A (que não o
  trata) serve o residual "subarrendamento" (0.8826). Recomenda-se não verificar as fontes antes de
  decidir uma nova versão ou uma exclusão. O n.º 8 (Lei n.º 56/2023) também não é tratado.
- **AT-FAQ-2721:** a afirmação "com uma única liquidação" e a referência a "regularização posterior"
  não constam da FAQ nem do art. 135.º-D lidos; a fonte diz que, sem declaração no prazo, o AIMI incide
  sobre cada cônjuge. Fica para uma versão futura.
- **AT-FAQ-5795:** coerente com a FAQ e as normas.

## 11. Decisões do segundo gate e estado (2026-10-14)

- **C9-A aprovada** (contexto obrigatório). Implementada na branch `feat/c9-a-required-context`:
  ADR-009, V19, marcadores `SUBLOCACAO`, `ACTO_ISOLADO`, `FORNECEDOR_NAO_RESIDENTE` e
  `ADQUIRENTE_NAO_RESIDENTE`. Merge humano.
- Uso editorial depois do merge:
  - 0959 → REQUIRES `ACTO_ISOLADO`;
  - 4624 → REQUIRES `FORNECEDOR_NAO_RESIDENTE` + `ADQUIRENTE_NAO_RESIDENTE`;
  - 5930 → EXCLUDES `SUBLOCACAO`.
- **WAVE 1-B** com conteúdo aprovado:
  - B1, B4 e B7 como propostos;
  - B2 com MEDIUM e `requiresHumanValidation=true`;
  - B6 reformulado para a regra geral (CPPT arts. 70.º e 102.º; canónica 0.8860).

  B3 e B5 adiados. Criação no piloto só com autorização de escrita; publicação só depois dos gates.
- **AT-FAQ:**
  - 5795 autorizada para o gate C8;
  - 2721 e 5930 só depois de novas versões (ADR-005), já propostas: 2721 sem afirmações sem fonte;
    5930 com os n.os 6 a 9 do CIRS art. 41.º.
  - A exclusão `HABITACAO_PROPRIA` da 5930 colide com o n.º 8; fica para decisão humana.
- Limitações registadas para o futuro:
  - recall de termos coloquiais ("Modelo 22", "recibos verdes"): aliases ou query expansion governada;
  - perguntas ambíguas IRS/IRC: clarificação;
  - divórcio/AIMI ainda sem marcador.

## 12. C9-A merged e activada no piloto (2026-10-15)

- O PR #24 foi merged (`9e6f06c`). A V19 foi validada numa base descartável e aplicada ao piloto
  pelo Flyway normal. As 7 exclusões foram preservadas como `EXCLUDES`.
- O António correu `c9a-activation.sh`, com escritas governadas pela sua sessão:
  - `EXCLUDES SUBLOCACAO` na AT-FAQ-5930 publicada;
  - `AT-FAQ-2721_v2` e `AT-FAQ-5930_v2` criadas em `PENDING_REVIEW`;
  - HABITACAO_PROPRIA retirada da 5930_v2.

  Foram 8 eventos de auditoria, todos esperados (138 → 146).
- Regressão do gate: a única diferença é "Sou subarrendatário…" → 5930 `APPLICABILITY_EXCLUDED`.
- Estado e próximos gates: [taxia-project-state.md](taxia-project-state.md).

## 13. C8 da AT-FAQ-5795 (2026-10-15)

- As 3 fontes (FAQ 5795; CIRS art. 98.º, n.º 6; art. 99.º-D, n.º 3) foram reconfirmadas pelo António e
  registadas no backoffice. Nos dois artigos foi confirmada a versão consolidada. Sem matéria
  estável.
- CURRENT: FAQ até 2026-12-14 e artigos até 2027-01-13. A actualidade agregada da 5795 é CURRENT até
  2026-12-14.
- Auditoria +3 (146 → 149). Nenhuma outra fonte, Q&A ou embedding foi alterado.
