package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.domain.enums.Role;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationProgress;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.presentation.dto.request.EquipmentIncidentSearchCondition;
import healoworld.referral_service.presentation.dto.request.IncidentReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentDetailResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentSummaryResponse;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

// 역할별 조회 범위(권한)와 응답 집계를 Mock으로 검증한다
@ExtendWith(MockitoExtension.class)
class EquipmentIncidentQueryServiceTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 2, 14, 0);
    // 현황 집계의 기준 시각: 한국 시간 OCCURRED_AT으로 고정한다 (JVM 기본 시간대와 무관)
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Spy
    private Clock clock = Clock.fixed(OCCURRED_AT.atZone(SEOUL).toInstant(), SEOUL);

    @Mock
    private EquipmentIncidentRepository equipmentIncidentRepository;

    @Mock
    private IncidentReferralRepository incidentReferralRepository;

    @Mock
    private ReferralRepository referralRepository;

    @Mock
    private ExaminationRepository examinationRepository;

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

        service.getIncidents(Role.ADMIN, myInstitutionId, searchFor(otherInstitutionId));
        service.getIncidents(Role.ADMIN, myInstitutionId, searchFor(null));

        then(equipmentIncidentRepository).should().search(eq(otherInstitutionId), eq(equipmentId),
                eq(EquipmentIncidentStatus.OCCURRED), any());
        then(equipmentIncidentRepository).should().search(eq(null), eq(equipmentId),
                eq(EquipmentIncidentStatus.OCCURRED), any());
    }

    @Test
    @DisplayName("EXAMINER 목록은 요청 파라미터의 기관을 무시하고 자기 기관으로만 조회한다")
    void examinerListIsScopedToOwnInstitution() {
        givenSearchReturnsEmpty();

        service.getIncidents(Role.EXAMINER, myInstitutionId, searchFor(otherInstitutionId));

        then(equipmentIncidentRepository).should().search(eq(myInstitutionId), eq(equipmentId),
                eq(EquipmentIncidentStatus.OCCURRED), any());
    }

    @Test
    @DisplayName("REQUESTER는 목록을 조회할 수 없다 (403, DB 조회 없음)")
    void requesterCannotList() {
        assertErrorCode(() -> service.getIncidents(Role.REQUESTER, myInstitutionId, searchFor(null)),
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

        var response = service.getIncidents(Role.EXAMINER, myInstitutionId, searchFor(null));

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

        EquipmentIncidentDetailResponse response = service.getIncident(Role.EXAMINER, myInstitutionId, id);

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

        assertThat(service.getIncident(Role.ADMIN, myInstitutionId, incident.getId()).institutionId())
                .isEqualTo(otherInstitutionId);
    }

    @Test
    @DisplayName("상세: EXAMINER가 다른 기관의 장애를 조회하면 존재를 숨기고 404이며 처리 현황도 조회하지 않는다")
    void examinerCannotReadOtherInstitution() {
        EquipmentIncident incident = incidentOf(otherInstitutionId);

        assertErrorCode(() -> service.getIncident(Role.EXAMINER, myInstitutionId, incident.getId()),
                ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
        then(incidentReferralRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("상세: REQUESTER는 403이고 장애를 조회하지 않는다")
    void requesterCannotReadDetail() {
        assertErrorCode(() -> service.getIncident(Role.REQUESTER, myInstitutionId, UUID.randomUUID()),
                ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED);
        then(equipmentIncidentRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("상세: 없는 장애면 404")
    void detailNotFound() {
        UUID unknown = UUID.randomUUID();
        given(equipmentIncidentRepository.findById(unknown)).willReturn(Optional.empty());

        assertErrorCode(() -> service.getIncident(Role.ADMIN, myInstitutionId, unknown),
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

        var all = service.getIncidentReferrals(Role.EXAMINER, myInstitutionId, id,
                new IncidentReferralSearchCondition(null, 0, 10));
        var failed = service.getIncidentReferrals(Role.EXAMINER, myInstitutionId, id,
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

        var forExaminer = service.getIncidentReferrals(Role.EXAMINER, myInstitutionId, id, cond)
                .contents().getFirst();
        var forAdmin = service.getIncidentReferrals(Role.ADMIN, otherInstitutionId, id, cond)
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

        assertErrorCode(() -> service.getIncidentReferrals(Role.EXAMINER, myInstitutionId, incident.getId(),
                new IncidentReferralSearchCondition(null, 0, 10)), ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
        then(incidentReferralRepository).should(never()).findByIncident_Id(any(), any());
        then(examinationRepository).should(never()).findProgressByReferralIds(any());
    }

    // ===== 영향 의뢰: 수동 확인 필요 여부 =====

    // 처리 상태·결과 코드를 지정한 작업 (의뢰·예약 ID는 새로 만든다)
    private IncidentReferral taskOf(EquipmentIncident incident, IncidentProcessingStatus status, String resultCode) {
        IncidentReferral task = IncidentReferral.pending(incident, UUID.randomUUID(), UUID.randomUUID());
        ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(task, "processingStatus", status);
        ReflectionTestUtils.setField(task, "resultCode", resultCode);
        return task;
    }

    private IncidentReferral skippedInProgress(EquipmentIncident incident) {
        return taskOf(incident, IncidentProcessingStatus.SUCCEEDED, IncidentProcessingService.SKIPPED_IN_PROGRESS);
    }

    // 작업의 의뢰에 연결된 검사 (reservationId·status 지정)
    private ExaminationProgress examinationOf(IncidentReferral task, UUID reservationId, ExaminationStatus status) {
        return new ExaminationProgress(task.getReferralId(), reservationId, status);
    }

    // 장애 페이지의 작업 목록과 검사 일괄 조회 결과를 지정하고, 작업 ID별 manualReviewRequired를 돌려준다
    private Map<UUID, Boolean> manualReviewOf(EquipmentIncident incident, List<IncidentReferral> tasks,
                                                       List<ExaminationProgress> examinations) {
        given(incidentReferralRepository.findByIncident_Id(eq(incident.getId()), any()))
                .willReturn(new PageImpl<>(tasks));
        if (examinations != null) {
            given(examinationRepository.findProgressByReferralIds(any())).willReturn(examinations);
        }
        return service.getIncidentReferrals(Role.EXAMINER, myInstitutionId, incident.getId(),
                        new IncidentReferralSearchCondition(null, 0, 10)).contents().stream()
                .collect(Collectors.toMap(item -> item.id(), item -> item.manualReviewRequired()));
    }

    @Test
    @DisplayName("수동 확인: SUCCEEDED·SKIPPED_IN_PROGRESS이고 같은 예약의 검사가 IN_PROGRESS면 true")
    void manualReviewWhenExaminationInProgress() {
        EquipmentIncident incident = incidentOf(myInstitutionId);
        IncidentReferral task = skippedInProgress(incident);

        var result = manualReviewOf(incident, List.of(task),
                List.of(examinationOf(task, task.getReservationId(), ExaminationStatus.IN_PROGRESS)));

        assertThat(result).containsEntry(task.getId(), true);
    }

    @Test
    @DisplayName("수동 확인: 검사가 다른 예약에 연결돼 있으면 IN_PROGRESS여도 false")
    void noManualReviewWhenReservationDiffers() {
        EquipmentIncident incident = incidentOf(myInstitutionId);
        IncidentReferral task = skippedInProgress(incident);

        var result = manualReviewOf(incident, List.of(task),
                List.of(examinationOf(task, UUID.randomUUID(), ExaminationStatus.IN_PROGRESS)));

        assertThat(result).containsEntry(task.getId(), false);
    }

    @Test
    @DisplayName("수동 확인: 검사가 COMPLETED·SCHEDULED이면 false (IN_PROGRESS → COMPLETED 이후 재조회 포함)")
    void noManualReviewWhenExaminationNotInProgress() {
        EquipmentIncident incident = incidentOf(myInstitutionId);
        IncidentReferral completed = skippedInProgress(incident);
        IncidentReferral scheduled = skippedInProgress(incident);

        var result = manualReviewOf(incident, List.of(completed, scheduled), List.of(
                examinationOf(completed, completed.getReservationId(), ExaminationStatus.COMPLETED),
                examinationOf(scheduled, scheduled.getReservationId(), ExaminationStatus.SCHEDULED)));

        assertThat(result).containsEntry(completed.getId(), false).containsEntry(scheduled.getId(), false);
    }

    @Test
    @DisplayName("수동 확인: 검사 데이터가 없으면 false")
    void noManualReviewWithoutExamination() {
        EquipmentIncident incident = incidentOf(myInstitutionId);
        IncidentReferral task = skippedInProgress(incident);

        var result = manualReviewOf(incident, List.of(task), List.of());

        assertThat(result).containsEntry(task.getId(), false);
    }

    @Test
    @DisplayName("수동 확인: 다른 결과 코드(SKIPPED_COMPLETED·RESCHEDULE_REQUIRED·ALREADY_BLOCKED)와 SUCCEEDED가 아닌 작업은 "
            + "false이고 후보가 없으면 검사를 조회하지 않는다")
    void noManualReviewForOtherTasks() {
        EquipmentIncident incident = incidentOf(myInstitutionId);
        List<IncidentReferral> tasks = List.of(
                taskOf(incident, IncidentProcessingStatus.SUCCEEDED, IncidentProcessingService.SKIPPED_COMPLETED),
                taskOf(incident, IncidentProcessingStatus.SUCCEEDED, IncidentProcessingService.RESCHEDULE_REQUIRED),
                taskOf(incident, IncidentProcessingStatus.SUCCEEDED, IncidentProcessingService.ALREADY_BLOCKED),
                taskOf(incident, IncidentProcessingStatus.PENDING, null),
                taskOf(incident, IncidentProcessingStatus.PROCESSING, null),
                taskOf(incident, IncidentProcessingStatus.FAILED, null),
                // 비정상 데이터라도 SUCCEEDED가 아니면 결과 코드와 관계없이 후보가 아니다
                taskOf(incident, IncidentProcessingStatus.FAILED, IncidentProcessingService.SKIPPED_IN_PROGRESS));

        var result = manualReviewOf(incident, tasks, null);

        assertThat(result).hasSize(tasks.size()).doesNotContainValue(true);
        then(examinationRepository).should(never()).findProgressByReferralIds(any());
    }

    @Test
    @DisplayName("수동 확인: 페이지에 후보가 여러 건이어도 검사는 후보 의뢰 ID로 한 번만 조회한다 (N+1 없음)")
    @SuppressWarnings("unchecked")
    void examinationsAreLoadedOnceForCandidates() {
        EquipmentIncident incident = incidentOf(myInstitutionId);
        IncidentReferral first = skippedInProgress(incident);
        IncidentReferral second = skippedInProgress(incident);
        IncidentReferral third = skippedInProgress(incident);
        IncidentReferral notCandidate = taskOf(incident, IncidentProcessingStatus.SUCCEEDED,
                IncidentProcessingService.RESCHEDULE_REQUIRED);

        var result = manualReviewOf(incident, List.of(first, notCandidate, second, third), List.of(
                examinationOf(first, first.getReservationId(), ExaminationStatus.IN_PROGRESS),
                examinationOf(second, second.getReservationId(), ExaminationStatus.COMPLETED),
                examinationOf(third, third.getReservationId(), ExaminationStatus.IN_PROGRESS)));

        assertThat(result).containsEntry(first.getId(), true).containsEntry(second.getId(), false)
                .containsEntry(third.getId(), true).containsEntry(notCandidate.getId(), false);
        ArgumentCaptor<Collection<UUID>> referralIds = ArgumentCaptor.forClass(Collection.class);
        then(examinationRepository).should(times(1)).findProgressByReferralIds(referralIds.capture());
        assertThat(Set.copyOf(referralIds.getValue()))
                .containsExactlyInAnyOrder(first.getReferralId(), second.getReferralId(), third.getReferralId());
    }

    @Test
    @DisplayName("수동 확인: ADMIN도 같은 기준으로 받는다")
    void adminReceivesManualReview() {
        EquipmentIncident incident = incidentOf(myInstitutionId);
        IncidentReferral task = skippedInProgress(incident);
        given(incidentReferralRepository.findByIncident_Id(eq(incident.getId()), any()))
                .willReturn(new PageImpl<>(List.of(task)));
        given(examinationRepository.findProgressByReferralIds(any())).willReturn(
                List.of(examinationOf(task, task.getReservationId(), ExaminationStatus.IN_PROGRESS)));

        var item = service.getIncidentReferrals(Role.ADMIN, otherInstitutionId, incident.getId(),
                new IncidentReferralSearchCondition(null, 0, 10)).contents().getFirst();

        assertThat(item.manualReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("수동 확인: REQUESTER는 기존처럼 403이고 작업·검사를 조회하지 않는다")
    void requesterCannotReadReferrals() {
        // 역할 확인에서 먼저 거절되므로 장애도 조회하지 않는다
        assertErrorCode(() -> service.getIncidentReferrals(Role.REQUESTER, myInstitutionId, UUID.randomUUID(),
                new IncidentReferralSearchCondition(null, 0, 10)), ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED);
        then(incidentReferralRepository).should(never()).findByIncident_Id(any(), any());
        then(examinationRepository).should(never()).findProgressByReferralIds(any());
    }

    // ===== 현황 집계 =====

    private void givenCounts(UUID institutionId, LocalDateTime now, long active, long affected, long needsReschedule) {
        given(equipmentIncidentRepository.countEquipmentsInDowntime(institutionId, now)).willReturn(active);
        given(incidentReferralRepository.countAffectedReferrals(institutionId)).willReturn(affected);
        given(referralRepository.countNeedsRescheduleByLatestBlockedInstitution(institutionId)).willReturn(needsReschedule);
    }

    private void thenNoCountQueries() {
        then(equipmentIncidentRepository).should(never()).countEquipmentsInDowntime(any(), any());
        then(incidentReferralRepository).should(never()).countAffectedReferrals(any());
        then(referralRepository).should(never()).countNeedsRescheduleByLatestBlockedInstitution(any());
    }

    @Test
    @DisplayName("현황: EXAMINER는 요청의 institutionId를 무시하고 자기 기관·같은 기준 시각으로 세 값을 집계한다")
    void examinerSummaryUsesOwnInstitution() {
        givenCounts(myInstitutionId, OCCURRED_AT, 1, 5, 2);

        EquipmentIncidentSummaryResponse summary = service.getSummary(Role.EXAMINER, myInstitutionId,
                otherInstitutionId, OCCURRED_AT);

        assertThat(summary).isEqualTo(new EquipmentIncidentSummaryResponse(myInstitutionId, OCCURRED_AT, 1, 5, 2));
        then(equipmentIncidentRepository).should(never()).countEquipmentsInDowntime(eq(otherInstitutionId), any());
    }

    @Test
    @DisplayName("현황: 기준 시각을 지정하지 않으면 한국 시간 현재 시각으로 진행 중인 중단을 센다")
    void summaryUsesKoreanClock() {
        givenCounts(myInstitutionId, OCCURRED_AT, 1, 0, 0);

        EquipmentIncidentSummaryResponse summary = service.getSummary(Role.EXAMINER, myInstitutionId, null);

        assertThat(summary.asOf()).isEqualTo(OCCURRED_AT);
        then(equipmentIncidentRepository).should().countEquipmentsInDowntime(myInstitutionId, OCCURRED_AT);
    }

    @Test
    @DisplayName("현황: ADMIN은 지정한 institutionId로 세 값을 집계한다")
    void adminSummaryUsesRequestedInstitution() {
        givenCounts(otherInstitutionId, OCCURRED_AT, 0, 3, 1);

        EquipmentIncidentSummaryResponse summary = service.getSummary(Role.ADMIN, myInstitutionId,
                otherInstitutionId, OCCURRED_AT);

        assertThat(summary).isEqualTo(new EquipmentIncidentSummaryResponse(otherInstitutionId, OCCURRED_AT, 0, 3, 1));
    }

    @Test
    @DisplayName("현황: ADMIN이 institutionId 없이 요청하면 400 VALIDATION_FAILED이고 집계하지 않는다")
    void adminSummaryRequiresInstitutionId() {
        assertErrorCode(() -> service.getSummary(Role.ADMIN, myInstitutionId, null, OCCURRED_AT),
                ErrorCode.VALIDATION_FAILED);
        thenNoCountQueries();
    }

    @Test
    @DisplayName("현황: REQUESTER는 403이고 집계하지 않는다")
    void requesterCannotReadSummary() {
        assertErrorCode(() -> service.getSummary(Role.REQUESTER, myInstitutionId, myInstitutionId, OCCURRED_AT),
                ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED);
        thenNoCountQueries();
    }
}
