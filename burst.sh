#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-token}"
HOT_SEAT="${HOT_SEAT:-A1}"
HOT_USERS="${HOT_USERS:-500}"
HOT_PARALLELISM="${HOT_PARALLELISM:-500}"
LIMIT_USERS="${LIMIT_USERS:-10}"
PER_USER_LIMIT="${PER_USER_LIMIT:-4}"
SEAT_COUNT="${SEAT_COUNT:-100}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "==> Target: $BASE_URL"
echo "==> Waiting for readiness..."
for i in $(seq 1 60); do
  if curl -sf "$BASE_URL/health/ready" >/dev/null; then
    break
  fi
  if [[ "$i" -eq 60 ]]; then
    echo "Service not ready at $BASE_URL" >&2
    exit 1
  fi
  sleep 1
done

echo "==> Creating show with $SEAT_COUNT seats (per_user_limit=$PER_USER_LIMIT)..."
SEATS_JSON=$(python3 - <<PY
seats = [f"A{i}" for i in range(1, ${SEAT_COUNT}+1)]
import json
print(json.dumps(seats))
PY
)

SHOW_RESP=$(curl -sf -X POST "$BASE_URL/shows" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"burst-show-$(date +%s)\",\"seats\":$SEATS_JSON,\"price_paise\":25000,\"per_user_limit\":$PER_USER_LIMIT}")

SHOW_ID=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$SHOW_RESP")
echo "    show_id=$SHOW_ID"

echo "==> Creating $HOT_USERS users for hot-seat storm on $HOT_SEAT..."
mkdir -p "$WORKDIR/tokens"
export BASE_URL HOT_USERS WORKDIR
python3 - <<'PY'
import concurrent.futures
import json
import os
import urllib.request

base_url = os.environ["BASE_URL"]
user_count = int(os.environ["HOT_USERS"])
token_dir = os.path.join(os.environ["WORKDIR"], "tokens")

def create_user(i):
    body = json.dumps({"display_name": f"hot-user-{i}"}).encode()
    request = urllib.request.Request(
        f"{base_url}/auth/users",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        result = json.load(response)
    with open(os.path.join(token_dir, f"hot-{i}.token"), "w", encoding="utf-8") as token_file:
        token_file.write(result["token"])

with concurrent.futures.ThreadPoolExecutor(max_workers=min(20, user_count)) as pool:
    list(pool.map(create_user, range(1, user_count + 1)))
PY

echo "==> Hot-seat storm: $HOT_USERS concurrent reserves for $HOT_SEAT"
HOT_OUT="$WORKDIR/hot_results.txt"
: > "$HOT_OUT"

export BASE_URL SHOW_ID HOT_SEAT WORKDIR HOT_OUT

python3 - <<'PY'
import concurrent.futures
import json
import os
import urllib.error
import urllib.request

base_url = os.environ["BASE_URL"]
show_id = os.environ["SHOW_ID"]
seat = os.environ["HOT_SEAT"]
user_count = int(os.environ["HOT_USERS"])
parallelism = max(1, int(os.environ["HOT_PARALLELISM"]))
token_dir = os.path.join(os.environ["WORKDIR"], "tokens")

def reserve(i):
    with open(os.path.join(token_dir, f"hot-{i}.token"), encoding="utf-8") as token_file:
        token = token_file.read()
    body = json.dumps({"seats": [seat], "idempotency_key": f"hot-{i}"}).encode()
    request = urllib.request.Request(
        f"{base_url}/shows/{show_id}/reserve",
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "X-Request-Id": f"hot-{i}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            code = response.status
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        code = error.code
        try:
            payload = json.load(error)
        except (json.JSONDecodeError, ValueError):
            payload = {}
    except Exception:
        code, payload = 0, {}
    return i, code, payload.get("reason", "none")

with concurrent.futures.ThreadPoolExecutor(max_workers=min(user_count, parallelism)) as pool:
    results = sorted(pool.map(reserve, range(1, user_count + 1)))
with open(os.path.join(os.environ["WORKDIR"], "hot_results.txt"), "w", encoding="utf-8") as output:
    for _, code, reason in results:
        output.write(f"{code:03d} {reason}\n")
PY

HOT_201=$(grep -c '^201 ' "$HOT_OUT" || true)
HOT_409=$(grep -c '^409 ' "$HOT_OUT" || true)
HOT_5XX=$(grep -E -c '^5[0-9][0-9] ' "$HOT_OUT" || true)
HOT_OTHER=$((HOT_USERS - HOT_201 - HOT_409 - HOT_5XX))

echo ""
echo "=== HOT-SEAT OUTCOME ==="
echo "confirmed(201): $HOT_201"
echo "declined(409):  $HOT_409"
echo "5xx:            $HOT_5XX"
echo "other:          $HOT_OTHER"
echo "decline reasons:"
awk '$1 == "409" { print $2 }' "$HOT_OUT" | sort | uniq -c || true
if [[ "$HOT_201" -ne 1 || "$HOT_409" -ne "$((HOT_USERS - 1))" || "$HOT_5XX" -ne 0 || "$HOT_OTHER" -ne 0 ]]; then
  echo "Hot-seat correctness check failed" >&2
  exit 1
fi

echo ""
echo "==> Idempotency: same key twice, then same key different seats"
IDEM_USER=$(curl -sf -X POST "$BASE_URL/auth/users" -H "Content-Type: application/json" -d '{"display_name":"idem-user"}')
IDEM_TOKEN=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])' <<<"$IDEM_USER")

