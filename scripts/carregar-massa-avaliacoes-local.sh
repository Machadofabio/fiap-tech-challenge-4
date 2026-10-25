#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  cat <<'EOF'
Carrega avaliações determinísticas na tabela local `avaliacoes`.
Gera 4 avaliações por dia da semana anterior e 2 registros fora dos limites.

Uso:
  scripts/carregar-massa-avaliacoes-local.sh

Requer o serviço `localstack` ativo no perfil `aws` do Docker Compose.
Opcional: AVALIACOES_TABLE=nome-da-tabela
EOF
  exit 0
fi
if (($#)); then
  echo "Este script não aceita argumentos. Use --help para ver os detalhes." >&2
  exit 2
fi

TABLE="${AVALIACOES_TABLE:-avaliacoes}"

docker compose --profile aws exec -T localstack bash -s -- "$TABLE" <<'BASH'
set -euo pipefail
TABLE="$1"

if ! awslocal dynamodb describe-table --table-name "$TABLE" >/dev/null 2>&1; then
  echo "A tabela '$TABLE' não existe. Inicie o LocalStack com: docker compose --profile aws up -d localstack" >&2
  exit 1
fi

python3 - "$TABLE" <<'PY'
import json
import subprocess
import sys
import time
import uuid
from collections import Counter
from datetime import datetime, time as day_time, timedelta, timezone
from zoneinfo import ZoneInfo

table = sys.argv[1]
zone = ZoneInfo("America/Sao_Paulo")
now = datetime.now(zone)
monday_this_week = now.date() - timedelta(days=now.weekday())
week_start = monday_this_week - timedelta(days=7)
week_end = monday_this_week - timedelta(days=1)

score_patterns = [
    [0, 4, 7, 10],
    [1, 3, 6, 8],
    [2, 4, 5, 9],
    [0, 5, 5, 10],
    [3, 4, 8, 9],
    [1, 6, 7, 10],
    [2, 3, 7, 8],
]
times = [day_time(9, 15), day_time(11, 40), day_time(15, 5), day_time(19, 20)]
descriptions = [
    "A explicação foi clara, e os exemplos práticos ajudaram.",
    'Gostei do passo a passo "sem pular etapas"; quero mais exercícios.',
    "Conteúdo bom.\nSugestão: reservar mais tempo para dúvidas.",
    "Rever o ritmo, especialmente no tópico de APIs e integração.",
]

def item_for(local_datetime, score, description, stable_name):
    item_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "fiap-feedback-local/" + stable_name))
    sent_at = local_datetime.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    urgency = "CRITICA" if score <= 4 else "NORMAL"
    return {
        "PutRequest": {
            "Item": {
                "id": {"S": item_id},
                "descricao": {"S": description},
                "nota": {"N": str(score)},
                "urgencia": {"S": urgency},
                "dataEnvio": {"S": sent_at},
            }
        }
    }

requests = []
urgency_counts = Counter()
for day_offset, scores in enumerate(score_patterns):
    current_day = week_start + timedelta(days=day_offset)
    for slot, score in enumerate(scores):
        urgency = "CRITICA" if score <= 4 else "NORMAL"
        urgency_counts[urgency] += 1
        local_datetime = datetime.combine(current_day, times[slot], zone)
        requests.append(item_for(
            local_datetime,
            score,
            descriptions[(day_offset + slot) % len(descriptions)],
            f"{current_day.isoformat()}-slot-{slot + 1}",
        ))

# Dois registros de borda confirmam início inclusivo e fim exclusivo no relatório semanal.
before_start = datetime.combine(week_start, day_time.min, zone) - timedelta(seconds=1)
after_end = datetime.combine(monday_this_week, day_time.min, zone)
requests.append(item_for(before_start, 0, "Registro de borda anterior à semana do relatório.", "fora-antes-" + week_start.isoformat()))
requests.append(item_for(after_end, 10, "Registro de borda no início da semana seguinte.", "fora-depois-" + monday_this_week.isoformat()))

for index in range(0, len(requests), 25):
    pending = {table: requests[index:index + 25]}
    for attempt in range(6):
        result = subprocess.run(
            ["awslocal", "dynamodb", "batch-write-item", "--request-items", json.dumps(pending)],
            check=True,
            capture_output=True,
            text=True,
        )
        pending = json.loads(result.stdout).get("UnprocessedItems", {})
        if not pending:
            break
        time.sleep(0.25 * (attempt + 1))
    if pending:
        raise SystemExit("O DynamoDB local não processou todos os itens após várias tentativas.")

print(f"Inseridos/atualizados 30 registros determinísticos na tabela {table}.")
print(f"Semana coberta pelo relatório semanal: {week_start} a {week_end} (America/Sao_Paulo).")
print(f"Dentro do período: 28; CRITICA: {urgency_counts['CRITICA']}; NORMAL: {urgency_counts['NORMAL']}.")
print("Os dois itens restantes estão fora do período, um antes e outro no início da semana seguinte.")
PY
BASH
