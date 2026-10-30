package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.presentation.dto.request.EquipmentIncidentSearchCondition;
import healoworld.referral_service.presentation.dto.request.IncidentReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentDetailResponse;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// 역할별 조회 범위(권한)와 응답 집계를 Mock으로 검증한다
@ExtendWith(MockitoExtension.class)
class EquipmentIncidentQueryServiceTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 2, 14, 0);

    @Mock
    private EquipmentIncidentRepository equipmentIncidentRepository;

    @Mock
    private IncidentReferralRepository incidentReferralRepository;

    @InjectMocks
    private EquipmentIncidentQueryService service;

    private final UUID myInstitutionId = UUID.randomUUID();
    private final UUID otherInstitutionId = UUID.randomUUID();
    private final UUID equipmentId = UUID.randomUUID();

    private static void assertErrorCode(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private EquipmentIncident incidentOf(UUID institutionId) {
        EquipmentIncident incident = EquipmentIncident.occur(UUID.randomUUID(), equipmentId, institutionId,
                "가상 장애 사유", OCCURRED_AT, OCCURRED_AT, OCCURRED_AT.plusHours(3));
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        given(equipmentIncidentRepository.findById(incident.getId())).willReturn(Optional.of(incident));
        return incident;
    }

    private EquipmentIncidentSearchCondition searchFor(UUID institutionId) {
        return new EquipmentIncidentSearchCondition(institutionId, equipmentId, EquipmentIncidentStatus.OCCURRED, 0, 10);
    }

    private void givenSearchReturnsEmpty() {
        given(equipmentIncidentRepository.search(any(), any(), any(), any())).willReturn(Page.empty());
    }

    // ===== 목록 =====

    @Test
    @DisplayName("ADMIN 목록은 요청한 institutionId·equipmentId·status로 조회한다 (기관 미지정이면 전체)")
    void adminListUsesRequestedInstitution() {
        givenSearchReturnsEmpty();

        service.getIncidents(RoleType.ADMIN, myInstitutionId, searchFor(otherInstitutionId));
        service.getIncidents(RoleType.ADMIN, myInstitutionId, searchFor(null));

        then(equipmentIncidentRepository).should().search(eq(otherInstitutionId), eq(equipmentId),
                eq(EquipmentIncidentStatus.OCCURRED), any());
        then(equipmentIncidentRepository).should().search(eq(null), eq(equipmentId),
                eq(EquipmentIncidentStatus.OCCURRED), any());
    }

    @Test
    @DisplayName("EXAMINER 목록은 요청 파라미터의 기관을 무시하고 자기 기관으로만 조회한다")
    void examinerListIsScopedToOwnInstitution() {
        givenSearchReturnsEmpty();

        service.getIncidents(RoleType.EXAMINER, myInstitutionId, searchFor(otherInstitutionId));

        then(equipmentIncidentRepository).should().search(eq(myInstitutionId), eq(equipmentId),
                eq(EquipmentIncidentStatus.OCCURRED), any());
    }

    @Test
    @DisplayName("REQUESTER는 목록을 조회할 수 없다 (403, DB 조회 없음)")
    void requesterCannotList() {
        assertErrorCode(() -> service.getIncidents(RoleType.REQUESTER, myInstitutionId, searchFor(null)),
                ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED);
        then(equipmentIncidentRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("목록 항목은 Entity가 아닌 응답 DTO로 변환된다")
    void listIsMappedToResponse() {
        EquipmentIncident incident = EquipmentIncident.occur(UUID.randomUUID(), equipmentId, myInstitutionId,
                "가상 장애 사유", OCCURRED_AT, OCCURRED_AT, OCCURRED_AT.plusHours(3));
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        given(equipmentIncidentRepository.search(any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(incident), PageRequest.of(0, 10), 1));

        var response = service.getIncidents(RoleType.EXAMINER, myInstitutionId, searchFor(null));

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.contents()).singleElement().satisfies(item -> {
            assertThat(item.incidentId()).isEqualTo(incident.getId());
            assertThat(item.institutionId()).isEqualTo(myInstitutionId);
            assertThat(item.startAt()).isEqualTo(OCCURRED_AT);
            assertThat(item.endAt()).isEqualTo(OCCURRED_AT.plusHours(3));
        });
    }

    // ===== 상세 =====

    @Test
    @DisplayName("상세: 상태별 작업 수를 모아 응답하고 total은 네 상태의 합이다 (completedCount = SUCCEEDED 수)")
    void detailAggregatesCounts() {
        EquipmentIncident incident = incidentOf(myInstitutionId);
        UUID id = incident.getId();
        given(incidentReferralRepository.countByIncident_IdAndProcessingStatus(id, IncidentProcessingStatus.PENDING))
                .willReturn(1L);
        given(incidentReferralRepository.countByIncident_IdAndProcessingStatus(id, IncidentProcessingStatus.PROCESSING))
                .willReturn(2L);
        given(incidentReferralRepository.countByIncident_IdAndProcessingStatus(id, IncidentProcessingStatus.SUCCEEDED))
                .willReturn(3L);
        given(incidentReferralRepository.countByIncident_IdAndProcessingStatus(id, IncidentProcessingStatus.FAILED))
                .willReturn(4L);

        EquipmentIncidentDetailResponse response = service.getIncident(RoleType.EXAMINER, myInstitutionId, id);

        assertThat(response.incidentId()).isEqualTo(id);
        assertThat(response.pendingCount()).isEqualTo(1);
        assertThat(response.processingCount()).isEqualTo(2);
        assertThat(response.completedCount()).isEqualTo(3);
        assertThat(response.failedCount()).isEqualTo(4);
        assertThat(response.totalReferrals()).isEqualTo(10);
    }

    @Test
    @DisplayName("상세: ADMIN은 다른 기관의 장애도 조회한다")
    void adminCanReadAnyInstitution() {
        EquipmentIncident incident = incidentOf(otherInstitutionId);

        assertThat(service.getIncident(RoleType.ADMIN, myInstitutionId, incident.getId()).institutionId())
                .isEqualTo(otherInstitutionId);
    }

    @Test
    @DisplayName("상세: EXAMINER가 다른 기관의 장애를 조회하면 존재를 숨기고 404이며 처리 현황도 조회하지 않는다")
    void examinerCannotReadOtherInstitution() {
        EquipmentIncident incident = incidentOf(otherInstitutionId);

        assertErrorCode(() -> service.getIncident(RoleType.EXAMINER, myInstitutionId, incident.getId()),
                ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
        then(incidentReferralRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("상세: REQUESTER는 403이고 장애를 조회하지 않는다")
    void requesterCannotReadDetail() {
        assertErrorCode(() -> service.getIncident(RoleType.REQUESTER, myInstitutionId, UUID.randomUUID()),
                ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED);
        then(equipmentIncidentRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("상세: 없는 장애면 404")
    void detailNotFound() {
        UUID unknown = UUID.randomUUID();
        given(equipmentIncidentRepository.findById(unknown)).willReturn(Optional.empty());

        assertErrorCode(() -> service.getIncident(RoleType.ADMIN, myInstitutionId, unknown),
                ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
    }

    // ===== 영향 의뢰 =====

    @Test
    @DisplayName("영향 의뢰: 상태 필터가 없으면 전체, 있으면 해당 상태만 조회한다")
    void referralsByStatusFilter() {
        EquipmentIncident incident = incidentOf(myInstitutionId);
        UUID id = incident.getId();
        IncidentReferral task = IncidentReferral.pending(incident, UUID.randomUUID(), UUID.randomUUID());
        given(incidentReferralRepository.findByIncident_Id(eq(id), any()))
                .willReturn(new PageImpl<>(List.of(task)));
        given(incidentReferralRepository.findByIncident_IdAndProcessingStatus(eq(id),
                eq(IncidentProcessingStatus.FAILED), any())).willReturn(Page.empty());

        var all = service.getIncidentReferrals(RoleType.EXAMINER, myInstitutionId, id,
                new IncidentReferralSearchCondition(null, 0, 10));
        var failed = service.getIncidentReferrals(RoleType.EXAMINER, myInstitutionId, id,
                new IncidentReferralSearchCondition(IncidentProcessingStatus.FAILED, 0, 10));

        assertThat(all.contents()).singleElement().satisfies(item -> {
            assertThat(item.incidentId()).isEqualTo(id);
            assertThat(item.reservationId()).isEqualTo(task.getReservationId());
            assertThat(item.processingStatus()).isEqualTo(IncidentProcessingStatus.PENDING);
        });
        assertThat(failed.contents()).isEmpty();
    }

    @Test
    @DisplayName("영향 의뢰: lastError는 EXAMINER에게 null, ADMIN에게 원문이며 Entity 값은 바뀌지 않는다")
    void lastErrorIsHiddenFromExaminer() {
        EquipmentIncident incident = incidentOf(myInstitutionId);
        UUID id = incident.getId();
        IncidentReferral failed = IncidentReferral.pending(incident, UUID.randomUUID(), UUID.randomUUID());
        String internalError = "[RETRYABLE] PSQLException: ERROR: 가상 SQL 오류";
        ReflectionTestUtils.setField(failed, "processingStatus", IncidentProcessingStatus.FAILED);
        ReflectionTestUtils.setField(failed, "retryCount", 2);
        ReflectionTestUtils.setField(failed, "lastError", internalError);
        given(incidentReferralRepository.findByIncident_Id(eq(id), any())).willReturn(new PageImpl<>(List.of(failed)));
        IncidentReferralSearchCondition cond = new IncidentReferralSearchCondition(null, 0, 10);

        var forExaminer = service.getIncidentReferrals(RoleType.EXAMINER, myInstitutionId, id, cond)
                .contents().getFirst();
        var forAdmin = service.getIncidentReferrals(RoleType.ADMIN, otherInstitutionId, id, cond)
                .contents().getFirst();

        assertThat(forExaminer.lastError()).isNull();
        assertThat(forExaminer.processingStatus()).isEqualTo(IncidentProcessingStatus.FAILED);
        assertThat(forExaminer.retryCount()).isEqualTo(2);
        assertThat(forAdmin.lastError()).isEqualTo(internalError);
        assertThat(failed.getLastError()).isEqualTo(internalError);
    }

    @Test
    @DisplayName("영향 의뢰: EXAMINER가 다른 기관 장애의 의뢰를 조회하면 404이고 작업을 조회하지 않는다")
    void examinerCannotReadOtherInstitutionReferrals() {
        EquipmentIncident incident = incidentOf(otherInstitutionId);

        assertErrorCode(() -> service.getIncidentReferrals(RoleType.EXAMINER, myInstitutionId, incident.getId(),
                new IncidentReferralSearchCondition(null, 0, 10)), ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
        then(incidentReferralRepository).should(never()).findByIncident_Id(any(), any());
    }
}