run_idempotency_race() {
  local i="$1"
  local code
  code=$(curl -s -o "$WORKDIR/idem-race-$i.json" -w "%{http_code}" -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
    -H "Authorization: Bearer $IDEM_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"seats":["A2"],"idempotency_key":"same-key-1","user_id":"spoofed-user"}')
  echo "$code" > "$WORKDIR/idem-race-$i.code"
}
export -f run_idempotency_race
export BASE_URL SHOW_ID IDEM_TOKEN WORKDIR
seq 1 2 | xargs -P 2 -I{} bash -c 'run_idempotency_race "$@"' _ {}
IDEM_RACE_1=$(<"$WORKDIR/idem-race-1.code")
IDEM_RACE_2=$(<"$WORKDIR/idem-race-2.code")
RACE_RID_1=$(python3 -c 'import json; print(json.load(open("'"$WORKDIR"'/idem-race-1.json")).get("reservation_id",""))')
RACE_RID_2=$(python3 -c 'import json; print(json.load(open("'"$WORKDIR"'/idem-race-2.json")).get("reservation_id",""))')
IDEM_USER_ID=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["user_id"])' <<<"$IDEM_USER")
RACE_RESPONSE_USER_ID=$(python3 -c 'import json; print(json.load(open("'"$WORKDIR"'/idem-race-1.json")).get("user_id",""))')
echo "concurrent same-key statuses=$IDEM_RACE_1,$IDEM_RACE_2 reservation ids match: $([[ "$RACE_RID_1" == "$RACE_RID_2" && -n "$RACE_RID_1" ]] && echo YES || echo NO); spoofed body user_id ignored: $([[ "$RACE_RESPONSE_USER_ID" == "$IDEM_USER_ID" ]] && echo YES || echo NO)"
if [[ "$IDEM_RACE_1" != "201" || "$IDEM_RACE_2" != "201" || "$RACE_RID_1" != "$RACE_RID_2" || -z "$RACE_RID_1" || "$RACE_RESPONSE_USER_ID" != "$IDEM_USER_ID" ]]; then
  echo "Concurrent idempotency replay failed" >&2
  exit 1
fi

