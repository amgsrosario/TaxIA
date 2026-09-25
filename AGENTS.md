# TaxIA / KnowledgeFlow — Instruções para agentes

## Objectivo

Acelerar o desenvolvimento do TaxIA mantendo decisões de produto, qualidade,
rastreabilidade e governação do conhecimento sob controlo da AR2.

Estas instruções aplicam-se a agentes que trabalhem neste repositório. Antes
de executar uma tarefa, ler a documentação e o código relevantes para ela.

## Contexto e decisões

O código, as configurações, o Compose e as migrações descrevem o comportamento
implementado. As decisões de produto aprovadas e os ADR descrevem o
comportamento pretendido. Quando divergirem, assinalar a divergência.

Consultar `README.md` e a documentação pertinente em `docs/`. O ficheiro
`CLAUDE.md` contém contexto histórico: confirmar as suas afirmações antes de
as usar. Não tratar uma instrução antiga como decisão ainda vigente.

## Componentes conhecidos

- Backend: Java 21 e Spring Boot.
- Persistência: PostgreSQL, pgvector e Flyway.
- Frontend: React, TypeScript e Vite.
- Embeddings: serviço local em `embeddings-service/`.
- Infraestrutura de desenvolvimento: Docker Compose.

Confirmar versões, portas, perfis, comandos e scripts nos ficheiros actuais
antes de os documentar ou executar. Não assumir que o estado dos contentores
ou das bases de dados é igual ao de uma sessão anterior.

## Ambientes e dados

As bases `knowledgeflow` e `knowledgeflow_pilot` têm finalidades distintas.
Confirmar sempre a base de destino antes de executar migrações, importações
ou operações que alterem dados. Preservar os dados do piloto.

Usar bases descartáveis para testes que criem, apaguem ou substituam dados.
Não utilizar dados reais de clientes numa tarefa sem autorização específica.

## Fluxo de trabalho

É permitido ler, diagnosticar e planear em `main`. Fazer alterações de código
numa branch ou worktree própria. Antes de editar, verificar `git status` e
preservar alterações preexistentes.

Para uma alteração funcional, definir o objectivo e os critérios de aceitação,
implementar dentro do âmbito, executar as verificações relevantes e rever o
diff. Preparar uma Pull Request com o resultado, os testes e os riscos.

Usar uma issue quando ajudar a acompanhar o trabalho. Uma pequena correcção
documental pode seguir um fluxo mais simples. Não fazer merge para `main`
sem autorização.

## Autonomia e decisões humanas

Dentro de uma tarefa autorizada, os agentes podem investigar, editar código,
criar testes, corrigir falhas, actualizar documentação e preparar commits e
Pull Requests.

Reservar para decisão humana as mudanças de política fiscal ou de produto,
as decisões arquitecturais transversais, a validação e publicação formal de
conhecimento e a promoção para produção. Alterações de autenticação,
permissões ou classificação de risco exigem revisão explícita antes da
integração.

Não alterar produção, fazer merge para `main` ou destruir dados persistentes
do piloto sem autorização para a acção concreta.

## Verificação

Escolher verificações proporcionais à alteração:

- Backend: executar testes relevantes; usar `mvn test` quando a mudança
  funcional ou o seu impacto o justificar.
- Frontend: consultar os scripts em `frontend/package.json` e executar os
  aplicáveis.
- Persistência: validar migrações numa base de teste apropriada.
- Documentação: rever coerência, comandos, referências e diff.

Registar os comandos executados e os resultados. Não desactivar testes para
ocultar falhas. Não afirmar que uma verificação passou se não foi executada.

## Conhecimento e respostas do TaxIA

Preservar a distinção entre circuito assistivo e circuito formal descrita na
documentação do projecto.

A IA pode investigar, reunir fontes, preparar rascunhos e apoiar a revisão
no circuito formal. A validação e a publicação de conhecimento institucional
exigem uma acção humana explícita através das transições previstas.

Em alterações relacionadas com matéria fiscal, identificar fontes, vigência,
âmbito e limitações. Separar factos das inferências. Respeitar as políticas
existentes de grounding, risco, parecer humano e resposta-limite. Não
transformar conteúdo pendente em conteúdo publicado por iniciativa própria.

## Segurança

Não incluir segredos, tokens, chaves API ou dados pessoais de clientes em
commits, issues, Pull Requests, prompts ou relatórios. Não imprimir valores
sensíveis de variáveis de ambiente.

Antes de apagar ou substituir dados persistentes, identificar a base afectada,
o impacto e a possibilidade de recuperação. Operações em bases descartáveis
de teste podem ser executadas dentro do âmbito da tarefa.

## Documentação e conclusão

Actualizar a documentação afectada pela alteração. Registar num ADR uma
nova decisão arquitectural relevante, evitando duplicar decisões já tomadas.

Ao concluir, indicar o que mudou, os testes executados e respectivos
resultados, riscos conhecidos e qualquer decisão ainda necessária. Quando
faltar contexto, investigar primeiro; pedir uma decisão humana se a dúvida
afectar o comportamento pretendido ou impedir uma implementação segura.
