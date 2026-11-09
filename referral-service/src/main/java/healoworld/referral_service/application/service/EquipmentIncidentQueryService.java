package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
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
import java.util.UUID;

// 장비 장애 조회 (등록·후속 처리 로직과 분리된 읽기 전용 서비스)
//
// 권한 (Gateway가 JWT에서 꺼내 전달하는 X-Role, X-Institution-Id 기준)
//  - ADMIN: 모든 기관의 장애를 조회한다. 목록은 institutionId로 좁힐 수 있다
//  - EXAMINER: 자기 기관(X-Institution-Id)의 장애만 조회한다. 다른 기관 장애는 존재 여부도 알리지 않도록 404로 응답한다
//              영향 의뢰의 lastError(실패 분류와 진단 정보)는 받지 않는다 (null)
//  - REQUESTER: 장애 조회 권한이 없다 (403)
//  - 현황 집계(getSummary)는 ADMIN도 institutionId를 반드시 지정한다 (전체 기관 합계는 제공하지 않음)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentIncidentQueryService {

    private final EquipmentIncidentRepository equipmentIncidentRepository;
    private final IncidentReferralRepository incidentReferralRepository;
    private final ReferralRepository referralRepository;   // 현황 집계의 재조정 필요 의뢰 수

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
        return PagedResponse.from(page.map(task -> IncidentReferralResponse.from(task, includeLastError)));
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
