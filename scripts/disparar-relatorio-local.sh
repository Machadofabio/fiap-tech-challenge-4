#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Dispara a Lambda de relatório no serviço local do Compose.

Uso:
  scripts/disparar-relatorio-local.sh --semanal [--referencia ISO-8601] [--id ID]
  scripts/disparar-relatorio-local.sh --manual [--inicio YYYY-MM-DD] [--fim YYYY-MM-DD] [--id ID]

Para uma nova execução manual, o script cria um ID. Reutilize --id ao repetir a
mesma solicitação técnica, para preservar a idempotência.

Configuração opcional:
  REPORT_PORT=8083 (padrão) ou REPORT_URL=http://localhost:8083
EOF
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE=""
START_DATE=""
END_DATE=""
REFERENCE=""
GENERATION_ID=""

while (($#)); do
  case "$1" in
    --semanal|--weekly)
      [[ -z "$MODE" ]] || { echo "Escolha apenas um tipo de relatório." >&2; exit 2; }
      MODE="SEMANAL"
      shift
      ;;
    --manual)
      [[ -z "$MODE" ]] || { echo "Escolha apenas um tipo de relatório." >&2; exit 2; }
      MODE="MANUAL"
      shift
      ;;
    --inicio|--start)
      (($# >= 2)) || { echo "Falta o valor de $1." >&2; exit 2; }
      START_DATE="$2"
      shift 2
      ;;
    --fim|--end)
      (($# >= 2)) || { echo "Falta o valor de $1." >&2; exit 2; }
      END_DATE="$2"
      shift 2
      ;;
    --referencia|--reference)
      (($# >= 2)) || { echo "Falta o valor de $1." >&2; exit 2; }
      REFERENCE="$2"
      shift 2
      ;;
    --id)
      (($# >= 2)) || { echo "Falta o valor de --id." >&2; exit 2; }
      GENERATION_ID="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Argumento desconhecido: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$MODE" ]]; then
  echo "Informe --semanal ou --manual." >&2
  usage >&2
  exit 2
fi

if [[ "$MODE" == "SEMANAL" && ( -n "$START_DATE" || -n "$END_DATE" ) ]]; then
  echo "Use --inicio/--fim somente com --manual." >&2
  exit 2
fi
if [[ "$MODE" == "MANUAL" && -n "$REFERENCE" ]]; then
  echo "Use --referencia somente com --semanal." >&2
  exit 2
fi

EVENT="$(python3 - "$MODE" "$START_DATE" "$END_DATE" "$REFERENCE" "$GENERATION_ID" <<'PY'
import json
import re
import sys
from datetime import date, datetime, timezone

mode, start, end, reference, generation_id = sys.argv[1:]
event = {"tipo": mode}

def validate_date(value, field):
    if not value:
        return
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise SystemExit(f"{field} precisa ser uma data válida no formato YYYY-MM-DD.") from error
    if parsed.isoformat() != value:
        raise SystemExit(f"{field} precisa usar exatamente o formato YYYY-MM-DD.")

if mode == "MANUAL":
    if not start and not end:
        raise SystemExit("Informe --inicio, --fim ou ambos para o relatório manual.")
    validate_date(start, "dataInicio")
    validate_date(end, "dataFim")
    if start and end and date.fromisoformat(start) > date.fromisoformat(end):
        raise SystemExit("dataInicio não pode ser posterior a dataFim.")
    if not generation_id:
        generation_id = "local-manual-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", generation_id):
        raise SystemExit("--id aceita de 1 a 80 letras, números, ponto, hífen ou sublinhado.")
    event["geracaoId"] = generation_id
    if start:
        event["dataInicio"] = start
    if end:
        event["dataFim"] = end
elif mode == "SEMANAL":
    if reference:
        try:
            parsed = datetime.fromisoformat(reference.replace("Z", "+00:00"))
        except ValueError as error:
            raise SystemExit("--referencia precisa ser um timestamp ISO-8601 com fuso horário.") from error
        if parsed.tzinfo is None:
            raise SystemExit("--referencia precisa informar um fuso horário.")
        event["dataReferencia"] = reference
    if generation_id:
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,80}", generation_id):
            raise SystemExit("--id aceita de 1 a 80 letras, números, ponto, hífen ou sublinhado.")
        event["geracaoId"] = generation_id

print(json.dumps(event, ensure_ascii=False))
PY
)"

if [[ -z "${REPORT_PORT:-}" && -f "$ROOT/.env" ]]; then
  REPORT_PORT="$(awk -F= '$1 == "REPORT_PORT" { value=$2 } END { gsub(/[[:space:]\r]/, "", value); print value }' "$ROOT/.env")"
fi
REPORT_URL="${REPORT_URL:-http://localhost:${REPORT_PORT:-8083}}"
REPORT_URL="${REPORT_URL%/}"
printf 'POST %s/_lambda_\nEvento: %s\n' "$REPORT_URL" "$EVENT" >&2

if ! RESPONSE="$(curl --silent --show-error --connect-timeout 5 --max-time 200 \
  --header 'Content-Type: application/json' \
  --data-binary "$EVENT" \
  --write-out $'\nHTTP_STATUS:%{http_code}' \
  "$REPORT_URL/_lambda_")"; then
  echo "Não consegui acessar o serviço reporter. Inicie-o com: docker compose --profile aws up -d localstack reporter" >&2
  exit 1
fi

HTTP_STATUS="${RESPONSE##*$'\n'HTTP_STATUS:}"
BODY="${RESPONSE%$'\n'HTTP_STATUS:*}"
printf '%s\n' "$BODY"
if [[ ! "$HTTP_STATUS" =~ ^2[0-9][0-9]$ ]]; then
  echo "A invocação local respondeu HTTP $HTTP_STATUS." >&2
  exit 1
fi
