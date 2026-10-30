package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.presentation.dto.request.EquipmentIncidentSearchCondition;
import healoworld.referral_service.presentation.dto.request.IncidentReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentDetailResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentResponse;
import healoworld.referral_service.presentation.dto.response.IncidentReferralResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 테스트 전용 PostgreSQL에서 목록 검색 조건·정렬·페이지, 상세 집계, 영향 의뢰 조회를 검증한다 (조회 전용이라 테스트마다 롤백)
@DataJpaTest
@Import({EquipmentIncidentQueryService.class, QuerydslConfig.class})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EquipmentIncidentQueryServiceIntegrationTest {

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
}
