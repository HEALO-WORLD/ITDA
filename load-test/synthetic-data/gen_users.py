"""
부하 테스트용 가상 사용자 CSV 생성기 ('확대' 규모: 의뢰기관 80곳 x 하루 3건, 검사기관 10곳)

사용법:
    python gen_users.py              # 기본 시드(20261117)로 data/ 아래에 생성
    python gen_users.py --seed 7     # 다른 시드로 생성 (같은 시드면 항상 같은 결과)

생성 파일 (data/):
    referrer_users.csv       원무직원 80명 (기관당 1명)
    referrer_sessions.csv    원무직원 의뢰 세션 240건 (접속 시각, 행동 패턴, 목표 슬롯)
    labs.csv                 검사기관 10곳 (검사 종류, 장비 구성)
    equipments.csv           장비 목록 (장비 한 대 = 검사 종류 하나 전담, CT·MRI만)
    lab_managers.csv         검사관리자 10명 (접속 시간, 새로고침 주기, 처리 방식)
    partnerships.csv         의뢰기관-검사기관 협력 관계
    today_examinations.csv   검사기관별 오늘 검사 예정 24건 (검사관리자가 시작·완료·결과 등록할 대상)
"""
import argparse
import csv
import random
from datetime import datetime, timedelta
from pathlib import Path

REFERRER_COUNT = 80
SESSIONS_PER_REFERRER = 3
TODAY_EXAMS_PER_LAB = 24
LOGIN_PREFIX = "loadtest_"

# 시간 분포: 오픈 30분 30%, 점심 25%, 나머지 근무 시간 45%
WINDOW_SHARE = {"open_peak": 0.30, "lunch": 0.25, "normal": 0.45}
OPEN_PEAK_FIRST_2MIN_SHARE = 0.40  # 오픈 30분 중 09:00~09:02에 몰리는 비율 (인기 슬롯 확보 경쟁)

# 행동 패턴 비율 (세션 단위)
PATTERN_SHARE = {"normal": 0.55, "search_retry": 0.20, "double_click": 0.10, "status_poll": 0.15}
# 후속 행동 비율: 취소 5%, 재예약 10%
POST_ACTION_SHARE = {"none": 0.85, "cancel": 0.05, "rebook": 0.10}
# 인기 슬롯을 노리는 비율 (시간대별)
POPULAR_SHARE = {"open_peak": 0.50, "lunch": 0.10, "normal": 0.10}

MIN_SESSION_GAP_MIN = 10  # 같은 사용자의 세션 사이 최소 간격

SURNAMES = list("김이박최정강조윤장임한오서신권황안송류전홍고문양손배백허유남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용")
GIVEN_SYLLABLES = list("민서준지현우도윤하은수연영진호성재가경나다라미보소아유주채태희승혜원정훈동석")

CLINIC_PREFIXES = ["햇살", "푸른숲", "새봄", "한결", "다온", "늘빛", "소나무", "바른길", "온누리", "맑은샘",
                   "하늘빛", "고운", "참좋은", "든든", "밝은미소", "사랑채", "꿈나무", "도담", "가온", "누리봄"]
CLINIC_SPECIALTIES = ["내과의원", "가정의학과의원", "소아청소년과의원", "이비인후과의원"]

# 검사 종류 (장비 종류 Modality는 CT·MRI만 있다)
EXAM_TYPES = {"뇌 MRI": "MRI", "척추 MRI": "MRI", "흉부 CT": "CT", "복부 CT": "CT"}

