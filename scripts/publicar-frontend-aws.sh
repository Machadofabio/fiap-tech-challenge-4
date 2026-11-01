#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
Compila o frontend com a URL da API e publica os arquivos no bucket privado
servido pelo CloudFront definido no template SAM.

Uso:
  scripts/publicar-frontend-aws.sh NOME_DA_STACK

Usa AWS_REGION ou AWS_DEFAULT_REGION (padrão: us-east-1) e respeita o perfil
AWS configurado no ambiente/CLI.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi
if [[ $# -ne 1 ]]; then
  usage >&2
  exit 2
fi

STACK_NAME="$1"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"

stack_outputs="$(aws cloudformation describe-stacks \
  --stack-name "$STACK_NAME" \
  --region "$REGION" \
  --query 'Stacks[0].Outputs' \
  --output json)"

output_value() {
  local key="$1" value
  value="$(python3 -c 'import json,sys; outputs=json.load(sys.stdin); key=sys.argv[1]; print(next((item["OutputValue"] for item in outputs if item["OutputKey"] == key), ""))' \
    "$key" <<<"$stack_outputs")"
  if [[ -z "$value" ]]; then
    echo "A saída '$key' não foi encontrada na stack '$STACK_NAME'." >&2
    exit 1
  fi
  printf '%s' "$value"
}

API_URL="$(output_value EvaluationsApiUrl)"
BUCKET="$(output_value FrontendBucketName)"
DISTRIBUTION_ID="$(output_value FrontendDistributionId)"
FRONTEND_URL="$(output_value FrontendUrl)"

if [[ ! -x frontend/node_modules/.bin/vite ]]; then
  npm --prefix frontend install --no-save --no-package-lock --no-audit --no-fund
fi
VITE_API_MODE=http VITE_API_BASE_URL="$API_URL" npm --prefix frontend run build
aws s3 sync frontend/dist "s3://$BUCKET" --delete --region "$REGION"
INVALIDATION_ID="$(aws cloudfront create-invalidation \
  --distribution-id "$DISTRIBUTION_ID" \
  --paths '/*' \
  --query 'Invalidation.Id' \
  --output text)"

cat <<EOF
Frontend publicado no bucket $BUCKET.
URL: $FRONTEND_URL
Invalidação CloudFront: $INVALIDATION_ID
EOF
