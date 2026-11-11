package healoworld.slot_service.application.service;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.presentation.dto.response.SlotReservationContextResponse;
import healoworld.slot_service.presentation.dto.response.SlotStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// Referral의 정합성 점검과 의뢰 생성·재예약에 필요한 슬롯 내부 조회
// - 삭제된 슬롯과 없는 슬롯은 응답에서 빠진다. 호출하는 쪽은 응답에 없는 슬롯을 "판단할 수 없음"으로 보고 건너뛴다
// - 같은 ID가 여러 번 와도 한 번만 응답하고, 응답 순서는 요청 순서를 따른다
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SlotStatusQueryService {

    private final SlotRepository slotRepository;

    public SlotReservationContextResponse getReservationContext(UUID slotId) {
        Slot slot = slotRepository.findByIdAndDeletedAtIsNull(slotId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));
        return SlotReservationContextResponse.from(slot);
    }

    public List<SlotStatusResponse> getStatuses(List<UUID> slotIds) {
        List<UUID> distinctIds = slotIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, Slot> slotById = slotRepository.findAllByIdInAndDeletedAtIsNull(distinctIds).stream()
                .collect(Collectors.toMap(Slot::getId, Function.identity()));

        return distinctIds.stream()
                .map(slotById::get)
                .filter(Objects::nonNull)
                .map(SlotStatusResponse::from)
                .toList();
    }
}
