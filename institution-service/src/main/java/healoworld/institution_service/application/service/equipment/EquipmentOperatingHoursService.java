package healoworld.institution_service.application.service.equipment;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.domain.entity.EquipmentOperatingHours;
import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.EquipmentOperatingHoursRepository;
import healoworld.institution_service.infrastructure.repository.EquipmentRepository;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.presentation.dto.request.EquipmentOperatingHoursUpdateRequest;
import healoworld.institution_service.presentation.dto.request.EquipmentOperatingHoursUpdateRequest.OperatingHours;
import healoworld.institution_service.presentation.dto.response.EquipmentOperatingHoursResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EquipmentOperatingHoursService {

    private final EquipmentRepository equipmentRepository;
    private final InstitutionRepository institutionRepository;
    private final EquipmentOperatingHoursRepository operatingHoursRepository;
    private final EquipmentService equipmentService;

    @Transactional(readOnly = true)
    public List<EquipmentOperatingHoursResponse> getOperatingHours(
            UUID equipmentId,
            UUID userId,
            UUID userInstitutionId,
            Role userRole
    ) {
        equipmentService.getEquipment(
                equipmentId,
                userId,
                userInstitutionId,
                userRole
        );

        return operatingHoursRepository.findAllByEquipmentIdAndDeletedAtIsNullOrderByWeekAsc(equipmentId)
                .stream()
                .map(EquipmentOperatingHoursResponse::from)
                .toList();
    }

    @Transactional
    public List<EquipmentOperatingHoursResponse> updateOperatingHours(
            UUID equipmentId,
            EquipmentOperatingHoursUpdateRequest request,
            UUID userId,
            UUID userInstitutionId,
            Role userRole
    ) {
        if (equipmentId == null || userId == null
                || userInstitutionId == null || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.EXAMINER) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        Map<Integer, OperatingHours> requested = validateRequest(request);

        Equipment equipment = equipmentRepository.findForUpdateById(equipmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!equipment.getInstitutionId().equals(userInstitutionId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        Institution institution = institutionRepository.findByIdAndDeletedAtIsNull(userInstitutionId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!institution.isActive() || institution.getType() != InstitutionType.EXAM) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        Map<Integer, EquipmentOperatingHours> existing = new HashMap<>();

        for (EquipmentOperatingHours hours : operatingHoursRepository.findAllByEquipmentIdOrderByWeekAsc(equipmentId)) {
            existing.put(hours.getWeek(), hours);
        }

        for (int week = 1; week <= 7; week++) {
            OperatingHours input = requested.get(week);
            EquipmentOperatingHours hours = existing.get(week);

            if (input == null) {
                if (hours != null && hours.getDeletedAt() == null) {
                    hours.close(userId);
                }
                continue;
            }

            if (hours == null) {
                operatingHoursRepository.save(EquipmentOperatingHours.create(
                        equipment,
                        DayOfWeek.of(week),
                        input.getStartTime(),
                        input.getEndTime()));
            } else {
                hours.setOperatingTimes(input.getStartTime(), input.getEndTime());
            }
        }

        return operatingHoursRepository.findAllByEquipmentIdAndDeletedAtIsNullOrderByWeekAsc(equipmentId)
                .stream()
                .map(EquipmentOperatingHoursResponse::from)
                .toList();
    }

    private Map<Integer, OperatingHours> validateRequest(EquipmentOperatingHoursUpdateRequest request) {
        if (request == null
                || request.getOperatingHours() == null
                || request.getOperatingHours().size() > 7) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        Map<Integer, OperatingHours> requested = new HashMap<>();

        for (OperatingHours hours : request.getOperatingHours()) {
            if (hours == null
                    || hours.getWeek() == null
                    || hours.getWeek() < 1
                    || hours.getWeek() > 7
                    || hours.getStartTime() == null
                    || hours.getEndTime() == null
                    || !hours.getStartTime().isBefore(hours.getEndTime())) {
                throw new BaseException(ErrorCode.INVALID_INPUT);
            }

            if (requested.put(hours.getWeek(), hours) != null) {
                throw new BaseException(ErrorCode.INVALID_INPUT);
            }
        }

        return requested;
    }
}