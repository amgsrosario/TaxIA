# ADR-011 — `main` como referência tecnológica única e piloto M0 sem casos VALIDATED

- Estado: Accepted (ordem executiva de 24/11/2026, que invoca a deliberação da Administração da mesma data)
- Âmbito: base tecnológica e pesquisa RAG

## Contexto

A linha experimental de medição divergiu da `main`. O piloto convencional usa Q&A publicadas. Os casos `VALIDATED`
(`knowledge_cases`) entravam automaticamente na pesquisa RAG e não há caminho na aplicação para os retirar.

## Decisão

1. A `main` é a referência única, a partir de `f5730ee`. As linhas experimentais ficam preservadas, sem desenvolvimento
   e sem fusão integral. Qualquer recuperação é selectiva e tem decisão própria.
2. **M0:** no piloto, os casos `VALIDATED` não fundamentam respostas. `ValidatedCasesPolicy` decide no arranque:
   - perfil `pilot`: `knowledgeflow.rag.validated-cases-enabled: false`, sem variável de ambiente;
   - base `knowledgeflow_pilot`: excluídos **sempre**, seja qual for o perfil ou a propriedade (o launcher usa `dev`);
   - base não identificável: excluídos.
3. A exclusão é feita na consulta, por isso os casos não ocupam lugares do `topK`. Nenhum caso é apagado ou muda de estado.

## Consequências

- Voltar a incluir casos no piloto exige alterar o código, com decisão e revisão.
- O portal (`/api/v1/portal/cases`) continua a mostrar ao cliente os seus casos: não é fundamentação e não foi alterado.
- `/api/v1/interactions/{id}/ask` e o equivalente do portal chamam o modelo **sem RAG nem grounding**. Não usam casos,
  mas não dão as garantias do `/ask`; não foram alterados e não devem ser usados no piloto.
- Fora do piloto, o comportamento mantém-se (omissão `true` em `application.yml`).