IDEM1=$(curl -s -o "$WORKDIR/idem1.json" -w "%{http_code}" -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
  -H "Authorization: Bearer $IDEM_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"seats":["A2"],"idempotency_key":"same-key-1"}')
IDEM2=$(curl -s -o "$WORKDIR/idem2.json" -w "%{http_code}" -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
  -H "Authorization: Bearer $IDEM_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"seats":["A2"],"idempotency_key":"same-key-1"}')
IDEM3=$(curl -s -o "$WORKDIR/idem3.json" -w "%{http_code}" -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
  -H "Authorization: Bearer $IDEM_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"seats":["A3"],"idempotency_key":"same-key-1"}')

RID1=$(python3 -c 'import json; print(json.load(open("'"$WORKDIR"'/idem1.json")).get("reservation_id",""))')
RID2=$(python3 -c 'import json; print(json.load(open("'"$WORKDIR"'/idem2.json")).get("reservation_id",""))')

echo "idempotent first=$IDEM1 second=$IDEM2 different-body=$IDEM3"
echo "reservation ids match: $([[ "$RID1" == "$RID2" && -n "$RID1" ]] && echo YES || echo NO)"
if [[ "$IDEM1" != "201" || "$IDEM2" != "201" || "$IDEM3" != "409" || "$RID1" != "$RID2" || -z "$RID1" ]]; then
  echo "Idempotency check failed" >&2
  exit 1
fi

echo ""
echo "==> Per-user limit under concurrency ($LIMIT_USERS parallel reserves, limit=$PER_USER_LIMIT)"
LIMIT_USER=$(curl -sf -X POST "$BASE_URL/auth/users" -H "Content-Type: application/json" -d '{"display_name":"limit-user"}')
LIMIT_TOKEN=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])' <<<"$LIMIT_USER")
LIMIT_OUT="$WORKDIR/limit_results.txt"
: > "$LIMIT_OUT"

run_limit() {
  local i="$1"
  local seat="A$((10 + i))"
  local code
  code=$(curl -s -o "$WORKDIR/limit-$i.body" -w "%{http_code}" -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
    -H "Authorization: Bearer $LIMIT_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"seats\":[\"$seat\"],\"idempotency_key\":\"limit-$i\"}")
  echo "$code" >> "$LIMIT_OUT"
}
export -f run_limit
export LIMIT_TOKEN LIMIT_OUT

seq 1 "$LIMIT_USERS" | xargs -P "$LIMIT_USERS" -I{} bash -c 'run_limit "$@"' _ {}

LIM_201=$(grep -c '^201$' "$LIMIT_OUT" || true)
LIM_409=$(grep -c '^409$' "$LIMIT_OUT" || true)
LIM_5XX=$(grep -E -c '^5[0-9][0-9]$' "$LIMIT_OUT" || true)

echo "confirmed(201): $LIM_201 (expected <= $PER_USER_LIMIT)"
echo "declined(409):  $LIM_409"
echo "5xx:            $LIM_5XX"
if [[ "$LIM_201" -gt "$PER_USER_LIMIT" || "$LIM_5XX" -ne 0 || "$LIM_409" -ne "$((LIMIT_USERS - LIM_201))" ]]; then
  echo "Per-user limit correctness check failed" >&2
  exit 1
fi

echo ""
echo "==> Cancellation ownership and seat release"
CANCEL_USER=$(curl -sf -X POST "$BASE_URL/auth/users" -H "Content-Type: application/json" -d '{"display_name":"cancel-owner"}')
CANCEL_TOKEN=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])' <<<"$CANCEL_USER")
OTHER_USER=$(curl -sf -X POST "$BASE_URL/auth/users" -H "Content-Type: application/json" -d '{"display_name":"non-owner"}')
OTHER_TOKEN=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])' <<<"$OTHER_USER")
CANCEL_SEAT="A$SEAT_COUNT"
FIRST_RESERVATION=$(curl -sf -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
  -H "Authorization: Bearer $CANCEL_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"seats\":[\"$CANCEL_SEAT\"],\"idempotency_key\":\"cancel-test-first\"}")
FIRST_RESERVATION_ID=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["reservation_id"])' <<<"$FIRST_RESERVATION")
NON_OWNER_CANCEL=$(curl -s -o "$WORKDIR/non-owner-cancel.json" -w "%{http_code}" -X POST "$BASE_URL/reservations/$FIRST_RESERVATION_ID/cancel" \
  -H "Authorization: Bearer $OTHER_TOKEN")
OWNER_CANCEL=$(curl -s -o "$WORKDIR/owner-cancel.json" -w "%{http_code}" -X POST "$BASE_URL/reservations/$FIRST_RESERVATION_ID/cancel" \
  -H "Authorization: Bearer $CANCEL_TOKEN")
REBOOK=$(curl -s -o "$WORKDIR/rebook.json" -w "%{http_code}" -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
  -H "Authorization: Bearer $CANCEL_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"seats\":[\"$CANCEL_SEAT\"],\"idempotency_key\":\"cancel-test-rebook\"}")
echo "non-owner cancel=$NON_OWNER_CANCEL owner cancel=$OWNER_CANCEL released-seat rebook=$REBOOK"
if [[ "$NON_OWNER_CANCEL" != "403" || "$OWNER_CANCEL" != "200" || "$REBOOK" != "201" ]]; then
  echo "Cancellation ownership or seat release check failed" >&2
  exit 1
fi

echo ""
echo "==> Final reconciliation"
SHOW_STATE=$(curl -sf "$BASE_URL/shows/$SHOW_ID")
python3 - <<PY
import json
import sys
s = json.loads('''$SHOW_STATE''')
total = s["total_seats"]
avail, held, conf = s["available"], s["held"], s["confirmed"]
ok = (avail + held + conf) == total
print(f"available={avail} held={held} confirmed={conf} total={total}")
print(f"invariant available+held+confirmed==total: {'PASS' if ok else 'FAIL'}")
print(f"hot seat $HOT_SEAT status: ", end="")
for seat in s["seats"]:
    if seat["seat"] == "$HOT_SEAT":
        print(seat["status"])
        break
if not ok:
    sys.exit(1)
PY

echo ""
echo "==> Metrics snapshot (confirmed / declines)"
curl -sf "$BASE_URL/actuator/prometheus" | grep -E 'reservations_(confirmed|declined)_total|seats_available'

echo ""
echo "DONE show_id=$SHOW_ID"
