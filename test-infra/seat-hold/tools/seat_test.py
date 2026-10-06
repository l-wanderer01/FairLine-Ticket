#!/usr/bin/env python3
"""Local seat-test requests and observations; no third-party Python dependencies."""
import argparse
import base64
import datetime as dt
import hashlib
import hmac
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ["docker", "compose", "--project-name", "fairline-seat-test",
           "--file", str(ROOT / "compose.yaml"), "--env-file", str(ROOT / ".env")]


def run(args, *, data=None, capture=True):
    return subprocess.run(args, input=data, text=True, check=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def config():
    cfg = json.loads(run(COMPOSE + ["config", "--format", "json"]))
    services = cfg["services"]
    if cfg["name"] != "fairline-seat-test" or set(services) != {"postgres", "redis", "ticketing-service"}:
        raise ValueError("Only the dedicated fairline-seat-test project is supported")
    env = services["ticketing-service"]["environment"]
    if (env["SPRING_PROFILES_ACTIVE"] != "seat-test" or env["REDIS_HOST"] != "redis"
            or not env["DB_URL"].startswith("jdbc:postgresql://postgres:5432/seat_test?")):
        raise ValueError("Refusing connections outside the isolated seat-test stack")
    if services["postgres"]["environment"]["POSTGRES_DB"] != "seat_test":
        raise ValueError("Refusing a non-test database")
    port = services["ticketing-service"]["ports"][0]
    if port["host_ip"] != "127.0.0.1":
        raise ValueError("The API must bind to loopback")
    return env, f"http://127.0.0.1:{port['published']}"


def token(user, secret, seconds=3600):
    if len(secret.encode()) < 32:
        raise ValueError("JWT secret must be at least 32 UTF-8 bytes")
    if user not in {101, 102, 103} and not 1001 <= user <= 1100:
        raise ValueError("Use a user ID from the common fixture")
    now = int(time.time())
    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b"=")
    head = encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    body = encode(json.dumps({"sub": str(user), "email": f"u-{user}@seat-test.invalid",
                             "iat": now, "exp": now + seconds}).encode())
    payload = head + b"." + body
    return (payload + b"." + encode(hmac.new(secret.encode(), payload, hashlib.sha256).digest())).decode()


