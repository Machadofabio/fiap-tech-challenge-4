# Proteção do formulário contra abuso

Status: implementação preparada em 2026-11-08; validação na AWS pendente.

## Objetivo e contexto

Reduzir envios excessivos à API pública e seus efeitos no DynamoDB, no SES e nos custos, preservando o formulário anônimo e acessível. Fontes: enunciado do Tech Challenge, planejamento em `../../../docs/planejamento/plano-plataforma-feedback.md`, `template.yaml`, `frontend/src/App.tsx` e documentação AWS de [limites de rota HTTP API](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-apigatewayv2-stage-routesettings.html).

Fatos: a rota `POST /avaliacao` usa API Gateway HTTP API, e o frontend usa uma chave de idempotência por envio lógico. CORS restringe navegadores, mas não impede chamadas diretas. O template atual não configura limites de taxa. O deploy é via SAM/GitHub Actions; a configuração de simulação local precisa acompanhar alterações que possam ser simuladas.

Fora de escopo desta proposta: exigir login, identificar estudantes, migrar a API, usar CAPTCHA ou criar infraestrutura adicional sem decisão específica.

## Opções e decisões pendentes

- **Abordagem inicial — aprovada em 2026-11-08:** limite global de taxa e rajada da rota no API Gateway, escolhido pelo usuário (opção A). O limite global é compartilhado entre visitantes, opera por melhor esforço e não identifica nem bloqueia um abusador individual. AWS WAF não se associa diretamente a uma HTTP API; sua adoção exige arquitetura compatível e avaliação de custos.
- **Valores iniciais — aprovados em 2026-11-08:** taxa global de 2 requisições por segundo e rajada de 5, escolhidos pelo usuário para a demonstração. Manter configuráveis, simular simultaneidade e revisar conforme o uso observado; os limites são compartilhados e aplicados por melhor esforço.
- **Resposta 429 — aprovada em 2026-11-08:** exibir “Há muitos envios no momento. Aguarde um pouco e tente novamente” e permitir tentativa manual. Preservar descrição, nota e chave de idempotência; não reenviar automaticamente nem prometer tempo de espera fixo.
- **Acompanhamento — aprovado em 2026-11-08:** consultar as métricas existentes `Count` e `4xx` no CloudWatch e documentar a consulta, sem criar alarme nem habilitar logs de acesso nesta etapa. A métrica `4xx` agrega erros, portanto não separa `429`; `Count` apresenta o total de requisições. A AWS informa que `429` em excesso pode não gerar métricas. Fontes: [métricas HTTP API](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-metrics.html) e [limitações de monitoramento](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-monitor.html).
- **Validação — aprovada em 2026-11-08:** validar somente na AWS, sem simular `429` localmente. Após o deploy, usar carga pequena e controlada para observar respostas `429`, comportamento do formulário e recuperação; consultar `Count` e `4xx` no CloudWatch. Registrar o resultado e respeitar os limites de acesso disponíveis à equipe.

## Épicos e histórias em rascunho

### Infraestrutura — limitar e observar o tráfego

**US INF-AB-01 — Limite de tráfego.** Como responsável pela aplicação, quero limitar requisições da rota pública para reduzir picos de tráfego.

Critérios: parâmetros de taxa e rajada explícitos no `template.yaml` único; aplicação na rota `POST /avaliacao`; resposta 429 observável sob carga controlada; deploy preserva API e integrações atuais; documentação de parâmetros, limites e procedimento de ajuste. Implementação no repositório; implantação e validação AWS pendentes.

**US INF-AB-02 — Acompanhamento.** Como responsável pela operação, quero observar requisições e erros para avaliar os limites.

Critérios: documentação no README para consultar métricas `Count` e `4xx` e suas limitações, sem criar alarme ou habilitar logs de acesso. Consulta documentada; validação na AWS pendente.

### UX/UI — informar bloqueio temporário

**US UX-AB-01 — Estado de limite.** Como estudante, quero entender quando houver excesso temporário de envios para saber como tentar novamente.

Critérios: mensagem, estado de erro acessível e orientação de tentativa especificados; campos preenchidos e seleção mantidos. Especificação registrada em `../../ui-ux/protecao-formulario.md`.

### Frontend — tratar limitação sem perder dados

**US FE-AB-01 — Tratar 429.** Como estudante, quero receber uma orientação quando o envio for limitado para poder tentar novamente sem perder minha avaliação.

Critérios: tratar 429 conforme UX aprovada; manter descrição, nota e idempotência; permitir tentativa manual; validar o comportamento na AWS. Implementação no repositório; homologação AWS pendente. Não interpretar qualquer 429 como prova de comportamento abusivo do usuário.

## Dependências e sequência

| Dependente | Origem | Tipo e desbloqueio |
| --- | --- | --- |
| INF-AB-01 | Abordagem e valores aprovados | Produto/infraestrutura: limite global de 2 requisições por segundo e rajada de 5 definidos; demais decisões de operação e homologação permanecem pendentes. |
| FE-AB-01 | UX-AB-01 | UX: aprovar texto e comportamento de tentativa antes da integração. |
| FE-AB-01 | INF-AB-01 | Contrato/ambiente: validar a resposta real 429 e o comportamento do formulário na AWS para homologação. |
| INF-AB-02 | INF-AB-01 | Operação: limites implantados e métricas disponíveis para homologação. |

Sequência: concluir a especificação UX; implementar infraestrutura e frontend conforme as decisões; atualizar Compose/documentação quando futuras mudanças exigirem simulação local; publicar e validar somente na AWS. Registrar evidências e confirmar ausência de regressão no envio normal, crítico e nas tentativas com mesma chave.
