#!/usr/bin/env bash
set -euo pipefail

docker compose --profile aws exec -T localstack bash -c '
  set -euo pipefail
  QUEUE="${FAILURE_QUEUE_NAME:-notificar-avaliacao-critica-falhas}"
  QUEUE_URL="$(awslocal sqs get-queue-url --queue-name "$QUEUE" --query QueueUrl --output text)"
  MESSAGE="$(awslocal sqs receive-message --queue-url "$QUEUE_URL" \
    --max-number-of-messages 1 --visibility-timeout 60 --wait-time-seconds 5 --output json)"
  RECEIPT="$(python3 -c "import json,sys; print(json.load(sys.stdin).get(\"Messages\", [{}])[0].get(\"ReceiptHandle\", \"\"))" <<<"$MESSAGE")"
  if [[ -z "$RECEIPT" ]]; then
    echo "A fila não tem mensagem de falha disponível."
    exit 1
  fi

  BODY="$(python3 -c "import json,sys; print(json.load(sys.stdin)[\"Messages\"][0][\"Body\"])" <<<"$MESSAGE")"
  STREAM_ARN="$(python3 -c "import json,sys; print(json.loads(sys.stdin.read())[\"DDBStreamBatchInfo\"][\"streamArn\"])" <<<"$BODY")"
  SHARD_ID="$(python3 -c "import json,sys; print(json.loads(sys.stdin.read())[\"DDBStreamBatchInfo\"][\"shardId\"])" <<<"$BODY")"
  SEQUENCE_NUMBER="$(python3 -c "import json,sys; print(json.loads(sys.stdin.read())[\"DDBStreamBatchInfo\"][\"startSequenceNumber\"])" <<<"$BODY")"
  SHARD_ITERATOR="$(awslocal dynamodbstreams get-shard-iterator \
    --stream-arn "$STREAM_ARN" --shard-id "$SHARD_ID" \
    --shard-iterator-type AT_SEQUENCE_NUMBER --sequence-number "$SEQUENCE_NUMBER" \
    --query ShardIterator --output text)"
  awslocal dynamodbstreams get-records --shard-iterator "$SHARD_ITERATOR" --limit 1 \
    > /tmp/be-03-recovery-records.json
  python3 - "$SEQUENCE_NUMBER" <<'PY'
import json
import sys

sequence = sys.argv[1]
with open("/tmp/be-03-recovery-records.json", encoding="utf-8") as source:
    records = json.load(source).get("Records", [])
record = next((item for item in records if item.get("dynamodb", {}).get("SequenceNumber") == sequence), None)
if record is None:
    raise SystemExit("O registro já não está disponível no DynamoDB Streams; não apaguei a mensagem SQS.")
with open("/tmp/be-03-recovery-event.json", "w", encoding="utf-8") as target:
    json.dump({"Records": [record]}, target)
PY

  INVOKE_RESULT="$(awslocal lambda invoke --function-name notificar-avaliacao-critica \
    --payload fileb:///tmp/be-03-recovery-event.json \
    /tmp/be-03-recovery-response.json)"
  FUNCTION_ERROR="$(python3 -c "import json,sys; print(json.loads(sys.stdin.read()).get(\"FunctionError\", \"\"))" <<<"$INVOKE_RESULT")"
  if [[ -n "$FUNCTION_ERROR" && "$FUNCTION_ERROR" != "None" ]]; then
    echo "A reexecução da Lambda falhou; mantive a mensagem na fila."
    exit 1
  fi

  awslocal sqs delete-message --queue-url "$QUEUE_URL" --receipt-handle "$RECEIPT"
  echo "Registro recuperado do Stream e reprocessado; mensagem SQS removida."
'