# 검사기관: (이름, 장비 구성 {검사 종류: 장비 수}, 목표 비중)
# 장비 한 대가 검사 종류 하나를 전담한다 (같은 장비에 시간이 겹치는 슬롯을 만들 수 없음)
# 대형 영상센터 두 곳(LAB01, LAB02)에 의뢰가 몰리도록 비중과 장비 수를 높게 둔다
LABS = [
    ("가람영상의학센터", {"뇌 MRI": 1, "척추 MRI": 1, "흉부 CT": 2, "복부 CT": 1}, 0.18),
    ("한빛MRI센터", {"뇌 MRI": 2, "척추 MRI": 2}, 0.15),
    ("새솔영상의학과", {"흉부 CT": 1, "복부 CT": 1}, 0.08),
    ("늘품영상검진센터", {"흉부 CT": 1, "복부 CT": 1}, 0.09),
    ("다솜영상의학과", {"뇌 MRI": 1, "흉부 CT": 1}, 0.09),
    ("온빛영상센터", {"척추 MRI": 1, "복부 CT": 1}, 0.08),
    ("푸른영상의학과", {"뇌 MRI": 1, "복부 CT": 1}, 0.08),
    ("미르영상의학과", {"척추 MRI": 1, "흉부 CT": 1}, 0.09),
    ("새봄검진센터", {"흉부 CT": 1, "복부 CT": 1}, 0.08),
    ("바른영상센터", {"뇌 MRI": 1, "흉부 CT": 1}, 0.08),
]

WEEKDAY_OPEN, WEEKDAY_CLOSE = "08:00", "18:00"
SATURDAY_OPEN, SATURDAY_CLOSE = "08:00", "12:00"
SLOT_MINUTES = 30


def t(hhmm_ss: str) -> datetime:
    fmt = "%H:%M:%S" if hhmm_ss.count(":") == 2 else "%H:%M"
    return datetime.strptime(hhmm_ss, fmt)


def fmt(dt: datetime) -> str:
    return dt.strftime("%H:%M:%S")


def weighted_choice(rng: random.Random, share: dict):
    keys = list(share)
    return rng.choices(keys, weights=[share[k] for k in keys], k=1)[0]


def exact_allocation(share: dict, total: int) -> list:
    """비율을 총 건수에 맞게 정수로 나눈다 (반올림 오차는 큰 비율 항목에서 조정)."""
    counts = {k: int(round(v * total)) for k, v in share.items()}
    diff = total - sum(counts.values())
    counts[max(share, key=share.get)] += diff
    result = []
    for k, c in counts.items():
        result += [k] * c
    return result


def fake_names(rng: random.Random, n: int) -> list:
    names = set()
    while len(names) < n:
        names.add(rng.choice(SURNAMES) + rng.choice(GIVEN_SYLLABLES) + rng.choice(GIVEN_SYLLABLES))
    names = sorted(names)  # set 순서는 실행마다 달라지므로 정렬 후 섞어야 시드가 같을 때 결과가 같다
    rng.shuffle(names)
    return names


def random_time(rng: random.Random, start: str, end: str) -> datetime:
    s, e = t(start), t(end)
    return s + timedelta(seconds=rng.randrange(int((e - s).total_seconds())))


def session_start_times(rng: random.Random, total: int) -> list:
    windows = exact_allocation(WINDOW_SHARE, total)
    times = []
    for w in windows:
        if w == "open_peak":
            if rng.random() < OPEN_PEAK_FIRST_2MIN_SHARE:
                times.append((w, random_time(rng, "09:00:00", "09:02:00")))
            else:
                times.append((w, random_time(rng, "09:02:00", "09:30:00")))
        elif w == "lunch":
            times.append((w, random_time(rng, "12:00:00", "13:00:00")))
        else:
            # 09:30~12:00(150분), 13:00~18:00(300분)을 길이 비례로 고른다
            if rng.random() < 150 / 450:
                times.append((w, random_time(rng, "09:30:00", "12:00:00")))
            else:
                times.append((w, random_time(rng, "13:00:00", "18:00:00")))
    return times


