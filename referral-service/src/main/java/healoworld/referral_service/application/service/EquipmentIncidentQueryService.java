package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
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
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentSummaryResponse;
import healoworld.referral_service.presentation.dto.response.IncidentReferralResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 장비 장애 조회 (등록·후속 처리 로직과 분리된 읽기 전용 서비스)
//
// 권한 (Gateway가 JWT에서 꺼내 전달하는 X-Role, X-Institution-Id 기준)
//  - ADMIN: 모든 기관의 장애를 조회한다. 목록은 institutionId로 좁힐 수 있다
//  - EXAMINER: 자기 기관(X-Institution-Id)의 장애만 조회한다. 다른 기관 장애는 존재 여부도 알리지 않도록 404로 응답한다
//              영향 의뢰의 lastError(실패 분류와 진단 정보)는 받지 않는다 (null)
//  - 영향 의뢰의 수동 확인 필요 여부(manualReviewRequired)는 ADMIN·EXAMINER 모두 같은 기준으로 받는다
//  - REQUESTER: 장애 조회 권한이 없다 (403)
//  - 현황 집계(getSummary)는 ADMIN도 institutionId를 반드시 지정한다 (전체 기관 합계는 제공하지 않음)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentIncidentQueryService {

    private final EquipmentIncidentRepository equipmentIncidentRepository;
    private final IncidentReferralRepository incidentReferralRepository;
    private final ReferralRepository referralRepository;   // 현황 집계의 재조정 필요 의뢰 수
    private final ExaminationRepository examinationRepository;   // 영향 의뢰의 수동 확인 여부 판단

    public PagedResponse<EquipmentIncidentResponse> getIncidents(RoleType role, UUID institutionId,
                                                                 EquipmentIncidentSearchCondition cond) {
        UUID scopedInstitutionId = switch (role) {
            case ADMIN -> cond.institutionId();
            case EXAMINER -> institutionId;   // 요청 파라미터의 institutionId는 무시하고 자기 기관으로 고정한다
            default -> throw new BaseException(ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED);
        };
        Page<EquipmentIncidentResponse> page = equipmentIncidentRepository
                .search(scopedInstitutionId, cond.equipmentId(), cond.status(), cond.toPageable())
                .map(EquipmentIncidentResponse::from);
        return PagedResponse.from(page);
    }

    public EquipmentIncidentDetailResponse getIncident(RoleType role, UUID institutionId, UUID incidentId) {
        EquipmentIncident incident = findReadableIncident(role, institutionId, incidentId);
        return EquipmentIncidentDetailResponse.of(incident,
                count(incidentId, IncidentProcessingStatus.PENDING),
                count(incidentId, IncidentProcessingStatus.PROCESSING),
                count(incidentId, IncidentProcessingStatus.SUCCEEDED),
                count(incidentId, IncidentProcessingStatus.FAILED));
    }

    public PagedResponse<IncidentReferralResponse> getIncidentReferrals(RoleType role, UUID institutionId,
                                                                        UUID incidentId,
                                                                        IncidentReferralSearchCondition cond) {
        findReadableIncident(role, institutionId, incidentId);
        Page<IncidentReferral> page = cond.status() == null
                ? incidentReferralRepository.findByIncident_Id(incidentId, cond.toPageable())
                : incidentReferralRepository.findByIncident_IdAndProcessingStatus(incidentId, cond.status(),
                cond.toPageable());
        // lastError(실패 분류와 진단 정보)는 ADMIN에게만 보여준다. EXAMINER는 처리 상태·결과 코드·재시도 정보만 받는다
        boolean includeLastError = role == RoleType.ADMIN;
        Set<UUID> manualReviewTaskIds = findManualReviewTaskIds(page.getContent());
        return PagedResponse.from(page.map(task -> IncidentReferralResponse.from(task, includeLastError,
                manualReviewTaskIds.contains(task.getId()))));
    }

    // 수동 확인이 필요한 작업 ID: 아래를 모두 만족해야 한다
    //  - 자동 후속 처리가 끝났고(SUCCEEDED) 검사 진행 중이라 재조정에서 제외됐다(SKIPPED_IN_PROGRESS)
    //  - 지금도 그 검사가 진행 중(IN_PROGRESS)이고, 검사가 작업의 예약에 연결돼 있다 (예약이 다르면 다른 검사로 본다)
    // 검사 상태는 조회 시점의 DB 값이다. 검사가 COMPLETED로 바뀌면 다음 조회부터 false가 된다
    // 현재 페이지의 후보만 모아 검사를 한 번에 조회한다 (작업마다 조회하지 않음). 후보가 없으면 검사를 조회하지 않는다
    private Set<UUID> findManualReviewTaskIds(List<IncidentReferral> tasks) {
        List<IncidentReferral> candidates = tasks.stream()
                .filter(task -> task.getProcessingStatus() == IncidentProcessingStatus.SUCCEEDED
                        && IncidentProcessingService.SKIPPED_IN_PROGRESS.equals(task.getResultCode()))
                .toList();
        if (candidates.isEmpty()) {
            return Collections.emptySet();   // Set.of()와 달리 contains(null)에도 예외 없이 false
        }

        Set<UUID> referralIds = candidates.stream().map(IncidentReferral::getReferralId).collect(Collectors.toSet());
        Map<UUID, ExaminationProgress> examinations = examinationRepository.findProgressByReferralIds(referralIds)
                .stream()
                .collect(Collectors.toMap(ExaminationProgress::referralId, Function.identity()));

        return candidates.stream()
                .filter(task -> {
                    ExaminationProgress examination = examinations.get(task.getReferralId());
                    return examination != null
                            && examination.status() == ExaminationStatus.IN_PROGRESS
                            && task.getReservationId().equals(examination.reservationId());
                })
                .map(IncidentReferral::getId)
                .collect(Collectors.toSet());
    }

    // 장비 장애 현황 집계 (검사기관 대시보드)
    // - EXAMINER는 자기 기관(X-Institution-Id)으로 고정하고 요청의 institutionId는 무시한다
    // - ADMIN은 institutionId가 필수다 (없으면 기관 조건이 null이 되어 오류 없이 0이 나오므로 400으로 막는다)
    // - 세 값을 같은 기관과 같은 기준 시각으로 계산한다
    public EquipmentIncidentSummaryResponse getSummary(RoleType role, UUID institutionId, UUID requestedInstitutionId) {
        return getSummary(role, institutionId, requestedInstitutionId, LocalDateTime.now());
    }

    // 기준 시각을 지정할 수 있는 집계 (중단 구간 경계를 고정 시각으로 검증하는 테스트에서 사용)
    EquipmentIncidentSummaryResponse getSummary(RoleType role, UUID institutionId, UUID requestedInstitutionId,
                                                LocalDateTime now) {
        UUID scopedInstitutionId = switch (role) {
            case ADMIN -> {
                if (requestedInstitutionId == null) {
                    throw new BaseException(ErrorCode.VALIDATION_FAILED);
                }
                yield requestedInstitutionId;
            }
            case EXAMINER -> institutionId;   // 요청 파라미터의 institutionId는 무시하고 자기 기관으로 고정한다
            default -> throw new BaseException(ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED);
        };
        return new EquipmentIncidentSummaryResponse(scopedInstitutionId, now,
                equipmentIncidentRepository.countEquipmentsInDowntime(scopedInstitutionId, now),
                incidentReferralRepository.countAffectedReferrals(scopedInstitutionId),
                referralRepository.countNeedsRescheduleByLatestBlockedInstitution(scopedInstitutionId));
    }

    private EquipmentIncident findReadableIncident(RoleType role, UUID institutionId, UUID incidentId) {
        if (role != RoleType.ADMIN && role != RoleType.EXAMINER) {
            throw new BaseException(ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED);
        }
        EquipmentIncident incident = equipmentIncidentRepository.findById(incidentId)
                .orElseThrow(() -> new BaseException(ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND));
        if (role == RoleType.EXAMINER && !incident.getInstitutionId().equals(institutionId)) {
            throw new BaseException(ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
        }
        return incident;
    }

    private long count(UUID incidentId, IncidentProcessingStatus status) {
        return incidentReferralRepository.countByIncident_IdAndProcessingStatus(incidentId, status);
    }
}
