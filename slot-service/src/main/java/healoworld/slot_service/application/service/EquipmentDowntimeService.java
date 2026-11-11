package healoworld.slot_service.application.service;

import healoworld.slot_service.domain.enums.Role;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.presentation.dto.response.EquipmentDowntimeLifecycleResponse;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.client.EquipmentOwnerClient;
import healoworld.slot_service.infrastructure.client.EquipmentVisibilityClient;
import healoworld.slot_service.presentation.dto.request.EquipmentDowntimeCreateRequest;
import healoworld.slot_service.presentation.dto.response.EquipmentDowntimeCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

// 장비 중단 등록
// 권한 (Gateway가 JWT에서 꺼내 전달하는 X-Role, X-Institution-Id 기준)
//  - EXAMINER: 자기 기관 장비만 등록한다
//  - ADMIN: 모든 장비를 등록한다. 이벤트의 기관 ID는 장비의 실제 소속 기관이다
//  - REQUESTER: 등록할 수 없다 (403)
// 장비 소유권은 Institution Service에 REST로 확인하므로 이 메서드에는 트랜잭션을 걸지 않는다
// (원격 호출을 기다리는 동안 DB 커넥션과 잠금을 잡고 있지 않도록). DB 변경은 EquipmentDowntimeWriter가 한 트랜잭션으로 처리한다
@Service
@RequiredArgsConstructor
public class EquipmentDowntimeService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final EquipmentDowntimeWriter equipmentDowntimeWriter;
    private final EquipmentVisibilityClient equipmentVisibilityClient;
    private final EquipmentOwnerClient equipmentOwnerClient;
    private final Clock clock;
    private final EquipmentDowntimeRepository downtimes;
    private final EquipmentDowntimeLifecycleWriter lifecycleWriter;

    public EquipmentDowntimeCreateResponse createDowntime(
            EquipmentDowntimeCreateRequest request,
            UUID userId,
            UUID userInstitutionId,
            Role userRole
    ) {
        validateActor(userId, userInstitutionId, userRole);

        LocalDateTime startedAt = toBusinessTime(request.getStartedAt().toInstant());
        LocalDateTime endedAt = toBusinessTime(request.getEndedAt().toInstant());

        if (!startedAt.isBefore(endedAt)) {
            throw new BaseException(ErrorCode.DOWNTIME_INVALID_TIME_RANGE);
        }

        // 이미 발생한 장애도 등록할 수 있도록 시작 일시는 과거를 허용하고, 종료 일시만 현재 이후로 제한한다
        if (!request.getEndedAt().toInstant().isAfter(clock.instant())) {
            throw new BaseException(ErrorCode.DOWNTIME_END_NOT_FUTURE);
        }

        UUID ownerInstitutionId = resolveOwnerInstitution(request.getEquipmentId(), userInstitutionId, userRole);

        return EquipmentDowntimeCreateResponse.from(equipmentDowntimeWriter.register(
                request.getEquipmentId(),
                ownerInstitutionId,
                startedAt,
                endedAt,
                request.getReason().strip(),
                userId, request.getDowntimeType()
        ));
    }

    public EquipmentDowntimeLifecycleResponse recover(UUID id, String reason,
                                                      UUID userId, UUID userInstitutionId, Role userRole) {
        validateActor(userId, userInstitutionId, userRole);
        var d = downtimes.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));
        UUID owner = resolveOwnerInstitution(d.getEquipmentId(), userInstitutionId, userRole);
        return lifecycleWriter.recover(id, d.getEquipmentId(), owner, userId, reason);
    }

    public EquipmentDowntimeLifecycleResponse extend(UUID id, OffsetDateTime endedAt,
                                                     UUID userId, UUID userInstitutionId, Role userRole) {
        validateActor(userId, userInstitutionId, userRole);
        var d = downtimes.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));
        UUID owner = resolveOwnerInstitution(d.getEquipmentId(), userInstitutionId, userRole);
        return lifecycleWriter.extend(id, d.getEquipmentId(), owner, userId, toBusinessTime(endedAt.toInstant()));
    }

    private void validateActor(UUID userId, UUID institutionId, Role role) {
        if (userId == null || institutionId == null || role == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (role != Role.EXAMINER && role != Role.ADMIN) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    // 이벤트에 담을 장비의 실제 소속 기관 ID를 정한다. 요청자 헤더·요청 본문의 기관 ID는 이 값으로 쓰지 않는다
    //  - EXAMINER: 기존 조회 권한 확인(자기 기관의 활성 검사기관 장비인지)을 거친 뒤, 실제 소속 기관이 요청자 기관과 같은지 다시 확인한다
    //  - ADMIN: 장비의 실제 소속 기관을 그대로 쓴다. 장비가 없으면 404
    // 조회가 실패하면 예외로 끝나므로 트랜잭션이 시작되지 않고 Outbox에도 아무것도 저장되지 않는다
    private UUID resolveOwnerInstitution(UUID equipmentId, UUID institutionId, Role role) {
        if (role == Role.ADMIN) {
            return equipmentOwnerClient.getOwnerInstitutionId(equipmentId);
        }

        // 고장·점검 중인 장비도 중단을 등록해야 하므로 운영 상태와 관계없이(onlyOperational = false) 조회한다
        // EXAMINER는 다른 기관 장비인지 없는 장비인지 구분하지 않고 403으로 응답한다 (슬롯 단건 조회와 같은 방식)
        Set<UUID> visibleEquipmentIds =
                equipmentVisibilityClient.getVisibleEquipmentIds(institutionId, role, null, false);
        if (!visibleEquipmentIds.contains(equipmentId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        UUID ownerInstitutionId = equipmentOwnerClient.getOwnerInstitutionId(equipmentId);
        if (!institutionId.equals(ownerInstitutionId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return ownerInstitutionId;
    }

    private LocalDateTime toBusinessTime(Instant instant) {
        return instant.atZone(BUSINESS_ZONE).toLocalDateTime();
    }
}