def request(args, env, base):
    action = args.action
    select = {"scheduleId": args.schedule, "section": "A", "rowNumber": 1,
              "seatNumber": args.seat - 400 if args.seat <= 409 else 1}
    if action in {"hold", "release"}:
        method, path, body = ("POST" if action == "hold" else "DELETE"), "/api/seats/hold", select
    elif action in {"batch", "booking"}:
        method, path, body = "POST", ("/api/seats/holds" if action == "batch" else "/api/bookings"), {
            "concertId": args.concert, "seatIds": args.seats}
    elif action == "leave":
        method, path, body = "POST", f"/api/seats/leave?concertId={args.concert}&scheduleId={args.schedule}", None
    else:
        if not args.booking:
            raise ValueError("--booking must be the UUID returned by a booking request")
        uuid.UUID(args.booking)
        method, path = "POST", f"/internal/finalizations/{action}"
        body = {"bookingId": args.booking, "paymentId": args.payment,
                "pgOrderId": "seat-test-fake-order"}
        if action == "confirm":
            body.update(pgPaymentKey="seat-test-fake-key", amount=args.amount)
        else:
            body["reasonCode"] = "SEAT_TEST"
    headers = {"Content-Type": "application/json"}
    if action in {"confirm", "cancel", "expire"}:
        headers["X-Internal-Api-Key"] = env["TICKETING_INTERNAL_API_TOKEN"]
    else:
        headers["Authorization"] = "Bearer " + token(args.user, env["JWT_SECRET"])
    req = urllib.request.Request(base + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    started = dt.datetime.now(dt.timezone.utc).isoformat()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        response = opener.open(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode()
        try:
            content = json.loads(raw)
        except json.JSONDecodeError:
            content = raw
        # No request headers/JWT/credentials are recorded.
        print(json.dumps({"at": started, "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                          "action": action, "user": args.user, "request": body,
                          "http": response.code, "response": content}, ensure_ascii=False, indent=2))


def redis(*args):
    return run(COMPOSE + ["exec", "-T", "redis", "redis-cli", "--raw", *map(str, args)]).strip()


def db(sql):
    return run(COMPOSE + ["exec", "-T", "postgres", "sh", "-ec",
                          'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -P pager=off'], data=sql)


def observe(args):
    prefix = f"concert:{args.concert}:schedule:{args.schedule}"
    result = {"at": dt.datetime.now(dt.timezone.utc).isoformat(), "seats": {}}
    for seat in args.seats:
        key = f"seat:{prefix}:seatId:{seat}"
        owner = redis("GET", key)
        started = dt.datetime.now(dt.timezone.utc).isoformat()
        pttl = int(redis("PTTL", key))
        result["seats"][seat] = {"owner": owner, "pttl": pttl, "pttlStartedAt": started,
                                 "pttlFinishedAt": dt.datetime.now(dt.timezone.utc).isoformat()}
    result["holds"] = redis("SMEMBERS", f"seat:user:holds:{prefix}:user:{args.user}").splitlines()
    result["access"] = redis("GET", f"seat:access:user:{args.user}:{prefix}")
    result["accessBySchedule"] = redis("GET", f"seat:access:user:{args.user}:schedule:{args.schedule}")
    result["accessIndex"] = redis("SMEMBERS", f"seat:access:index:{prefix}").splitlines()
    result["active"] = redis("GET", f"seat:active:{prefix}")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(db("SELECT clock_timestamp() AS db_now;\n"
             "SELECT id,schedule_id,status,version,price FROM concert.seats ORDER BY id;\n"
             "SELECT id,user_id,schedule_id,status,total_price,created_at,expires_at,confirmed_at FROM ticketing.bookings ORDER BY created_at,id;\n"
             "SELECT booking_id,seat_id FROM ticketing.booking_items ORDER BY booking_id,seat_id;\n"
             "SELECT bi.seat_id,count(*) FROM ticketing.booking_items bi JOIN ticketing.bookings b ON b.id=bi.booking_id "
             "WHERE b.status='CONFIRMED' OR (b.status='HOLDING' AND b.expires_at>now()) GROUP BY bi.seat_id HAVING count(*)>1;\n"))


def reset(args):
    if not args.discard_evidence:
        raise ValueError("Collect failure evidence first; pass --discard-evidence to delete test data")
    # Fixed project/file/environment plus connection checks keep deletion local to this test stack.
    run(COMPOSE + ["stop", "ticketing-service"], capture=False)
    db((ROOT / "sql" / "02-fixture.sql").read_text())
    if redis("FLUSHDB") != "OK":
        raise RuntimeError("Redis reset failed; ticketing remains stopped")
    run(COMPOSE + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "180"], capture=False)
    print("Reset complete: common DB fixture restored and dedicated Redis DB 0 empty")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check")
    jwt = sub.add_parser("jwt")
    jwt.add_argument("--user", type=int, default=101)
    jwt.add_argument("--seconds", type=int, default=3600)
    api = sub.add_parser("request")
    api.add_argument("action", choices=["hold", "release", "batch", "leave", "booking", "confirm", "cancel", "expire"])
    api.add_argument("--seat", type=int, default=401)
    api.add_argument("--booking")
    api.add_argument("--payment", default="00000000-0000-0000-0000-000000000001")
    api.add_argument("--amount", type=int, default=20000)
    obs = sub.add_parser("observe")
    for cmd in [api, obs]:
        cmd.add_argument("--user", type=int, default=101)
        cmd.add_argument("--concert", type=int, default=201)
        cmd.add_argument("--schedule", type=int, default=301)
        cmd.add_argument("--seats", type=int, nargs="+", default=[401, 402] if cmd is api else [401, 402, 403, 404, 405])
    rs = sub.add_parser("reset")
    rs.add_argument("--discard-evidence", action="store_true")
    args = parser.parse_args()
    env, base = config()
    if args.command == "check":
        print("Dedicated seat-test configuration OK; endpoint=" + base)
    elif args.command == "jwt":
        print(token(args.user, env["JWT_SECRET"], args.seconds))
    elif args.command == "request":
        request(args, env, base)
    elif args.command == "observe":
        observe(args)
    else:
        reset(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as exc:
        print(f"seat-test failed: {exc}", file=sys.stderr)
        sys.exit(1)
