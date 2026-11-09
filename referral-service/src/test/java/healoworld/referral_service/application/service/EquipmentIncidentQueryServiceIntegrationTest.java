package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationProgress;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.EquipmentIncidentSearchCondition;
import healoworld.referral_service.presentation.dto.request.IncidentReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentDetailResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentSummaryResponse;
import healoworld.referral_service.presentation.dto.response.IncidentReferralResponse;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 테스트 전용 PostgreSQL에서 목록 검색 조건·정렬·페이지, 상세 집계, 영향 의뢰 조회를 검증한다 (조회 전용이라 테스트마다 롤백)
@DataJpaTest
@Import({EquipmentIncidentQueryService.class, QuerydslConfig.class})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EquipmentIncidentQueryServiceIntegrationTest extends PostgresContainerSupport {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 2, 9, 0);

    @Autowired
    private EquipmentIncidentQueryService service;

    @Autowired
    private EquipmentIncidentRepository equipmentIncidentRepository;

    @Autowired
    private IncidentReferralRepository incidentReferralRepository;

    // 테스트마다 새 기관·장비 ID를 써서 다른 테스트나 남은 데이터와 섞이지 않게 한다
    private final UUID myInstitutionId = UUID.randomUUID();
    private final UUID otherInstitutionId = UUID.randomUUID();
    private final UUID equipmentA = UUID.randomUUID();
    private final UUID equipmentB = UUID.randomUUID();

    private EquipmentIncident incident(UUID institutionId, UUID equipmentId, int hoursAfterBase) {
        LocalDateTime occurredAt = BASE.plusHours(hoursAfterBase);
        return equipmentIncidentRepository.saveAndFlush(EquipmentIncident.occur(UUID.randomUUID(), equipmentId,
                institutionId, "가상 장애 사유", occurredAt, occurredAt, occurredAt.plusHours(2)));
    }

    private IncidentReferral task(EquipmentIncident incident) {
        return incidentReferralRepository.saveAndFlush(
                IncidentReferral.pending(incident, UUID.randomUUID(), UUID.randomUUID()));
    }

    private void moveTo(IncidentReferral task, IncidentProcessingStatus status) {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime lockedUntil = now.plusMinutes(5);
        if (status == IncidentProcessingStatus.PENDING) {
            return;
        }
        incidentReferralRepository.claim(task.getId(), now, lockedUntil);
        if (status == IncidentProcessingStatus.SUCCEEDED) {
            incidentReferralRepository.markSucceeded(task.getId(), lockedUntil, "RESCHEDULE_REQUIRED", now);
        } else if (status == IncidentProcessingStatus.FAILED) {
            incidentReferralRepository.markFailed(task.getId(), lockedUntil, "[RETRYABLE] 가상 오류", now, now);
        }
    }

    private EquipmentIncidentSearchCondition search(UUID institutionId, UUID equipmentId, Integer page, Integer size) {
        return new EquipmentIncidentSearchCondition(institutionId, equipmentId, EquipmentIncidentStatus.OCCURRED,
                page, size);
    }

    @Test
    @DisplayName("EXAMINER 목록: 자기 기관 장애만 최신 발생순으로, 페이지 크기만큼 조회된다")
    void examinerListIsScopedSortedAndPaged() {
        EquipmentIncident oldest = incident(myInstitutionId, equipmentA, 1);
        EquipmentIncident middle = incident(myInstitutionId, equipmentB, 2);
        EquipmentIncident newest = incident(myInstitutionId, equipmentA, 3);
        incident(otherInstitutionId, equipmentA, 4);   // 다른 기관 → 보이면 안 됨

        PagedResponse<EquipmentIncidentResponse> firstPage = service.getIncidents(RoleType.EXAMINER, myInstitutionId,
                search(otherInstitutionId, null, 0, 10));

        assertThat(firstPage.contents()).extracting(EquipmentIncidentResponse::incidentId)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
        assertThat(firstPage.totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("목록: 장비 ID로 필터하고, 페이지 번호에 맞는 항목과 전체 건수를 돌려준다")
    void equipmentFilterAndPaging() {
        for (int hour = 0; hour < 12; hour++) {
            incident(myInstitutionId, equipmentA, hour);
        }
        incident(myInstitutionId, equipmentB, 20);

        PagedResponse<EquipmentIncidentResponse> secondPage = service.getIncidents(RoleType.EXAMINER, myInstitutionId,
                search(null, equipmentA, 1, 10));

        assertThat(secondPage.totalElements()).isEqualTo(12);
        assertThat(secondPage.totalPages()).isEqualTo(2);
        assertThat(secondPage.contents()).hasSize(2)
                .allSatisfy(item -> assertThat(item.equipmentId()).isEqualTo(equipmentA));
        assertThat(secondPage.contents()).extracting(EquipmentIncidentResponse::occurredAt)
                .containsExactly(BASE.plusHours(1), BASE);
    }

    @Test
    @DisplayName("ADMIN 목록: 기관 ID로 좁히면 그 기관의 장애만 조회된다")
    void adminFiltersByInstitution() {
        incident(myInstitutionId, equipmentA, 1);
        EquipmentIncident other = incident(otherInstitutionId, equipmentA, 2);

        PagedResponse<EquipmentIncidentResponse> page = service.getIncidents(RoleType.ADMIN, myInstitutionId,
                search(otherInstitutionId, equipmentA, 0, 10));

        assertThat(page.contents()).extracting(EquipmentIncidentResponse::incidentId).containsExactly(other.getId());
    }

    @Test
    @DisplayName("상세: 처리 상태별 작업 수를 실제 DB에서 집계한다")
    void detailCounts() {
        EquipmentIncident incident = incident(myInstitutionId, equipmentA, 1);
        moveTo(task(incident), IncidentProcessingStatus.PENDING);
        moveTo(task(incident), IncidentProcessingStatus.PROCESSING);
        moveTo(task(incident), IncidentProcessingStatus.SUCCEEDED);
        moveTo(task(incident), IncidentProcessingStatus.SUCCEEDED);
        moveTo(task(incident), IncidentProcessingStatus.FAILED);
        task(incident(myInstitutionId, equipmentA, 2));   // 다른 장애의 작업은 세지 않음

        EquipmentIncidentDetailResponse detail = service.getIncident(RoleType.EXAMINER, myInstitutionId,
                incident.getId());

        assertThat(detail.totalReferrals()).isEqualTo(5);
        assertThat(detail.pendingCount()).isEqualTo(1);
        assertThat(detail.processingCount()).isEqualTo(1);
        assertThat(detail.completedCount()).isEqualTo(2);
        assertThat(detail.failedCount()).isEqualTo(1);
        assertThat(detail.startAt()).isEqualTo(BASE.plusHours(1));
        assertThat(detail.endAt()).isEqualTo(BASE.plusHours(3));
        assertThat(detail.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("상세: EXAMINER가 다른 기관의 장애를 조회하면 404")
    void examinerCannotReadOtherInstitution() {
        EquipmentIncident other = incident(otherInstitutionId, equipmentA, 1);

        assertThatThrownBy(() -> service.getIncident(RoleType.EXAMINER, myInstitutionId, other.getId()))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
    }

    @Test
    @DisplayName("영향 의뢰: 상태 필터·페이지가 적용되고 재시도 정보가 담기며, EXAMINER에게는 실패 원인(lastError)이 null이다")
    void referralsWithStatusFilter() {
        EquipmentIncident incident = incident(myInstitutionId, equipmentA, 1);
        IncidentReferral failed = task(incident);
        moveTo(failed, IncidentProcessingStatus.FAILED);
        moveTo(task(incident), IncidentProcessingStatus.SUCCEEDED);
        task(incident);

        PagedResponse<IncidentReferralResponse> all = service.getIncidentReferrals(RoleType.EXAMINER, myInstitutionId,
                incident.getId(), new IncidentReferralSearchCondition(null, 0, 10));
        PagedResponse<IncidentReferralResponse> onlyFailed = service.getIncidentReferrals(RoleType.EXAMINER,
                myInstitutionId, incident.getId(),
                new IncidentReferralSearchCondition(IncidentProcessingStatus.FAILED, 0, 10));

        assertThat(all.totalElements()).isEqualTo(3);
        assertThat(onlyFailed.contents()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(failed.getId());
            assertThat(item.incidentId()).isEqualTo(incident.getId());
            assertThat(item.reservationId()).isEqualTo(failed.getReservationId());
            assertThat(item.referralId()).isEqualTo(failed.getReferralId());
            assertThat(item.processingStatus()).isEqualTo(IncidentProcessingStatus.FAILED);
            assertThat(item.retryCount()).isZero();
            assertThat(item.lastError()).isNull();
            assertThat(item.nextRetryAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("lastError 노출: EXAMINER는 null, ADMIN은 원문을 받고, 조회 후에도 DB의 lastError는 그대로다")
    void lastErrorExposureByRole() {
        EquipmentIncident incident = incident(myInstitutionId, equipmentA, 1);
        IncidentReferral failed = task(incident);
        moveTo(failed, IncidentProcessingStatus.FAILED);
        IncidentReferralSearchCondition onlyFailed = new IncidentReferralSearchCondition(
                IncidentProcessingStatus.FAILED, 0, 10);

        IncidentReferralResponse forExaminer = service.getIncidentReferrals(RoleType.EXAMINER, myInstitutionId,
                incident.getId(), onlyFailed).contents().getFirst();
        IncidentReferralResponse forAdmin = service.getIncidentReferrals(RoleType.ADMIN, otherInstitutionId,
                incident.getId(), onlyFailed).contents().getFirst();

        assertThat(forExaminer.lastError()).isNull();
        assertThat(forAdmin.lastError()).isEqualTo("[RETRYABLE] 가상 오류");
        // lastError 외 처리 현황은 두 역할이 같다
        assertThat(forExaminer).usingRecursiveComparison().ignoringFields("lastError").isEqualTo(forAdmin);
        // 응답에서만 가렸을 뿐 저장된 원본은 바뀌지 않는다
        assertThat(incidentReferralRepository.findById(failed.getId()).orElseThrow().getLastError())
                .isEqualTo("[RETRYABLE] 가상 오류");
    }

    // ===== 현황 집계 (STEP 2·3·4를 같은 기관 범위로 묶은 summary) =====
    // 실제 흐름과 같은 모양의 데이터: 검사기관의 장비 장애 → 그 기관 예약 차단(BLOCKED) → 의뢰 NEEDS_RESCHEDULE → 후속 작업
    // 장애의 institutionId(장비 소유 기관)와 차단 예약의 examInstitutionId(검사기관 스냅샷)는 같은 기관이다

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private Referral referral(ReferralStatus status) {
        Referral referral = Referral.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "가상환자",
                UUID.randomUUID());
        referral.confirm();
        if (status == ReferralStatus.NEEDS_RESCHEDULE) {
            referral.requestReschedule();
        }
        return referralRepository.saveAndFlush(referral);
    }

    // 확정된 예약을 저장한다. blocked면 장비 장애로 차단된 예약(BLOCKED)으로 저장한다
    private Reservation reservation(Referral referral, int attemptNo, UUID examInstitutionId, UUID equipmentId,
                                    boolean blocked) {
        Reservation reservation = Reservation.create(referral, attemptNo, UUID.randomUUID(), examInstitutionId,
                equipmentId, BASE, BASE, BASE.plusHours(1));
        reservation.hold();
        reservation.confirm();
        if (blocked) {
            reservation.block();
        }
        return reservationRepository.saveAndFlush(reservation);
    }

    private void link(EquipmentIncident incident, Referral referral, Reservation reservation) {
        incidentReferralRepository.saveAndFlush(
                IncidentReferral.pending(incident, referral.getId(), reservation.getId()));
    }

    @Test
    @DisplayName("현황: 세 값을 같은 기관 범위로 집계하고 다른 기관 데이터는 섞이지 않는다")
    void summaryUsesSameInstitutionScope() {
        LocalDateTime now = BASE.plusHours(1);   // incident(…, 0)의 중단 구간 BASE ~ BASE+2h 안
        EquipmentIncident mine = incident(myInstitutionId, equipmentA, 0);
        incident(myInstitutionId, equipmentA, -5);                          // 이미 끝난 같은 장비 장애 (중복 장비)
        EquipmentIncident others = incident(otherInstitutionId, equipmentB, 0);

        // R1: 내 기관 장애로 차단, 재조정 대기
        Referral r1 = referral(ReferralStatus.NEEDS_RESCHEDULE);
        link(mine, r1, reservation(r1, 1, myInstitutionId, equipmentA, true));
        // R2: 내 기관 장애의 영향 대상이었지만 의뢰는 확정 상태 (재조정 대상 아님)
        Referral r2 = referral(ReferralStatus.CONFIRMED);
        link(mine, r2, reservation(r2, 1, myInstitutionId, equipmentA, false));
        // R3: 내 기관 장애로 차단 → 다른 기관으로 재예약 → 다른 기관 장애로 다시 차단 (현재 원인은 다른 기관)
        Referral r3 = referral(ReferralStatus.NEEDS_RESCHEDULE);
        link(mine, r3, reservation(r3, 1, myInstitutionId, equipmentA, true));
        link(others, r3, reservation(r3, 2, otherInstitutionId, equipmentB, true));
        // R4: 다른 기관에서만 차단
        Referral r4 = referral(ReferralStatus.NEEDS_RESCHEDULE);
        link(others, r4, reservation(r4, 1, otherInstitutionId, equipmentB, true));

        EquipmentIncidentSummaryResponse mySummary = service.getSummary(RoleType.EXAMINER, myInstitutionId,
                otherInstitutionId, now);
        EquipmentIncidentSummaryResponse otherSummary = service.getSummary(RoleType.ADMIN, myInstitutionId,
                otherInstitutionId, now);

        // 내 기관: 중단 장비 A 1대, 누적 영향 R1·R2·R3, 재조정 R1
        assertThat(mySummary).isEqualTo(new EquipmentIncidentSummaryResponse(myInstitutionId, now, 1, 3, 1));
        // 다른 기관: 중단 장비 B 1대, 누적 영향 R3·R4, 재조정 R3·R4
        assertThat(otherSummary).isEqualTo(new EquipmentIncidentSummaryResponse(otherInstitutionId, now, 1, 2, 2));
    }

    @Test
    @DisplayName("현황: 데이터가 없는 기관은 세 값 모두 0")
    void summaryZeroWithoutData() {
        incident(otherInstitutionId, equipmentB, 0);   // 다른 기관 데이터만 있음

        assertThat(service.getSummary(RoleType.EXAMINER, myInstitutionId, null, BASE.plusHours(1)))
                .isEqualTo(new EquipmentIncidentSummaryResponse(myInstitutionId, BASE.plusHours(1), 0, 0, 0));
    }

    @Test
    @DisplayName("현황: 기준 시각이 중단 종료 시각이면 중단 장비는 0이지만 누적 영향·재조정 수는 그대로다")
    void summaryAfterDowntimeEnds() {
        EquipmentIncident mine = incident(myInstitutionId, equipmentA, 0);
        Referral r1 = referral(ReferralStatus.NEEDS_RESCHEDULE);
        link(mine, r1, reservation(r1, 1, myInstitutionId, equipmentA, true));

        assertThat(service.getSummary(RoleType.EXAMINER, myInstitutionId, null, BASE.plusHours(2)))
                .isEqualTo(new EquipmentIncidentSummaryResponse(myInstitutionId, BASE.plusHours(2), 0, 1, 1));
    }

    // ===== 영향 의뢰: 수동 확인 필요 여부 (검사 일괄 조회 쿼리를 실제 DB로 확인) =====

    @Autowired
    private ExaminationRepository examinationRepository;

    // 자동 후속 처리가 결과 코드와 함께 끝난 작업 (claim → markSucceeded)
    private IncidentReferral succeeded(EquipmentIncident incident, String resultCode) {
        IncidentReferral task = task(incident);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime lockedUntil = now.plusMinutes(5);
        incidentReferralRepository.claim(task.getId(), now, lockedUntil);
        incidentReferralRepository.markSucceeded(task.getId(), lockedUntil, resultCode, now);
        return task;
    }

    // 의뢰의 검사를 reservationId 예약에 연결해 저장하고, inProgress면 검사 시작과 같은 조건부 UPDATE로 시작한다
    private void examination(UUID referralId, UUID reservationId, boolean inProgress) {
        Examination examination = examinationRepository.saveAndFlush(
                Examination.schedule(referralId, reservationId, myInstitutionId));
        if (inProgress) {
            assertThat(examinationRepository.startIfScheduled(examination.getId(), reservationId,
                    LocalDateTime.now())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("수동 확인: 같은 예약의 진행 중 검사만 true, 예약 불일치·검사 없음·SCHEDULED·다른 결과 코드·미처리 작업은 false")
    void manualReviewAgainstRealExaminations() {
        EquipmentIncident incident = incident(myInstitutionId, equipmentA, 0);
        IncidentReferral matched = succeeded(incident, IncidentProcessingService.SKIPPED_IN_PROGRESS);
        IncidentReferral otherReservation = succeeded(incident, IncidentProcessingService.SKIPPED_IN_PROGRESS);
        IncidentReferral noExamination = succeeded(incident, IncidentProcessingService.SKIPPED_IN_PROGRESS);
        IncidentReferral scheduled = succeeded(incident, IncidentProcessingService.SKIPPED_IN_PROGRESS);
        IncidentReferral rescheduled = succeeded(incident, IncidentProcessingService.RESCHEDULE_REQUIRED);
        IncidentReferral pending = task(incident);

        examination(matched.getReferralId(), matched.getReservationId(), true);
        examination(otherReservation.getReferralId(), UUID.randomUUID(), true);   // 다른 예약에 연결된 진행 중 검사
        examination(scheduled.getReferralId(), scheduled.getReservationId(), false);
        examination(rescheduled.getReferralId(), rescheduled.getReservationId(), true);
        examination(pending.getReferralId(), pending.getReservationId(), true);

        Map<UUID, Boolean> byTask = service.getIncidentReferrals(RoleType.EXAMINER, myInstitutionId,
                        incident.getId(), new IncidentReferralSearchCondition(null, 0, 10)).contents().stream()
                .collect(Collectors.toMap(IncidentReferralResponse::id, IncidentReferralResponse::manualReviewRequired));

        assertThat(byTask).hasSize(6)
                .containsEntry(matched.getId(), true)
                .containsEntry(otherReservation.getId(), false)
                .containsEntry(noExamination.getId(), false)
                .containsEntry(scheduled.getId(), false)
                .containsEntry(rescheduled.getId(), false)
                .containsEntry(pending.getId(), false);
    }

    @Test
    @DisplayName("수동 확인: 검사 일괄 조회는 요청한 의뢰의 연결 예약·상태만 돌려준다 (검사가 없는 의뢰는 결과에 없음)")
    void findProgressByReferralIds() {
        UUID inProgressReferral = UUID.randomUUID();
        UUID inProgressReservation = UUID.randomUUID();
        UUID scheduledReferral = UUID.randomUUID();
        UUID scheduledReservation = UUID.randomUUID();
        examination(inProgressReferral, inProgressReservation, true);
        examination(scheduledReferral, scheduledReservation, false);
        examination(UUID.randomUUID(), UUID.randomUUID(), true);   // 요청하지 않은 의뢰

        List<ExaminationProgress> progress = examinationRepository.findProgressByReferralIds(
                List.of(inProgressReferral, scheduledReferral, UUID.randomUUID()));

        assertThat(progress).containsExactlyInAnyOrder(
                new ExaminationProgress(inProgressReferral, inProgressReservation, ExaminationStatus.IN_PROGRESS),
                new ExaminationProgress(scheduledReferral, scheduledReservation, ExaminationStatus.SCHEDULED));
    }
}