def assign_sessions_to_users(rng: random.Random, times: list, user_ids: list) -> dict:
    """사용자마다 3건씩, 같은 사용자의 세션이 MIN_SESSION_GAP_MIN 이내로 겹치지 않게 배정한다."""
    for _ in range(200):
        rng.shuffle(times)
        assigned = {u: [] for u in user_ids}
        ok = True
        for w, st in times:
            candidates = [u for u in user_ids
                          if len(assigned[u]) < SESSIONS_PER_REFERRER
                          and all(abs((st - o).total_seconds()) >= MIN_SESSION_GAP_MIN * 60 for _, o in assigned[u])]
            if not candidates:
                ok = False
                break
            # 남은 칸이 많은 사용자부터 채워 마지막에 막히지 않게 한다
            max_left = max(SESSIONS_PER_REFERRER - len(assigned[u]) for u in candidates)
            u = rng.choice([c for c in candidates if SESSIONS_PER_REFERRER - len(assigned[c]) == max_left])
            assigned[u].append((w, st))
        if ok:
            return assigned
    raise RuntimeError("세션 배정 실패: 시드를 바꿔 다시 실행하세요")


def pick_target(rng: random.Random, window: str, lab_ids: list):
    weights = [LABS[i][2] for i in range(len(LABS))]
    popular = rng.random() < POPULAR_SHARE[window]
    if popular:
        # 인기 슬롯: 평일 MRI 첫 타임(08:00) 또는 토요일 오전 슬롯
        if rng.random() < 0.6:
            mri_labs = [i for i in range(len(LABS)) if any(EXAM_TYPES[e] == "MRI" for e in LABS[i][1])]
            idx = rng.choices(mri_labs, weights=[weights[i] for i in mri_labs], k=1)[0]
            exam = rng.choice([e for e in LABS[idx][1] if EXAM_TYPES[e] == "MRI"])
            return idx, exam, f"BIZ+{rng.randint(1, 5)}", "08:00", "popular", "MRI 오전 첫 타임"
        idx = rng.choices(range(len(LABS)), weights=weights, k=1)[0]
        exam = rng.choice(list(LABS[idx][1]))
        slot = rng.choice(["08:00", "08:30", "09:00"])
        return idx, exam, "NEXT_SAT", slot, "popular", "토요일 오전 슬롯"
    idx = rng.choices(range(len(LABS)), weights=weights, k=1)[0]
    exam = rng.choice(list(LABS[idx][1]))
    # 취소는 검사 2시간 전까지만 가능하므로 목표일은 다음 영업일 이후로 둔다
    # BIZ+n: 실행일 기준 n번째 평일(월~금). 일반 슬롯 시간(~17:30)은 토요일에 없으므로 평일만 쓴다
    day = f"BIZ+{rng.randint(1, 10)}"
    slot_start = t(WEEKDAY_OPEN) + timedelta(minutes=SLOT_MINUTES * rng.randrange(
        int((t(WEEKDAY_CLOSE) - t(WEEKDAY_OPEN)).total_seconds() // 60 // SLOT_MINUTES)))
    return idx, exam, day, slot_start.strftime("%H:%M"), "normal", "일반 슬롯"


PATTERN_DESC = {
    "normal": "정상 흐름",
    "search_retry": "원하는 날짜가 없어 슬롯 검색을 여러 번 반복",
    "double_click": "예약 버튼을 두 번 클릭 (같은 멱등 키로 의뢰 생성 2회 전송)",
    "status_poll": "확정이 늦어 상태 조회를 반복",
}
POST_DESC = {
    "none": "",
    "cancel": "확정 후 일정이 맞지 않아 취소",
    "rebook": "확정 실패 시 다른 슬롯으로 새 의뢰 생성",
}


def build(seed: int, out_dir: Path):
    rng = random.Random(seed)
    out_dir.mkdir(parents=True, exist_ok=True)

    # ---- 검사기관 ----
    lab_ids = [f"LAB{i + 1:02d}" for i in range(len(LABS))]
    lab_rows = []
    equipment_rows = []
    for lab_id, (name, equip, weight) in zip(lab_ids, LABS):
        lab_rows.append({
            "lab_id": lab_id, "lab_name": name,
            "exam_types": "/".join(equip),
            "equipments": ";".join(f"{k}:{v}" for k, v in equip.items()),
            "weekday_hours": f"{WEEKDAY_OPEN}-{WEEKDAY_CLOSE}",
            "saturday_hours": f"{SATURDAY_OPEN}-{SATURDAY_CLOSE}",
            "slot_minutes": SLOT_MINUTES,
            "target_weight": weight,
        })
        seq = {}
        for exam, cnt in equip.items():
            modality = EXAM_TYPES[exam]
            for _ in range(cnt):
                seq[modality] = seq.get(modality, 0) + 1
                equipment_rows.append({
                    "equipment_code": f"{lab_id}-{modality}-{seq[modality]}",
                    "lab_id": lab_id, "lab_name": name,
                    "equipment_name": f"{modality}-{seq[modality]} ({exam})",
                    "modality": modality, "exam_type": exam,
                })

    # ---- 원무직원 ----
    names = fake_names(rng, REFERRER_COUNT + len(LABS))
    ref_names, lab_mgr_names = names[:REFERRER_COUNT], names[REFERRER_COUNT:]
    org_names = [p + s for p in CLINIC_PREFIXES for s in CLINIC_SPECIALTIES]
    rng.shuffle(org_names)
    org_names = org_names[:REFERRER_COUNT]

    users = []
    for i in range(REFERRER_COUNT):
        users.append({
            "user_id": f"REF{i + 1:03d}",
            "login_id": f"{LOGIN_PREFIX}ref_{i + 1:03d}",
            "name": ref_names[i],
            "org_id": f"ORG{i + 1:03d}",
            "org_name": org_names[i],
        })
    user_by_id = {u["user_id"]: u for u in users}

    # ---- 세션 ----
    total_sessions = REFERRER_COUNT * SESSIONS_PER_REFERRER
    times = session_start_times(rng, total_sessions)
    assigned = assign_sessions_to_users(rng, times, [u["user_id"] for u in users])

    patterns = exact_allocation(PATTERN_SHARE, total_sessions)
    post_actions = exact_allocation(POST_ACTION_SHARE, total_sessions)
    rng.shuffle(patterns)
    rng.shuffle(post_actions)

    flat = []
    for uid, lst in assigned.items():
        lst.sort(key=lambda x: x[1])
        for seq, (w, st) in enumerate(lst, start=1):
            flat.append((uid, seq, w, st))
    flat.sort(key=lambda x: x[3])

    partner_need = {u["user_id"]: set() for u in users}
    sessions = []
    last_auth = {}
    for n, (uid, seq, w, st) in enumerate(flat):
        u = user_by_id[uid]
        pattern, post = patterns[n], post_actions[n]
        lab_idx, exam, day, slot_time, slot_type, slot_label = pick_target(rng, w, lab_ids)
        partner_need[uid].add(lab_idx)

        if seq == 1:
            auth = "login"
            last_auth[uid] = st
        elif (st - last_auth[uid]).total_seconds() >= 55 * 60:  # access token 1시간, 만료 직전 재발급
            auth = "reissue"
            last_auth[uid] = st
        else:
            auth = "reuse"

        search_count = rng.randint(2, 4) if pattern == "search_retry" else 1
        create_count = 2 if pattern == "double_click" else 1
        status_count = rng.randint(4, 6) if pattern == "status_poll" else 2
        post_delay = rng.randint(60, 600) if post == "cancel" else (rng.randint(5, 30) if post == "rebook" else 0)

        # 기본 5회: 협력기관 1 + 검색 1 + 생성 1 + 상태 조회 2
        calls = (1 if auth != "reuse" else 0) + 1 + search_count + create_count + status_count
        if post == "cancel":
            calls += 1
        elif post == "rebook":  # 실패했을 때만 실행: 검색 1 + 생성 1 + 상태 조회 2 (최대치)
            calls += 4

        desc = PATTERN_DESC[pattern] + (f", {POST_DESC[post]}" if post != "none" else "")
        sessions.append({
            "session_id": f"S{n + 1:04d}",
            "user_id": uid, "login_id": u["login_id"], "name": u["name"],
            "org_id": u["org_id"], "org_name": u["org_name"],
            "session_seq": seq,
            "start_time": fmt(st),
            "start_offset_sec": int((st - t("00:00:00")).total_seconds()),
            "time_window": w,
            "auth_action": auth,
            "behavior_pattern": pattern,
            "behavior_desc": desc,
            "target_lab_id": lab_ids[lab_idx], "target_lab_name": LABS[lab_idx][0],
            "exam_type": exam,
            "target_day_rule": day,
            "target_slot_time": slot_time,
            "slot_type": slot_type, "slot_label": slot_label,
            "search_count": search_count,
            "create_count": create_count,
            "status_check_count": status_count,
            "post_action": post,
            "post_action_delay_sec": post_delay,
            "think_time_sec": rng.randint(1, 3) if w == "open_peak" else rng.randint(2, 8),
            "expected_api_calls": calls,
        })

    # ---- 협력 관계: 세션에서 쓰는 검사기관은 반드시 포함하고, 기관당 4~6곳까지 채운다 ----
    partnerships = []
    for u in users:
        labs_for_user = set(partner_need[u["user_id"]])
        target = rng.randint(4, 6)
        pool = [i for i in range(len(LABS)) if i not in labs_for_user]
        rng.shuffle(pool)
        while len(labs_for_user) < target and pool:
            labs_for_user.add(pool.pop())
        for i in sorted(labs_for_user):
            partnerships.append({
                "org_id": u["org_id"], "org_name": u["org_name"],
                "referrer_login_id": u["login_id"],
                "lab_id": lab_ids[i], "lab_name": LABS[i][0],
            })

    # ---- 검사관리자 ----
    managers = []
    lab_styles = ["immediate", "batch"] * (len(LABS) // 2)
    lunch_styles = ["pause", "keep_polling"] * (len(LABS) // 2)
    rng.shuffle(lab_styles)
    rng.shuffle(lunch_styles)
    for i, lab_id in enumerate(lab_ids):
        # 검사기관은 08:00부터 검사하므로 그 전에 접속한다
        login = random_time(rng, "07:40:00", "07:58:00")
        logout = random_time(rng, "18:00:00", "18:30:00")
        poll, jitter = 30, 5
        active_sec = (logout - login).total_seconds()
        if lunch_styles[i] == "pause":
            active_sec -= 3600
        expected_polls = int(active_sec // poll)
        reissues = int((logout - login).total_seconds() // (55 * 60))
        process_calls = TODAY_EXAMS_PER_LAB * 3  # 시작 + 완료 + 결과 등록
        managers.append({
            "user_id": lab_id,
            "login_id": f"{LOGIN_PREFIX}lab_{i + 1:02d}",
            "name": lab_mgr_names[i],
            "lab_id": lab_id, "lab_name": LABS[i][0],
            "login_time": fmt(login), "logout_time": fmt(logout),
            "poll_interval_sec": poll, "poll_jitter_sec": jitter,
            "lunch_behavior": lunch_styles[i],
            "processing_style": lab_styles[i],
            "batch_size": rng.randint(4, 8) if lab_styles[i] == "batch" else 0,
            "today_exam_count": TODAY_EXAMS_PER_LAB,
            "expected_poll_calls": expected_polls,
            "expected_reissue_calls": reissues,
            "expected_process_calls": process_calls,
            "expected_api_calls": 1 + expected_polls + reissues + process_calls,
        })
    mgr_by_lab = {m["lab_id"]: m for m in managers}

    # ---- 오늘 검사 예정 (검사기관당 24건, 장비별 30분 슬롯이 겹치지 않게) ----
    today = []
    slots_per_day = int((t(WEEKDAY_CLOSE) - t(WEEKDAY_OPEN)).total_seconds() // 60 // SLOT_MINUTES)
    for i, lab_id in enumerate(lab_ids):
        equip_slots = []
        for eq in equipment_rows:
            if eq["lab_id"] != lab_id:
                continue
            for s in range(slots_per_day):
                equip_slots.append((eq["exam_type"], eq["equipment_code"], s))
        chosen = sorted(rng.sample(equip_slots, TODAY_EXAMS_PER_LAB), key=lambda x: (x[2], x[1]))
        m = mgr_by_lab[lab_id]
        pending_results = []
        rows = []
        for k, (exam, equip_code, s) in enumerate(chosen, start=1):
            slot_start = t(WEEKDAY_OPEN) + timedelta(minutes=SLOT_MINUTES * s)
            slot_end = slot_start + timedelta(minutes=SLOT_MINUTES)
            start_at = slot_start + timedelta(seconds=rng.randint(0, 300))
            complete_at = slot_end - timedelta(seconds=rng.randint(0, 300))
            rows.append({
                "exam_seq": f"{lab_id}-E{k:02d}",
                "lab_id": lab_id, "lab_name": LABS[i][0],
                "equipment_code": equip_code, "exam_type": exam,
                "slot_start": slot_start.strftime("%H:%M"), "slot_end": slot_end.strftime("%H:%M"),
                "start_at": fmt(start_at), "complete_at": fmt(complete_at),
                "result_at": "",
            })
            pending_results.append(rows[-1])
        # 결과 등록: 즉시형은 완료 2~10분 뒤, 일괄형은 batch_size건이 모이면 마지막 완료 직후 한꺼번에
        if m["processing_style"] == "immediate":
            for r in rows:
                r["result_at"] = fmt(t(r["complete_at"]) + timedelta(seconds=rng.randint(120, 600)))
        else:
            bs = m["batch_size"]
            ordered = sorted(rows, key=lambda r: r["complete_at"])
            for b in range(0, len(ordered), bs):
                group = ordered[b:b + bs]
                at = max(t(r["complete_at"]) for r in group) + timedelta(seconds=rng.randint(60, 300))
                for j, r in enumerate(group):
                    r["result_at"] = fmt(at + timedelta(seconds=20 * j))
        today += rows

    write_csv(out_dir / "labs.csv", lab_rows)
    write_csv(out_dir / "equipments.csv", equipment_rows)
    write_csv(out_dir / "referrer_users.csv", users)
    write_csv(out_dir / "referrer_sessions.csv", sessions)
    write_csv(out_dir / "partnerships.csv", partnerships)
    write_csv(out_dir / "lab_managers.csv", managers)
    write_csv(out_dir / "today_examinations.csv", today)
    print_summary(sessions, partnerships, managers, today)


def write_csv(path: Path, rows: list):
    # Excel에서 한글이 깨지지 않게 BOM을 붙인다
    with path.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def print_summary(sessions, partnerships, managers, today):
    def count(key):
        c = {}
        for s in sessions:
            c[s[key]] = c.get(s[key], 0) + 1
        return c

    ref_calls = sum(s["expected_api_calls"] for s in sessions)
    lab_calls = sum(m["expected_api_calls"] for m in managers)
    by_window = {}
    for s in sessions:
        by_window[s["time_window"]] = by_window.get(s["time_window"], 0) + s["expected_api_calls"]
    first2 = sum(1 for s in sessions if "09:00:00" <= s["start_time"] < "09:02:00")
    print(f"세션 {len(sessions)}건 | 시간대 {count('time_window')} | 09:00~09:02 {first2}건")
    print(f"행동 패턴 {count('behavior_pattern')}")
    print(f"후속 행동 {count('post_action')} | 슬롯 유형 {count('slot_type')}")
    print(f"검사기관 분포 {dict(sorted(count('target_lab_id').items()))}")
    print(f"협력 관계 {len(partnerships)}건 | 오늘 검사 예정 {len(today)}건")
    print(f"예상 API 호출: 원무직원 {ref_calls}회 {by_window} / 검사관리자 {lab_calls}회 / 합계 {ref_calls + lab_calls}회")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--seed", type=int, default=20261117)
    p.add_argument("--out", default=str(Path(__file__).parent / "data"))
    a = p.parse_args()
    build(a.seed, Path(a.out))
