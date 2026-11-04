package healoworld.slot_service.application.service;

import healoworld.slot_service.global.dto.PagedResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.presentation.dto.response.SlotStatusHistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// 슬롯 상태 이력 조회. 기록은 SlotStatusHistoryRecorder가 담당한다
@Service
@RequiredArgsConstructor
public class SlotStatusHistoryService {

    private final SlotRepository slotRepository;
    private final SlotStatusHistoryRepository slotStatusHistoryRepository;

    // 오래된 변경부터 반환한다. 없거나 삭제된 슬롯이면 SLOT_NOT_FOUND
    @Transactional(readOnly = true)
    public PagedResponse<SlotStatusHistoryResponse> getHistories(UUID slotId, int page, int size) {
        slotRepository.findById(slotId)
                .filter(slot -> slot.getDeletedAt() == null)
                .orElseThrow(() -> new BaseException(ErrorCode.SLOT_NOT_FOUND));

        return PagedResponse.from(
                slotStatusHistoryRepository.findBySlotIdOrderByChangedAtAsc(slotId, PageRequest.of(page, size))
                        .map(SlotStatusHistoryResponse::from));
    }
}
