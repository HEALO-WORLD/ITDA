package healoworld.slot_service.application.event.command;

import java.util.UUID;

// slot-commands로 수신하는 명령의 공통 필드. 컨슈머가 필수 필드와 메시지 키를 같은 방식으로 검증하는 데 쓴다
public interface SlotCommand {

    UUID eventId();

    UUID slotId();

    UUID reservationId();
}
