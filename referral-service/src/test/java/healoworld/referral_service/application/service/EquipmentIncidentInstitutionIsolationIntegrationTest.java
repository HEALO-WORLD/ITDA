package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.config.ClockConfig;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.presentation.dto.request.EquipmentIncidentSearchCondition;
import healoworld.referral_service.presentation.dto.request.IncidentReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentSummaryResponse;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 검사기관 A·B 두 기관 데이터를 한 DB에 함께 두고, EXAMINER의 집계·목록·상세·영향 의뢰 조회가 자기 기관으로만 제한되는지 확인한다.
// 기존 EquipmentIncidentQueryServiceIntegrationTest가 다루지 않는 부분만 둔다:
// - 두 기관을 서로 바꿔 가며(A↔B) 상대 기관 ID를 요청에 넣어도 자기 기관 결과만 나오는지
// - 다른 기관 장애의 영향 의뢰 조회가 404인지
// - 목록 조건(장비 필터·페이지·페이지 크기)을 바꿔도 집계가 같은지
// 테스트 전용 PostgreSQL(Testcontainers)에서 실행하고 테스트마다 롤백한다 (개발 DB를 쓰지 않는다)
@DataJpaTest
@Import({EquipmentIncidentQueryService.class, QuerydslConfig.class, ClockConfig.class})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EquipmentIncidentInstitutionIsolationIntegrationTest extends PostgresContainerSupport {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 2, 9, 0);
    // 집계 기준 시각: incident(…, 0)의 중단 구간 BASE ~ BASE+2h 안
    private static final LocalDateTime NOW = BASE.plusHours(1);

    @Autowired
    private EquipmentIncidentQueryService service;

    @Autowired
    private EquipmentIncidentRepository equipmentIncidentRepository;

    @Autowired
    private IncidentReferralRepository incidentReferralRepository;

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    // 테스트마다 새 기관·장비 ID (다른 테스트 데이터와 섞이지 않게)
    private final UUID institutionA = UUID.randomUUID();
    private final UUID institutionB = UUID.randomUUID();
    private final UUID equipmentA1 = UUID.randomUUID();
    private final UUID equipmentA2 = UUID.randomUUID();
    private final UUID equipmentB1 = UUID.randomUUID();

    private EquipmentIncident incidentA1;
    private EquipmentIncident incidentA2;
    private EquipmentIncident endedA1;
    private EquipmentIncident incidentB1;

    /**
     * A: 장애 3건(장비 A1 진행 중·A1 종료·A2 진행 중), 영향 의뢰 3건(재조정 필요 2·확정 1) → 집계 (2대, 3건, 2건)
     * B: 장애 1건(장비 B1 진행 중), 영향 의뢰 1건(재조정 필요) → 집계 (1대, 1건, 1건)
     * 두 기관의 값이 모두 달라서, 다른 기관 데이터가 섞이면 바로 드러난다
     */
    @BeforeEach
    void setUp() {
        incidentA1 = incident(institutionA, equipmentA1, 0);
        endedA1 = incident(institutionA, equipmentA1, -5);   // 같은 장비의 이미 끝난 장애 (중단 장비 수에 다시 세지 않음)
        incidentA2 = incident(institutionA, equipmentA2, 0);
        incidentB1 = incident(institutionB, equipmentB1, 0);

        Referral rA1 = referral(ReferralStatus.NEEDS_RESCHEDULE);
        link(incidentA1, rA1, reservation(rA1, institutionA, equipmentA1, true));
        Referral rA2 = referral(ReferralStatus.CONFIRMED);
        link(incidentA2, rA2, reservation(rA2, institutionA, equipmentA2, false));
        Referral rA3 = referral(ReferralStatus.NEEDS_RESCHEDULE);
        link(incidentA2, rA3, reservation(rA3, institutionA, equipmentA2, true));

        Referral rB1 = referral(ReferralStatus.NEEDS_RESCHEDULE);
        link(incidentB1, rB1, reservation(rB1, institutionB, equipmentB1, true));
    }

    @Test
    @DisplayName("집계: EXAMINER A·B는 각자 기관만 집계하고, 상대 기관 ID를 요청에 넣어도 자기 기관 값이 나온다")
    void summaryIsScopedPerExaminer() {
        EquipmentIncidentSummaryResponse expectedA = new EquipmentIncidentSummaryResponse(institutionA, NOW, 2, 3, 2);
        EquipmentIncidentSummaryResponse expectedB = new EquipmentIncidentSummaryResponse(institutionB, NOW, 1, 1, 1);

        assertThat(service.getSummary(RoleType.EXAMINER, institutionA, null, NOW)).isEqualTo(expectedA);
        assertThat(service.getSummary(RoleType.EXAMINER, institutionB, null, NOW)).isEqualTo(expectedB);
        // 요청 파라미터의 기관 ID는 EXAMINER에게 무시된다
        assertThat(service.getSummary(RoleType.EXAMINER, institutionA, institutionB, NOW)).isEqualTo(expectedA);
        assertThat(service.getSummary(RoleType.EXAMINER, institutionB, institutionA, NOW)).isEqualTo(expectedB);
    }

    @Test
    @DisplayName("집계: REQUESTER는 조회할 수 없다 (403 EQUIPMENT_INCIDENT_ACCESS_DENIED)")
    void summaryDeniedForRequester() {
        assertThatThrownBy(() -> service.getSummary(RoleType.REQUESTER, institutionA, null, NOW))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED);
    }

    @Test
    @DisplayName("목록: 상대 기관 ID를 검색 조건에 넣어도 EXAMINER A·B는 자기 기관 장애만 받는다")
    void listIsScopedPerExaminerEvenWithOtherInstitutionCondition() {
        PagedResponse<EquipmentIncidentResponse> forA = service.getIncidents(RoleType.EXAMINER, institutionA,
                search(institutionB, null, 0, 10));
        PagedResponse<EquipmentIncidentResponse> forB = service.getIncidents(RoleType.EXAMINER, institutionB,
                search(institutionA, null, 0, 10));

        assertThat(forA.contents()).extracting(EquipmentIncidentResponse::incidentId)
                .containsExactlyInAnyOrder(incidentA1.getId(), endedA1.getId(), incidentA2.getId());
        assertThat(forA.contents()).extracting(EquipmentIncidentResponse::institutionId).containsOnly(institutionA);
        assertThat(forA.totalElements()).isEqualTo(3);

        assertThat(forB.contents()).extracting(EquipmentIncidentResponse::incidentId).containsExactly(incidentB1.getId());
        assertThat(forB.contents()).extracting(EquipmentIncidentResponse::institutionId).containsOnly(institutionB);
        assertThat(forB.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("목록: 상대 기관 장비 ID로 필터하면 EXAMINER에게는 빈 결과다 (다른 기관 장애가 섞이지 않는다)")
    void otherInstitutionEquipmentFilterIsEmpty() {
        PagedResponse<EquipmentIncidentResponse> result = service.getIncidents(RoleType.EXAMINER, institutionA,
                search(null, equipmentB1, 0, 10));

        assertThat(result.contents()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    @DisplayName("상세: A↔B 서로 상대 기관 장애를 조회하면 404 EQUIPMENT_INCIDENT_NOT_FOUND")
    void detailOfOtherInstitutionIsNotFound() {
        assertThatThrownBy(() -> service.getIncident(RoleType.EXAMINER, institutionB, incidentA1.getId()))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
        assertThatThrownBy(() -> service.getIncident(RoleType.EXAMINER, institutionA, incidentB1.getId()))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
        // 자기 기관 장애는 그대로 조회된다
        assertThat(service.getIncident(RoleType.EXAMINER, institutionA, incidentA1.getId()).incidentId())
                .isEqualTo(incidentA1.getId());
    }

    @Test
    @DisplayName("영향 의뢰: A↔B 서로 상대 기관 장애의 영향 의뢰를 조회하면 404 EQUIPMENT_INCIDENT_NOT_FOUND")
    void referralsOfOtherInstitutionAreNotFound() {
        IncidentReferralSearchCondition all = new IncidentReferralSearchCondition(null, 0, 10);

        assertThatThrownBy(() -> service.getIncidentReferrals(RoleType.EXAMINER, institutionB, incidentA2.getId(), all))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
        assertThatThrownBy(() -> service.getIncidentReferrals(RoleType.EXAMINER, institutionA, incidentB1.getId(), all))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
        // 자기 기관 장애의 영향 의뢰는 그대로 조회된다 (incidentA2: rA2·rA3)
        assertThat(service.getIncidentReferrals(RoleType.EXAMINER, institutionA, incidentA2.getId(), all).totalElements())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("집계·목록 분리: 장비 필터·페이지·페이지 크기로 목록이 바뀌어도 집계는 기관 전체 기준 그대로다")
    void summaryIsIndependentOfListConditions() {
        EquipmentIncidentSummaryResponse before = service.getSummary(RoleType.EXAMINER, institutionA, null, NOW);

        List<PagedResponse<EquipmentIncidentResponse>> lists = List.of(
                service.getIncidents(RoleType.EXAMINER, institutionA, search(null, null, 0, 10)),        // 전체
                service.getIncidents(RoleType.EXAMINER, institutionA, search(null, equipmentA1, 0, 10)), // 장비 필터
                service.getIncidents(RoleType.EXAMINER, institutionA, search(null, null, 1, 10)),        // 페이지 이동
                service.getIncidents(RoleType.EXAMINER, institutionA, search(null, null, 0, 30)));       // 페이지 크기

        // 목록은 조건에 따라 달라진다
        assertThat(lists.get(0).totalElements()).isEqualTo(3);
        assertThat(lists.get(1).totalElements()).isEqualTo(2);
        assertThat(lists.get(1).contents()).extracting(EquipmentIncidentResponse::equipmentId).containsOnly(equipmentA1);
        assertThat(lists.get(2).contents()).isEmpty();
        assertThat(lists.get(2).totalElements()).isEqualTo(3);
        assertThat(lists.get(3).size()).isEqualTo(30);

        // 집계는 목록 조건과 무관 (같은 기준 시각이면 같은 값), 목록 건수(3)와도 다른 값이다
        EquipmentIncidentSummaryResponse after = service.getSummary(RoleType.EXAMINER, institutionA, null, NOW);
        assertThat(after).isEqualTo(before);
        assertThat(after).isEqualTo(new EquipmentIncidentSummaryResponse(institutionA, NOW, 2, 3, 2));
    }

    private EquipmentIncident incident(UUID institutionId, UUID equipmentId, int hoursAfterBase) {
        LocalDateTime occurredAt = BASE.plusHours(hoursAfterBase);
        return equipmentIncidentRepository.saveAndFlush(EquipmentIncident.occur(UUID.randomUUID(), equipmentId,
                institutionId, "가상 장애 사유", occurredAt, occurredAt, occurredAt.plusHours(2)));
    }

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
    private Reservation reservation(Referral referral, UUID examInstitutionId, UUID equipmentId, boolean blocked) {
        Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), examInstitutionId,
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

    private EquipmentIncidentSearchCondition search(UUID institutionId, UUID equipmentId, Integer page, Integer size) {
        return new EquipmentIncidentSearchCondition(institutionId, equipmentId, EquipmentIncidentStatus.OCCURRED,
                page, size);
    }
}
