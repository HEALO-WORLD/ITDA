"""
부하 테스트용 1년치 누적 데이터 생성기 ('확대' 규모, 기본 8.76만 건)

data/*.csv(gen_users.py 결과)를 읽어 기관·계정·장비·슬롯과 과거 1년의 의뢰·예약·검사 이력을 만든다.
DB가 서비스별로 나뉘어 있어서(institution_db, slot_db, referral_db) 하나의 계획을 세운 뒤
DB별 테이블 CSV로 나눠 쓴다. 적재는 run-seed.sh가 COPY로 한다.

사용법:
    python gen_seed.py                       # 오늘(한국 시간) 기준, 기본 시드
    python gen_seed.py --today 2026-11-18    # 기준일 지정 (테스트 당일 날짜로 다시 생성)
    python gen_seed.py --total 21900         # 규모 조절 (기본 240 x 365 = 87,600)

규칙:
    - 모든 ID는 f1KK0000-0000-0000-0000-NNNNNNNNNNNN 형태다 (KK=종류, N=순번). 식별·삭제는 이 패턴 전체로 한다
      (앞 두 글자 f1만 보면 앱이 만든 무작위 UUID도 256분의 1 확률로 걸린다. v4·v7 UUID는 세 번째 묶음이 0000일 수 없다)
    - 같은 시드·기준일이면 항상 같은 결과가 나온다
    - 의뢰는 기준일 전날까지 만들어지고, 검사일은 의뢰일로부터 1~10영업일 뒤(일부 토요일)
    - 지난 의뢰 결과: 완료 86%, 취소 5%, 슬롯 경합 실패 8%, 장비 장애 후 만료 1%
    - 기준일 당일 검사는 today_examinations.csv와 정확히 같다 (검사관리자 시나리오용)
    - 기준일 이후의 인기 슬롯(평일 MRI 08:00, 토요일 전체)은 비워 둔다 (인기 슬롯 경쟁 시나리오용)
"""
import argparse
import csv
import json
import random
from bisect import insort
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

BASE = Path(__file__).parent
DATA = BASE / "data"

# 평문 Loadtest!234 의 BCrypt 해시 (pgcrypto crypt(..., gen_salt('bf', 10))로 생성, Spring BCryptPasswordEncoder와 호환)
PASSWORD_HASH = "$2a$10$fWAUm7p0Nx23RMRVKIE05eDtqju03NSF2uHbATe4tqcTyfC3NHk9a"
SYSTEM_USER = "00000000-0000-0000-0000-000000000000"

EXAM_TYPES = {"뇌 MRI": "MRI", "척추 MRI": "MRI", "흉부 CT": "CT", "복부 CT": "CT"}
EXAM_MINUTES = 30
WEEKDAY_SLOTS = 20   # 08:00~18:00, 30분
SATURDAY_SLOTS = 8   # 08:00~12:00
# 기준일부터 앞으로 슬롯을 만들어 둘 기간 (--ahead-days)
# 시드 이력의 검사일은 최대 약 3주 뒤까지지만, 스트레스 테스트가 높은 단계에서 슬롯을 다 써버리지 않도록 넉넉히 둔다
SLOT_RANGE_AHEAD_DAYS = 90

OUTCOME_SHARE = {"COMPLETED": 0.86, "CANCELED": 0.05, "FAILED": 0.08, "EXPIRED": 0.01}
SATURDAY_TARGET_SHARE = 0.06
EXPIRE_AFTER_HOURS = 72  # 장비 장애로 재예약 대기 후 만료까지

SURNAMES = list("김이박최정강조윤장임한오서신권황안송류전홍고문양손배백허유남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용")
GIVEN = list("민서준지현우도윤하은수연영진호성재가경나다라미보소아유주채태희승혜원정훈동석")

KIND = {
    "institution": 0x01, "user": 0x02, "partnership": 0x03, "partnership_history": 0x04,
    "exam_type": 0x05, "equipment": 0x06, "equipment_exam_type": 0x07, "operating_hours": 0x08,
    "slot": 0x09, "slot_history": 0x0A, "referral": 0x0B, "reservation": 0x0C,
    "referral_history": 0x0D, "examination": 0x0E, "result": 0x0F, "inst_notification": 0x10,
    "event": 0x11, "idempotency": 0x12,
}


