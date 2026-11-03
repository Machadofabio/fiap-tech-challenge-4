# BE-01 — Contrato de avaliações

Contrato OpenAPI: [`be-01-avaliacoes.openapi.yaml`](be-01-avaliacoes.openapi.yaml).

## Envio

`POST /avaliacao` recebe JSON UTF-8 no formato:

```json
{
  "descricao": "A explicação foi clara e os exemplos ajudaram.",
  "nota": 8
}
```

O envio é anônimo. `descricao` e `nota` são obrigatórios. `nota` deve ser um número inteiro entre 0 e 10. Para criar uma avaliação, o cliente envia `Idempotency-Key` com um UUID. O cliente cria uma chave para cada novo envio lógico e reutiliza a mesma chave em todas as tentativas até obter uma resposta de sucesso.

## Normalização e classificação

- Remover das extremidades de `descricao` os pontos de código Unicode White_Space: U+0009–U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F e U+3000. Não alterar espaços internos nem normalizar o conteúdo Unicode (por exemplo, NFC). Não incluir U+FEFF, que não pertence a essa propriedade.
- Validar e contar caracteres após remover as extremidades. O comprimento válido é de 1 a 2.000 pontos de código Unicode, inclusive. Contar pontos de código, não bytes UTF-8 nem unidades UTF-16.
- Persistir a descrição normalizada.
- Classificar notas de 0 a 4 como `CRITICA` e de 5 a 10 como `NORMAL`.
- Gerar `dataEnvio` no backend no momento da primeira persistência, como instante UTC RFC 3339. Repetições retornam o instante original.

## Idempotência

O valor de `Idempotency-Key` também é o `id` persistido da avaliação. A gravação deve ser condicional/atômica para que duas tentativas concorrentes com a mesma chave não criem dois registros nem dois eventos de inserção.

- Chave ainda não registrada: validar o corpo atual e, se válido, persistir a avaliação. Retornar `201` com `resultado: "criada"`.
- Chave já registrada: retornar `200` com `resultado: "ja_recebida"` e os dados originais persistidos, mesmo se o corpo da nova tentativa tiver sido alterado. Não atualizar a avaliação nem emitir outro evento de inserção. A resposta confirma que a solicitação identificada pela chave já foi recebida; não confirma que alterações posteriores foram aplicadas.
- O cliente só cria outra chave após confirmar sucesso e iniciar uma nova avaliação. Em falha ou resultado desconhecido, preservar a chave para a nova tentativa.
- O identificador acompanha o registro da avaliação; sua disponibilidade para deduplicação tem a mesma retenção do registro. Política de retenção operacional ainda precisa ser definida.

## Respostas

### `201 Created` — avaliação criada

```json
{
  "resultado": "criada",
  "id": "2f1e8e77-967d-4eeb-b7b7-5ab3be62bb39",
  "descricao": "A explicação foi clara e os exemplos ajudaram.",
  "nota": 8,
  "urgencia": "NORMAL",
  "dataEnvio": "2026-10-22T14:30:00Z"
}
```

### `200 OK` — chave já recebida

```json
{
  "resultado": "ja_recebida",
  "id": "2f1e8e77-967d-4eeb-b7b7-5ab3be62bb39",
  "descricao": "A explicação foi clara e os exemplos ajudaram.",
  "nota": 8,
  "urgencia": "NORMAL",
  "dataEnvio": "2026-10-22T14:30:00Z"
}
```

### `400 Bad Request` — entrada inválida

JSON inválido:

```json
{
  "codigo": "JSON_INVALIDO",
  "mensagem": "O corpo da solicitação deve ser um JSON válido.",
  "campos": []
}
```

Falha de validação:

```json
{
  "codigo": "DADOS_INVALIDOS",
  "mensagem": "Confira os dados informados.",
  "campos": [
    {
      "campo": "descricao",
      "codigo": "COMPRIMENTO_INVALIDO",
      "mensagem": "A descrição deve ter de 1 a 2.000 caracteres."
    }
  ]
}
```

Chave ausente ou inválida:

```json
{
  "codigo": "CHAVE_INVALIDA",
  "mensagem": "Envie Idempotency-Key com um UUID válido.",
  "campos": [
    {
      "campo": "Idempotency-Key",
      "codigo": "FORMATO_INVALIDO",
      "mensagem": "Envie um UUID válido."
    }
  ]
}
```

### `500 Internal Server Error` — falha interna

```json
{
  "codigo": "ERRO_INTERNO",
  "mensagem": "Não foi possível processar a avaliação. Tente novamente.",
  "campos": []
}
```

O backend não deve devolver mensagens de exceção, detalhes de infraestrutura ou credenciais. Em falha de rede, o cliente não presume sucesso nem falha definitiva: preserva a chave e pode repetir a chamada.
