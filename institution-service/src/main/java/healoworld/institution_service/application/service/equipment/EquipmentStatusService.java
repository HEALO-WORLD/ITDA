package healoworld.institution_service.application.service.equipment;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.domain.entity.EquipmentStatusHistory;
import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.EquipmentStatus;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.EquipmentRepository;
import healoworld.institution_service.infrastructure.repository.EquipmentStatusHistoryRepository;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.presentation.dto.request.EquipmentStatusUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentDetailResponse;
import healoworld.institution_service.presentation.dto.response.EquipmentStatusHistoryResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EquipmentStatusService {
    private final EquipmentRepository equipmentRepository;
    private final InstitutionRepository institutionRepository;
    private final EquipmentStatusHistoryRepository historyRepository;
    private final EquipmentService equipmentService;
    private final Clock clock;

    @Transactional
    public EquipmentDetailResponse updateStatus(UUID equipmentId, EquipmentStatusUpdateRequest request,
                                                UUID userId, UUID userInstitutionId, Role role) {
        validateActor(equipmentId, userId, userInstitutionId, role);
        if (request == null || request.getStatus() == null || request.getReason() == null
                || request.getReason().isBlank() || request.getReason().length() > 500) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        // 같은 장비 상태 변경은 순서대로 처리하고 잠금 획득 후 실제 이전 상태를 읽는다.
        Equipment equipment = equipmentRepository.findForUpdateById(equipmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!userInstitutionId.equals(equipment.getInstitutionId())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        Institution institution = institutionRepository.findByIdAndDeletedAtIsNull(userInstitutionId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!institution.isActive() || institution.getType() != InstitutionType.EXAM) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        EquipmentStatus fromStatus = equipment.getStatus();
        if (fromStatus == request.getStatus()) {
            return EquipmentDetailResponse.from(equipment);
        }

        equipment.changeStatus(request.getStatus());
        historyRepository.save(EquipmentStatusHistory.create(equipmentId, fromStatus, request.getStatus(),
                request.getReason(), userId, LocalDateTime.now(clock)));
        return EquipmentDetailResponse.from(equipment);
    }

    @Transactional(readOnly = true)
    public PagedResponse<EquipmentStatusHistoryResponse> getHistories(UUID equipmentId,
                                                                      UUID userId, UUID userInstitutionId, Role role, Integer page, Integer size) {
        validateActor(equipmentId, userId, userInstitutionId, role);
        if (page == null || size == null || page < 0 || size < 1 || size > 100) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }
        // 기존 장비 조회의 소유 기관·활성 검사기관 검증을 재사용한다.
        equipmentService.getEquipment(equipmentId, userId, userInstitutionId, role);
        return PagedResponse.from(historyRepository
                .findByEquipmentIdOrderByChangedAtDescIdDesc(equipmentId, PageRequest.of(page, size))
                .map(EquipmentStatusHistoryResponse::from));
    }

    private void validateActor(UUID equipmentId, UUID userId, UUID userInstitutionId, Role role) {
        if (equipmentId == null || userId == null || userInstitutionId == null || role == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }
        if (role != Role.EXAMINER) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }
}
