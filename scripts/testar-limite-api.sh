#!/usr/bin/env bash
set -euo pipefail

API_URL="${1:-https://j0gbe5dgtg.execute-api.sa-east-1.amazonaws.com/avaliacao}"
REQUESTS="${2:-20}"
MAX_REQUESTS=50

if ! [[ "$REQUESTS" =~ ^[1-9][0-9]*$ ]]; then
  echo "Uso: $0 [URL_DA_API] [QUANTIDADE_DE_REQUISICOES]" >&2
  exit 2
fi
if (( REQUESTS > MAX_REQUESTS )); then
  echo "A quantidade máxima permitida é $MAX_REQUESTS requisições por execução." >&2
  exit 2
fi

for dependency in curl python3; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    echo "Dependência não encontrada: $dependency" >&2
    exit 2
  fi
done

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
PIDS=()

echo "Enviando $REQUESTS requisições em paralelo para $API_URL"
echo "As avaliações usam nota 8 para não disparar alertas críticos."

for ((index = 1; index <= REQUESTS; index++)); do
  REQUEST_KEY="$(python3 -c 'import uuid; print(uuid.uuid4())')"
  (
    STATUS="$(curl --silent --show-error \
      --connect-timeout 10 \
      --max-time 30 \
      --output /dev/null \
      --write-out '%{http_code}' \
      --request POST "$API_URL" \
      --header 'Content-Type: application/json' \
      --header "Idempotency-Key: $REQUEST_KEY" \
      --data '{"descricao":"Teste controlado de limite da API","nota":8}' \
      2>"$TEMP_DIR/$index.error" || true)"
    if [[ -z "$STATUS" ]]; then STATUS=000; fi
    printf '%s\n' "$STATUS" > "$TEMP_DIR/$index.status"
  ) &
  PIDS+=("$!")
done

for PID in "${PIDS[@]}"; do
  wait "$PID" || true
done

printf '\nRespostas HTTP:\n'
for ((index = 1; index <= REQUESTS; index++)); do
  STATUS="$(cat "$TEMP_DIR/$index.status" 2>/dev/null || printf '000')"
  printf '  Requisição %02d: %s\n' "$index" "$STATUS"
  if [[ -s "$TEMP_DIR/$index.error" ]]; then
    sed 's/^/    /' "$TEMP_DIR/$index.error"
  fi
done

awk '
  $1 == 429 { throttled++ }
  $1 ~ /^2[0-9][0-9]$/ { success++ }
  $1 == "000" { transport++ }
  END {
    printf "\nResumo: %d sucesso(s), %d resposta(s) 429, %d falha(s) de transporte.\n", success, throttled, transport
    if (throttled == 0) {
      print "Nenhuma resposta 429 observada. O throttling do API Gateway é de melhor esforço; confira o RouteSettings aplicado ao estágio $default."
    }
  }
' "$TEMP_DIR"/*.status
