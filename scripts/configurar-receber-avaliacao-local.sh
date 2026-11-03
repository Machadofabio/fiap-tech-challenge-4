#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
TABLE="${AVALIACOES_TABLE:-avaliacoes}"

docker compose --profile aws up -d localstack
./gradlew --no-daemon :receber-avaliacao:quarkusBuild
docker compose cp build/receber-avaliacao/function.zip localstack:/tmp/receber-avaliacao-function.zip

docker compose exec -T -e AVALIACOES_TABLE="$TABLE" localstack bash -s <<'LOCAL'
set -euo pipefail
FUNCTION=receber-avaliacao
ROLE=fiap-receber-avaliacao
TABLE="${AVALIACOES_TABLE:-avaliacoes}"

if ! awslocal dynamodb describe-table --table-name "$TABLE" >/dev/null 2>&1; then
  echo "A tabela '$TABLE' não existe. Verifique a inicialização do LocalStack." >&2
  exit 1
fi

python3 - "$TABLE" <<'PY'
import json, sys
table = sys.argv[1]
account = "000000000000"
region = "us-east-1"
with open("/tmp/receber-avaliacao-assume-role.json", "w", encoding="utf-8") as file:
    json.dump({"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}, file)
with open("/tmp/receber-avaliacao-role-policy.json", "w", encoding="utf-8") as file:
    json.dump({"Version":"2012-10-17","Statement":[
        {"Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem"],"Resource":f"arn:aws:dynamodb:{region}:{account}:table/{table}"},
        {"Effect":"Allow","Action":["logs:CreateLogGroup","logs:CreateLogStream","logs:PutLogEvents"],"Resource":"arn:aws:logs:*:*:*"}
    ]}, file)
PY

if ! awslocal iam get-role --role-name "$ROLE" >/dev/null 2>&1; then
  awslocal iam create-role --role-name "$ROLE" \
    --assume-role-policy-document file:///tmp/receber-avaliacao-assume-role.json >/dev/null
fi
awslocal iam put-role-policy --role-name "$ROLE" \
  --policy-name receber-avaliacao-dynamodb-access \
  --policy-document file:///tmp/receber-avaliacao-role-policy.json
ROLE_ARN="$(awslocal iam get-role --role-name "$ROLE" --query Role.Arn --output text)"
ENVIRONMENT="Variables={AWS_REGION=us-east-1,AWS_ACCESS_KEY_ID=test,AWS_SECRET_ACCESS_KEY=test,DYNAMODB_ENDPOINT=http://localstack:4566,AVALIACOES_TABLE=$TABLE,CORS_ORIGINS=http://localhost:5174}"

if awslocal lambda get-function --function-name "$FUNCTION" >/dev/null 2>&1; then
  awslocal lambda update-function-code --function-name "$FUNCTION" \
    --zip-file fileb:///tmp/receber-avaliacao-function.zip >/dev/null
  awslocal lambda wait function-updated --function-name "$FUNCTION"
  awslocal lambda update-function-configuration --function-name "$FUNCTION" \
    --role "$ROLE_ARN" --runtime java25 \
    --handler 'io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest' \
    --timeout 30 --memory-size 512 --environment "$ENVIRONMENT" >/dev/null
else
  awslocal lambda create-function --function-name "$FUNCTION" \
    --runtime java25 \
    --handler 'io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest' \
    --role "$ROLE_ARN" --timeout 30 --memory-size 512 \
    --zip-file fileb:///tmp/receber-avaliacao-function.zip \
    --environment "$ENVIRONMENT" >/dev/null
fi
awslocal lambda wait function-active --function-name "$FUNCTION"
awslocal lambda wait function-updated --function-name "$FUNCTION"
echo "Lambda $FUNCTION configurada no LocalStack."
LOCAL
