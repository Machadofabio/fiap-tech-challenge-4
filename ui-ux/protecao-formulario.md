# UX — limite temporário de envios

Status: rascunho. Dependente das decisões do plano `../docs/planejamento/protecao-formulario.md`.

Decisão aprovada em 2026-11-08: quando a API retornar 429, exibir “Há muitos envios no momento. Aguarde um pouco e tente novamente” e permitir tentativa manual, preservando descrição e nota. Não afirmar que o estudante causou o problema nem reenviar automaticamente. A mensagem deve ser acessível a leitores de tela e visível em telas pequenas; definir o foco durante o refinamento. Não exibir contador fixo sem informação confiável da API.
