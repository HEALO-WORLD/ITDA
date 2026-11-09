package healoworld.referral_service.domain.policy;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.domain.enums.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationReconciliationPolicyTest {

    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final UUID OTHER_RESERVATION_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 10, 0);

    private final ReservationReconciliationPolicy policy = new ReservationReconciliationPolicy();

    // 슬롯의 reservationId가 누구를 가리키는지
    enum Owner {
        MINE,   // 이 예약
        OTHER,  // 다른 예약
        NONE    // 비어 있음
    }

    private static SlotSnapshot slot(String status, Owner owner) {
        UUID reservationId = switch (owner) {
            case MINE -> RESERVATION_ID;
            case OTHER -> OTHER_RESERVATION_ID;
            case NONE -> null;
        };
        LocalDateTime heldUntil = "HELD".equals(status) ? NOW.plusMinutes(3) : null;
        return new SlotSnapshot(status, reservationId, heldUntil);
    }

    private Optional<SlotResultType> decide(ReservationStatus reservationStatus, String slotStatus, Owner owner) {
        return policy.decide(RESERVATION_ID, reservationStatus, slot(slotStatus, owner), NOW);
    }

    // expected 열의 "-"는 처리하지 않음(Optional.empty)을 뜻한다
    @Nested
    @DisplayName("예약이 REQUESTED일 때")
    class Requested {

        @ParameterizedTest(name = "슬롯 {0}, 소유 {1} → {2}")
        @CsvSource(nullValues = "-", value = {
                "HELD,        MINE,  SLOT_HELD",          // SlotHeld 유실
                "BOOKED,      MINE,  SLOT_HELD",          // Held·Booked 모두 유실, BookSlot 재요청 시 ALREADY_BOOKED로 이어짐
                "AVAILABLE,   NONE,  SLOT_HOLD_FAILED",   // Hold 실패 유실
                "AVAILABLE,   MINE,  SLOT_HOLD_FAILED",   // 해제 후 예전 reservationId가 남아 있어도 소유 아님
                "HELD,        OTHER, SLOT_HOLD_FAILED",
                "BOOKED,      OTHER, SLOT_HOLD_FAILED",
                "UNAVAILABLE, NONE,  SLOTS_BLOCKED",      // 장비 장애로 막힘
                "UNAVAILABLE, MINE,  SLOTS_BLOCKED",
        })
        void decide_requested(String slotStatus, Owner owner, SlotResultType expected) {
            assertThat(decide(ReservationStatus.REQUESTED, slotStatus, owner))
                    .isEqualTo(Optional.ofNullable(expected));
        }
    }

    @Nested
    @DisplayName("예약이 HELD일 때")
    class Held {

        @ParameterizedTest(name = "슬롯 {0}, 소유 {1} → {2}")
        @CsvSource(nullValues = "-", value = {
                "BOOKED,      MINE,  SLOT_BOOKED",        // SlotBooked 유실
                "HELD,        MINE,  -",                  // Book 진행 중, HOLD 만료는 slot-service가 처리
                "AVAILABLE,   NONE,  HOLD_EXPIRED",       // HoldExpired 유실
                "AVAILABLE,   MINE,  HOLD_EXPIRED",       // 해제 후 예전 reservationId가 남아 있어도 멈추지 않아야 함
                "HELD,        OTHER, HOLD_EXPIRED",
                "BOOKED,      OTHER, HOLD_EXPIRED",
                "UNAVAILABLE, NONE,  SLOTS_BLOCKED",
                "UNAVAILABLE, MINE,  SLOTS_BLOCKED",
        })
        void decide_held(String slotStatus, Owner owner, SlotResultType expected) {
            assertThat(decide(ReservationStatus.HELD, slotStatus, owner))
                    .isEqualTo(Optional.ofNullable(expected));
        }
    }

    @Nested
    @DisplayName("예약이 CONFIRMED일 때")
    class Confirmed {

        @ParameterizedTest(name = "슬롯 {0}, 소유 {1} → {2}")
        @CsvSource(nullValues = "-", value = {
                "BOOKED,      MINE,  -",                  // 정상
                "BOOKED,      OTHER, SLOTS_BLOCKED",      // 이중 예약
                "HELD,        MINE,  SLOTS_BLOCKED",      // 확정됐는데 슬롯은 HELD
                "HELD,        OTHER, SLOTS_BLOCKED",
                "AVAILABLE,   NONE,  SLOTS_BLOCKED",
                "AVAILABLE,   MINE,  SLOTS_BLOCKED",
                "UNAVAILABLE, NONE,  SLOTS_BLOCKED",      // SlotsBlocked 유실
                "UNAVAILABLE, MINE,  SLOTS_BLOCKED",
        })
        void decide_confirmed(String slotStatus, Owner owner, SlotResultType expected) {
            assertThat(decide(ReservationStatus.CONFIRMED, slotStatus, owner))
                    .isEqualTo(Optional.ofNullable(expected));
        }
    }

    @Nested
    @DisplayName("예약이 종료 상태(FAILED, BLOCKED, CANCELED)일 때")
    class Terminal {

        @ParameterizedTest(name = "{0}: 이 예약이 BOOKED한 슬롯 → SLOT_BOOKED (ReleaseSlot 보상)")
        @EnumSource(value = ReservationStatus.class, names = {"FAILED", "BLOCKED", "CANCELED"})
        void decide_terminal_ownBooked(ReservationStatus reservationStatus) {
            assertThat(decide(reservationStatus, "BOOKED", Owner.MINE))
                    .contains(SlotResultType.SLOT_BOOKED);
        }

        @ParameterizedTest(name = "{0}: 슬롯 {1}, 소유 {2} → 처리하지 않음")
        @MethodSource("healoworld.referral_service.domain.policy.ReservationReconciliationPolicyTest#terminalNoAction")
        void decide_terminal_noAction(ReservationStatus reservationStatus, String slotStatus, Owner owner) {
            assertThat(decide(reservationStatus, slotStatus, owner)).isEmpty();
        }
    }

    // 종료 상태 × (이 예약이 BOOKED한 경우를 뺀 슬롯 상태)
    static Stream<Arguments> terminalNoAction() {
        Object[][] slots = {
                {"BOOKED", Owner.OTHER},        // 해제 후 다른 예약이 다시 잡음, ReleaseSlot을 보내면 안 됨
                {"HELD", Owner.MINE},           // HOLD 만료로 자동 해제
                {"HELD", Owner.OTHER},
                {"AVAILABLE", Owner.NONE},
                {"AVAILABLE", Owner.MINE},
                {"UNAVAILABLE", Owner.NONE},
                {"UNAVAILABLE", Owner.MINE},
        };
        return Stream.of(ReservationStatus.FAILED, ReservationStatus.BLOCKED, ReservationStatus.CANCELED)
                .flatMap(status -> Stream.of(slots)
                        .map(slot -> Arguments.of(status, slot[0], slot[1])));
    }
}