class Ids:
    """f1 + 종류(1바이트) 접두사의 순차 UUID. 같은 입력이면 같은 ID가 나온다."""

    def __init__(self):
        self.counters = {k: 0 for k in KIND}

    def next(self, kind):
        self.counters[kind] += 1
        return self.of(kind, self.counters[kind])

    @staticmethod
    def of(kind, n):
        return f"f1{KIND[kind]:02x}0000-0000-0000-0000-{n:012x}"


def ts(dt):
    return dt.strftime("%Y-%m-%d %H:%M:%S.%f") if dt else None


def read_csv(name):
    with (DATA / name).open(encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


class Out:
    """DB별 테이블 CSV를 바로 디스크에 쓴다 (메모리에 행을 쌓지 않음)."""

    def __init__(self, root: Path):
        self.root = root
        self.files, self.writers, self.counts = {}, {}, {}

    def table(self, db, name, cols):
        key = (db, name)
        if key not in self.writers:
            path = self.root / db / f"{name}.csv"
            path.parent.mkdir(parents=True, exist_ok=True)
            f = path.open("w", newline="", encoding="utf-8")
            w = csv.writer(f, lineterminator="\n")
            w.writerow(cols)
            self.files[key], self.writers[key], self.counts[key] = f, w, 0
        return key

    def row(self, key, values):
        self.writers[key].writerow(values)
        self.counts[key] += 1

    def close(self):
        for f in self.files.values():
            f.close()


BASE_COLS = ["created_at", "created_by", "updated_at", "updated_by"]


def build(seed: int, today: date, total: int, days: int, out_root: Path, ahead_days: int = SLOT_RANGE_AHEAD_DAYS):
    rng = random.Random(seed)
    ids = Ids()
    out = Out(out_root)
    midnight = datetime.combine(today, time())  # 시드 데이터의 모든 이벤트는 기준일 0시 이전에 일어난다
    history_start = today - timedelta(days=days)
    setup_at = datetime.combine(history_start - timedelta(days=30), time(9))

    labs = read_csv("labs.csv")
    equipments = read_csv("equipments.csv")
    users = read_csv("referrer_users.csv")
    managers = read_csv("lab_managers.csv")
    partnerships = read_csv("partnerships.csv")
    today_exams = read_csv("today_examinations.csv")

    # ------------------------------------------------------------------ institution_db
    inst_cols = ["id", "name", "type", "address", "care_institution_code", "active"] + BASE_COLS
    t_inst = out.table("institution_db", "p_institutions", inst_cols)
    org_id, lab_inst_id = {}, {}
    code = 90000000
    for u in users:
        org_id[u["org_id"]] = ids.next("institution")
        code += 1
        out.row(t_inst, [org_id[u["org_id"]], u["org_name"], "REQUESTER", "부하테스트 가상 주소", str(code), True,
                         ts(setup_at), SYSTEM_USER, ts(setup_at), SYSTEM_USER])
    for lab in labs:
        lab_inst_id[lab["lab_id"]] = ids.next("institution")
        code += 1
        out.row(t_inst, [lab_inst_id[lab["lab_id"]], lab["lab_name"], "EXAM", "부하테스트 가상 주소", str(code), True,
                         ts(setup_at), SYSTEM_USER, ts(setup_at), SYSTEM_USER])

    t_user = out.table("institution_db", "p_users",
                       ["id", "login_id", "password", "role", "institution_id", "active"] + BASE_COLS)
    user_id = {}
    for u in users:
        user_id[u["user_id"]] = ids.next("user")
        out.row(t_user, [user_id[u["user_id"]], u["login_id"], PASSWORD_HASH, "REQUESTER", org_id[u["org_id"]], True,
                         ts(setup_at), SYSTEM_USER, ts(setup_at), SYSTEM_USER])
    for m in managers:
        user_id[m["user_id"]] = ids.next("user")
        out.row(t_user, [user_id[m["user_id"]], m["login_id"], PASSWORD_HASH, "EXAMINER", lab_inst_id[m["lab_id"]], True,
                         ts(setup_at), SYSTEM_USER, ts(setup_at), SYSTEM_USER])

    t_part = out.table("institution_db", "p_partnerships",
                       ["id", "requester_institution_id", "exam_institution_id", "status",
                        "requested_at", "approved_at"] + BASE_COLS)
    t_part_h = out.table("institution_db", "p_partnership_histories",
                         ["id", "partnership_id", "from_status", "to_status"] + BASE_COLS)
    partner_labs = {}
    approved_at = setup_at + timedelta(days=1)
    for p in partnerships:
        pid = ids.next("partnership")
        partner_labs.setdefault(p["org_id"], []).append(p["lab_id"])
        out.row(t_part, [pid, org_id[p["org_id"]], lab_inst_id[p["lab_id"]], "ACTIVE", ts(setup_at), ts(approved_at),
                         ts(setup_at), SYSTEM_USER, ts(approved_at), SYSTEM_USER])
        out.row(t_part_h, [ids.next("partnership_history"), pid, None, "PENDING",
                           ts(setup_at), SYSTEM_USER, ts(setup_at), SYSTEM_USER])
        out.row(t_part_h, [ids.next("partnership_history"), pid, "PENDING", "ACTIVE",
                           ts(approved_at), SYSTEM_USER, ts(approved_at), SYSTEM_USER])

    t_exam_type = out.table("institution_db", "p_exam_types", ["id", "name", "modality", "duration_minutes"] + BASE_COLS)
    exam_type_id = {}
    for name, modality in EXAM_TYPES.items():
        exam_type_id[name] = ids.next("exam_type")
        out.row(t_exam_type, [exam_type_id[name], name, modality, EXAM_MINUTES,
                              ts(setup_at), SYSTEM_USER, ts(setup_at), SYSTEM_USER])

    t_eq = out.table("institution_db", "p_equipments",
                     ["id", "institution_id", "name", "modality", "status", "manual_status", "downtime_revision"] + BASE_COLS)
    t_eq_exam = out.table("institution_db", "p_equipment_exam_types",
                          ["id", "equipment_id", "exam_type_id", "duration_minutes"] + BASE_COLS)
    t_hours = out.table("institution_db", "p_equipment_operating_hours",
                        ["id", "equipment_id", "week", "start_time", "end_time"] + BASE_COLS)
    eq_by_lab_exam = {}
    for idx, e in enumerate(equipments):
        e["id"] = ids.next("equipment")
        e["idx"] = idx
        eq_by_lab_exam.setdefault((e["lab_id"], e["exam_type"]), []).append(e)
        out.row(t_eq, [e["id"], lab_inst_id[e["lab_id"]], e["equipment_name"], e["modality"], "OPERATIONAL", "OPERATIONAL", 0,
                       ts(setup_at), SYSTEM_USER, ts(setup_at), SYSTEM_USER])
        out.row(t_eq_exam, [ids.next("equipment_exam_type"), e["id"], exam_type_id[e["exam_type"]], EXAM_MINUTES,
                            ts(setup_at), SYSTEM_USER, ts(setup_at), SYSTEM_USER])
        for week in range(1, 7):  # 1=월 ... 6=토 (DayOfWeek.getValue)
            end = "18:00:00" if week <= 5 else "12:00:00"
            out.row(t_hours, [ids.next("operating_hours"), e["id"], week, "08:00:00", end,
                              ts(setup_at), SYSTEM_USER, ts(setup_at), SYSTEM_USER])
    eq_by_code = {e["equipment_code"]: e for e in equipments}
    lab_weight = {lab["lab_id"]: float(lab["target_weight"]) for lab in labs}
    lab_exam_types = {}
    for e in equipments:
        lab_exam_types.setdefault(e["lab_id"], []).append(e["exam_type"])  # 장비 수만큼 들어가 비중이 된다

    # ------------------------------------------------------------------ 슬롯 계획
    slot_start_day = history_start
    slot_end_day = today + timedelta(days=ahead_days)
    slot_state = {}  # (eq_idx, date) -> [status, reservation_id, released] (슬롯 순서대로)

    def slots_per(d):
        wd = d.weekday()
        return WEEKDAY_SLOTS if wd < 5 else (SATURDAY_SLOTS if wd == 5 else 0)

    def slot_id(eq_idx, d, i):
        return Ids.of("slot", eq_idx * 10_000_000 + (d - slot_start_day).days * 100 + i + 1)

    def slot_times(d, i):
        start = datetime.combine(d, time(8)) + timedelta(minutes=EXAM_MINUTES * i)
        return start, start + timedelta(minutes=EXAM_MINUTES)

    def day_slots(eq_idx, d):
        key = (eq_idx, d)
        if key not in slot_state:
            slot_state[key] = [["AVAILABLE", None, False] for _ in range(slots_per(d))]
        return slot_state[key]

    def is_popular(e, d, i):
        return d.weekday() == 5 or (e["modality"] == "MRI" and i == 0)

    def add_biz(d, k):
        while k:
            d += timedelta(days=1)
            if d.weekday() < 5:
                k -= 1
        return d

    def next_saturday(d):
        return d + timedelta(days=(5 - d.weekday()) % 7 or 7)

    # ------------------------------------------------------------------ referral_db / slot_db 테이블
    ref_cols = ["id", "referring_institution_id", "referrer_id", "exam_type_id", "current_reservation_id",
                "referral_status", "patient_name", "idempotency_key"] + BASE_COLS
    t_ref = out.table("referral_db", "p_referrals", ref_cols)
    t_res = out.table("referral_db", "p_reservations",
                      ["id", "referral_id", "attempt_no", "slot_id", "exam_institution_id", "equipment_id",
                       "exam_date", "start_time", "end_time", "reservation_status", "failure_reason",
                       "confirmed_at", "ended_at"] + BASE_COLS)
    t_ref_h = out.table("referral_db", "p_referral_histories",
                        ["id", "referral_id", "reservation_id", "event_id", "from_status", "to_status", "reason",
                         "occurred_at", "changed_by"] + BASE_COLS)
    t_exam = out.table("referral_db", "p_examinations",
                       ["id", "referral_id", "reservation_id", "exam_institution_id", "status",
                        "started_at", "completed_at", "created_at", "updated_at"])
    t_result = out.table("referral_db", "p_examination_results",
                         ["id", "examination_id", "summary", "notes", "registered_at"])
    t_inotif = out.table("referral_db", "p_institution_notifications",
                         ["id", "institution_id", "referral_id", "reservation_id", "source_event_id",
                          "type", "title", "message", "created_at"])
    t_ref_out = out.table("referral_db", "p_outbox_events",
                          ["id", "aggregate_type", "aggregate_id", "topic", "message_key", "event_type", "payload",
                           "retry_count", "published_at", "created_at", "updated_at"])
    t_ref_in = out.table("referral_db", "p_consumed_events", ["id", "consumer", "event_type", "processed_at"])
    t_slot_h = out.table("slot_db", "p_slot_status_histories",
                         ["id", "slot_id", "reservation_id", "from_status", "to_status", "reason",
                          "source_event_id", "changed_at", "changed_by"])
    t_slot_out = out.table("slot_db", "p_outbox_events",
                           ["id", "aggregate_type", "aggregate_id", "topic", "message_key", "event_type", "payload",
                            "retry_count", "source_occurred_at", "published_at", "created_at", "updated_at"])
    t_slot_in = out.table("slot_db", "p_consumed_events", ["id", "consumer", "event_type", "processed_at"])

    def jitter(dt, lo_ms=30, hi_ms=300):
        return dt + timedelta(milliseconds=rng.randint(lo_ms, hi_ms))

    def ref_command(ev, ev_type, res_id, ref_id, s_id, at):
        payload = json.dumps({"reservationId": res_id, "referralId": ref_id, "slotId": s_id, "eventId": ev,
                              "eventType": ev_type, "occurredAt": at.isoformat()}, separators=(",", ":"))
        out.row(t_ref_out, [ev, "RESERVATION", res_id, "slot-commands", s_id, ev_type, payload, 0,
                            ts(jitter(at, 20, 150)), ts(at), ts(jitter(at, 20, 150))])

    def slot_result(ev, ev_type, s_id, res_id, at, source_at, extra=None):
        body = {"eventId": ev, "eventType": ev_type, "occurredAt": at.isoformat(), "slotId": s_id, "reservationId": res_id}
        if extra:
            body.update(extra)
        payload = json.dumps(body, separators=(",", ":"))
        out.row(t_slot_out, [ev, "SLOT", s_id, "slot-results", s_id, ev_type, payload, 0,
                             ts(source_at), ts(jitter(at, 20, 150)), ts(at), ts(jitter(at, 20, 150))])

    def ref_history(ref_id, res_id, ev, frm, to, reason, at, by):
        out.row(t_ref_h, [ids.next("referral_history"), ref_id, res_id, ev, frm, to, reason, ts(at), by,
                          ts(at), by, ts(at), by])

    def slot_history(s_id, res_id, frm, to, reason, ev, at):
        out.row(t_slot_h, [ids.next("slot_history"), s_id, res_id, frm, to, reason, ev, ts(at), None])

    def patient_name():
        return "부하테스트" + rng.choice(SURNAMES) + rng.choice(GIVEN) + rng.choice(GIVEN)

    exam_names = {v: k for k, v in exam_type_id.items()}
    stats = {"COMPLETED": 0, "CONFIRMED": 0, "CANCELED": 0, "FAILED": 0, "EXPIRED": 0, "FAILED_NO_SLOT": 0}

    def create_referral(u, lab_id, exam, created, target, outcome, fixed_slot=None):
        """의뢰 1건과 saga 이력 전체를 만든다. fixed_slot=(eq, d, i)면 그 슬롯을 예약한다."""
        referrer = user_id[u["user_id"]]
        ref_id, res_id = ids.next("referral"), ids.next("reservation")
        lab_inst = lab_inst_id[lab_id]

        # 슬롯 고르기
        chosen = None
        if fixed_slot:
            chosen = fixed_slot
        elif outcome != "FAILED":
            d = target
            for _ in range(6):
                future = d >= today
                cands, weights = [], []
                for e in eq_by_lab_exam[(lab_id, exam)]:
                    for i, st in enumerate(day_slots(e["idx"], d)):
                        if st[0] != "AVAILABLE" or st[2]:
                            continue
                        popular = is_popular(e, d, i)
                        if future and popular:
                            continue  # 앞으로의 인기 슬롯은 비워 둔다
                        cands.append((e, d, i))
                        weights.append(4 if popular else (1.5 if i < 4 else 1))
                if cands:
                    chosen = rng.choices(cands, weights=weights, k=1)[0]
                    break
                d = add_biz(d, 1)
                if d == today:
                    d = add_biz(d, 1)
            if not chosen:
                outcome = "FAILED"
                stats["FAILED_NO_SLOT"] += 1
        if outcome == "FAILED":
            # 같은 날 다른 의뢰가 이미 잡은 슬롯을 노리다 경합에서 진 경우
            booked = []
            for e in eq_by_lab_exam[(lab_id, exam)]:
                for i, st in enumerate(day_slots(e["idx"], target)):
                    if st[0] == "BOOKED":
                        booked.append((e, target, i))
            if booked:
                chosen = rng.choice(booked)
            else:
                e = rng.choice(eq_by_lab_exam[(lab_id, exam)])
                chosen = (e, target, rng.randrange(slots_per(target)))

        e, d, i = chosen
        s_id = slot_id(e["idx"], d, i)
        start, end = slot_times(d, i)
        future = d >= today
        if outcome in ("COMPLETED", "EXPIRED") and future:
            outcome = "CONFIRMED"
        if outcome == "EXPIRED" and start + timedelta(hours=EXPIRE_AFTER_HOURS + 24) > midnight:
            outcome = "COMPLETED"  # 만료까지 끝나지 않는 최근 건은 완료로 둔다

        # 1) 의뢰 생성 + HoldRequested
        e1 = ids.next("event")
        ref_command(e1, "HoldRequested", res_id, ref_id, s_id, created)
        ref_history(ref_id, res_id, e1, None, "PENDING", "REFERRAL_CREATED", created, referrer)
        t_hold = jitter(created, 80, 400)
        out.row(t_slot_in, [e1, "slot-service", "HoldRequested", ts(t_hold)])

        res_status, failure, confirmed_at, ended_at = None, None, None, None
        ref_status, last_change = None, created
        exam_status, started_at, completed_at = None, None, None

        if outcome == "FAILED":
            e2 = ids.next("event")
            slot_result(e2, "SlotHoldFailed", s_id, res_id, t_hold, created, {"reason": "SLOT_CONFLICT"})
            t_fail = jitter(t_hold, 80, 400)
            out.row(t_ref_in, [e2, "referral-service", "SlotHoldFailed", ts(t_fail)])
            ref_history(ref_id, res_id, e2, "PENDING", "FAILED", "RESERVATION_FAILED", t_fail, None)
            res_status, failure, ended_at, ref_status, last_change = "FAILED", "SLOT_CONFLICT", t_fail, "FAILED", t_fail
            stats["FAILED"] += 1
        else:
            st = day_slots(e["idx"], d)[i]
            # 2) HOLD → SlotHeld
            e2 = ids.next("event")
            slot_history(s_id, res_id, "AVAILABLE", "HELD", "HOLD", e1, t_hold)
            slot_result(e2, "SlotHeld", s_id, res_id, t_hold, created,
                        {"heldUntil": (t_hold + timedelta(minutes=5)).isoformat()})
            t_held = jitter(t_hold, 80, 400)
            out.row(t_ref_in, [e2, "referral-service", "SlotHeld", ts(t_held)])
            # 3) BookSlot → SlotBooked
            e3 = ids.next("event")
            ref_command(e3, "BookSlot", res_id, ref_id, s_id, t_held)
            t_book = jitter(t_held, 80, 400)
            out.row(t_slot_in, [e3, "slot-service", "BookSlot", ts(t_book)])
            slot_history(s_id, res_id, "HELD", "BOOKED", "BOOK", e3, t_book)
            e4 = ids.next("event")
            slot_result(e4, "SlotBooked", s_id, res_id, t_book, t_held)
            confirmed_at = jitter(t_book, 80, 400)
            out.row(t_ref_in, [e4, "referral-service", "SlotBooked", ts(confirmed_at)])
            ref_history(ref_id, res_id, e4, "PENDING", "CONFIRMED", "RESERVATION_CONFIRMED", confirmed_at, None)
            exam_name = exam_names[exam_type_id[exam]]
            out.row(t_inotif, [ids.next("inst_notification"), lab_inst, ref_id, res_id, e4, "RESERVATION_CONFIRMED",
                               "새 예약이 확정되었습니다",
                               f"{d.isoformat()} {start.strftime('%H:%M')} {exam_name} 예약이 확정되었습니다.",
                               ts(confirmed_at)])
            res_status, ref_status, last_change = "CONFIRMED", "CONFIRMED", confirmed_at
            st[0], st[1] = "BOOKED", res_id
            exam_status, exam_created, exam_updated = "SCHEDULED", confirmed_at, confirmed_at

            if outcome == "CANCELED":
                lo = confirmed_at + timedelta(minutes=10)
                hi = min(start - timedelta(hours=2), midnight - timedelta(minutes=1))
                if hi > lo:
                    t_cancel = lo + (hi - lo) * rng.random()
                    e5 = ids.next("event")
                    ref_command(e5, "ReleaseSlot", res_id, ref_id, s_id, t_cancel)
                    ref_history(ref_id, res_id, e5, "CONFIRMED", "CANCELED", "CANCELED_BY_REQUESTER", t_cancel, referrer)
                    out.row(t_inotif, [ids.next("inst_notification"), lab_inst, ref_id, res_id, e5,
                                       "RESERVATION_CANCELED", "예약이 취소되었습니다",
                                       f"{d.isoformat()} {start.strftime('%H:%M')} {exam_name} 예약이 취소되었습니다.",
                                       ts(t_cancel)])
                    t_release = jitter(t_cancel, 80, 400)
                    out.row(t_slot_in, [e5, "slot-service", "ReleaseSlot", ts(t_release)])
                    slot_history(s_id, res_id, "BOOKED", "AVAILABLE", "RELEASE_SLOT", e5, t_release)
                    # 취소로 비은 슬롯은 시각 순서가 꼬이지 않게 다시 배정하지 않는다
                    st[0], st[1], st[2] = "AVAILABLE", None, True
                    res_status, ended_at, ref_status, last_change = "CANCELED", t_cancel, "CANCELED", t_cancel
                    stats["CANCELED"] += 1
                else:
                    outcome = "CONFIRMED" if future else "COMPLETED"

            if outcome == "EXPIRED":
                t_block = start - timedelta(hours=rng.randint(2, 48))
                if t_block <= confirmed_at:
                    t_block = confirmed_at + timedelta(minutes=30)
                e6 = ids.next("event")
                slot_history(s_id, res_id, "BOOKED", "UNAVAILABLE", "DOWNTIME_STARTED", None, t_block)
                slot_result(e6, "SlotsBlocked", s_id, res_id, t_block, t_block, {"reason": "EQUIPMENT_FAILURE"})
                t_blocked = jitter(t_block, 80, 400)
                out.row(t_ref_in, [e6, "referral-service", "SlotsBlocked", ts(t_blocked)])
                ref_history(ref_id, res_id, e6, "CONFIRMED", "NEEDS_RESCHEDULE", "RESERVATION_BLOCKED", t_blocked, None)
                t_expire = t_blocked + timedelta(hours=EXPIRE_AFTER_HOURS)
                ref_history(ref_id, res_id, None, "NEEDS_RESCHEDULE", "EXPIRED", "RESCHEDULE_TIMEOUT", t_expire, None)
                st[0] = "UNAVAILABLE"
                res_status, failure, ended_at = "BLOCKED", "EQUIPMENT_FAILURE", t_blocked
                ref_status, last_change = "EXPIRED", t_expire
                stats["EXPIRED"] += 1

            if outcome == "COMPLETED":
                started_at = start + timedelta(seconds=rng.randint(0, 300))
                completed_at = end - timedelta(seconds=rng.randint(0, 300))
                exam_status, exam_updated = "COMPLETED", completed_at
                stats["COMPLETED"] += 1
            elif outcome == "CONFIRMED":
                stats["CONFIRMED"] += 1

            exam_id = ids.next("examination")
            out.row(t_exam, [exam_id, ref_id, res_id, lab_inst, exam_status, ts(started_at), ts(completed_at),
                             ts(exam_created), ts(exam_updated)])
            if exam_status == "COMPLETED":
                out.row(t_result, [ids.next("result"), exam_id, "특이 소견 없음 (부하테스트 시드)", None,
                                   ts(completed_at + timedelta(seconds=rng.randint(120, 1800)))])

        out.row(t_ref, [ref_id, org_id[u["org_id"]], referrer, exam_type_id[exam], res_id, ref_status,
                        patient_name(), ids.next("idempotency"), ts(created), referrer, ts(last_change), referrer])
        out.row(t_res, [res_id, ref_id, 1, s_id, lab_inst, e["id"], ts(datetime.combine(d, time())), ts(start), ts(end),
                        res_status, failure, ts(confirmed_at), ts(ended_at), ts(created), referrer,
                        ts(last_change if res_status != "CONFIRMED" else confirmed_at), referrer])

    # ------------------------------------------------------------------ 기준일 당일 검사 (today_examinations.csv)
    # 먼저 슬롯을 잡아 두어 일반 이력이 당일 슬롯을 쓰지 않게 한다
    orgs_by_lab = {}
    for p in partnerships:
        orgs_by_lab.setdefault(p["lab_id"], []).append(p["org_id"])
    user_by_org = {u["org_id"]: u for u in users}
    today_plan = []
    for te in today_exams:
        e = eq_by_code[te["equipment_code"]]
        i = (datetime.strptime(te["slot_start"], "%H:%M") - datetime.strptime("08:00", "%H:%M")).seconds // 60 // EXAM_MINUTES
        k = rng.randint(1, 10)
        created_day = today
        while k:
            created_day -= timedelta(days=1)
            if created_day.weekday() < 6:
                k -= 1
        created = datetime.combine(created_day, time(9)) + timedelta(seconds=rng.randrange(8 * 3600))
        u = user_by_org[rng.choice(orgs_by_lab[te["lab_id"]])]
        today_plan.append((created, u, te["lab_id"], te["exam_type"], (e, today, i)))
        day_slots(e["idx"], today)[i][2] = True  # 일반 이력이 쓰지 못하게 표시

    # ------------------------------------------------------------------ 과거 1년 의뢰
    history_days = [history_start + timedelta(days=n) for n in range(days)
                    if (history_start + timedelta(days=n)).weekday() != 6]
    weights = [3 if d.weekday() < 5 else 1 for d in history_days]
    generic_total = total - len(today_plan)
    per_day = [int(generic_total * w / sum(weights)) for w in weights]
    for n in rng.sample(range(len(history_days)), generic_total - sum(per_day)):
        per_day[n] += 1

    outcomes = list(OUTCOME_SHARE)
    plan = list(today_plan)
    for d, cnt in zip(history_days, per_day):
        for _ in range(cnt):
            r = rng.random()
            if d.weekday() == 5:
                sec = rng.randrange(9 * 3600, 13 * 3600) if r >= 0.30 else rng.randrange(9 * 3600, 9 * 3600 + 1800)
            elif r < 0.30:
                sec = rng.randrange(9 * 3600, 9 * 3600 + 1800)
            elif r < 0.55:
                sec = rng.randrange(12 * 3600, 13 * 3600)
            else:
                sec = rng.choice([rng.randrange(9 * 3600 + 1800, 12 * 3600), rng.randrange(13 * 3600, 18 * 3600),
                                  rng.randrange(13 * 3600, 18 * 3600)])
            created = datetime.combine(d, time()) + timedelta(seconds=sec, milliseconds=rng.randrange(1000))
            u = rng.choice(users)
            labs_of = partner_labs[u["org_id"]]
            lab_id = rng.choices(labs_of, weights=[lab_weight[x] for x in labs_of], k=1)[0]
            exam = rng.choice(lab_exam_types[lab_id])
            if rng.random() < SATURDAY_TARGET_SHARE:
                target = next_saturday(d)
            else:
                target = add_biz(d, rng.choices(range(1, 11), weights=range(10, 0, -1), k=1)[0])
            if target == today:
                target = add_biz(target, 1)
            if target > today and target.weekday() == 5:
                target = add_biz(target, 1)  # 앞으로의 토요일은 인기 슬롯이라 비워 둔다
            outcome = rng.choices(outcomes, weights=[OUTCOME_SHARE[o] for o in outcomes], k=1)[0]
            plan.append((created, u, lab_id, exam, target, outcome))

    plan.sort(key=lambda p: p[0])
    for p in plan:
        if len(p) == 5:
            created, u, lab_id, exam, fixed = p
            create_referral(u, lab_id, exam, created, today, "CONFIRMED", fixed_slot=fixed)
        else:
            created, u, lab_id, exam, target, outcome = p
            create_referral(u, lab_id, exam, created, target, outcome)

    # ------------------------------------------------------------------ 슬롯 행 (최종 상태)
    t_slot = out.table("slot_db", "p_slots",
                       ["id", "equipment_id", "exam_type_id", "equipment_downtime_id", "reservation_id", "date",
                        "start_time", "end_time", "status", "held_until"] + BASE_COLS)
    d = slot_start_day
    while d <= slot_end_day:
        n = slots_per(d)
        if n:
            created = datetime.combine(d - timedelta(days=28), time(7))
            for e in equipments:
                states = day_slots(e["idx"], d)
                for i in range(n):
                    s, _ = slot_times(d, i)
                    status, res = states[i][0], states[i][1]
                    out.row(t_slot, [slot_id(e["idx"], d, i), e["id"], exam_type_id[e["exam_type"]], None,
                                     res if status == "BOOKED" else None, d.isoformat(), s.strftime("%H:%M:%S"),
                                     (s + timedelta(minutes=EXAM_MINUTES)).strftime("%H:%M:%S"), status, None,
                                     ts(created), SYSTEM_USER, ts(created), SYSTEM_USER])
        d += timedelta(days=1)

    out.close()

    summary = {
        "seed": seed, "today": today.isoformat(), "slot_ahead_days": ahead_days, "total_referrals": len(plan),
        "outcomes": stats,
        "tables": {f"{db}.{name}": c for (db, name), c in sorted(out.counts.items())},
    }
    (out_root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def kst_today():
    return (datetime.now(timezone.utc) + timedelta(hours=9)).date()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--seed", type=int, default=20261117)
    p.add_argument("--today", type=date.fromisoformat, default=None)
    p.add_argument("--total", type=int, default=240 * 365)
    p.add_argument("--days", type=int, default=365)
    p.add_argument("--ahead-days", type=int, default=SLOT_RANGE_AHEAD_DAYS)
    p.add_argument("--out", default=str(BASE / "seed-out"))
    a = p.parse_args()
    build(a.seed, a.today or kst_today(), a.total, a.days, Path(a.out), a.ahead_days)
