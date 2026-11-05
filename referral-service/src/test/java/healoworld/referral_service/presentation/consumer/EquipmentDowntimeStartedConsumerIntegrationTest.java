package healoworld.referral_service.presentation.consumer;

import healoworld.referral_service.application.service.EquipmentIncidentService;
import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentLockRepository;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slot Service가 Outbox로 발행하는 EquipmentDowntimeStarted 메시지(키·헤더·JSON 형식 그대로)를 실제 DB에서 소비한다.
 * <p>
 * - 같은 메시지가 두 번 전달돼도(Outbox 재발행·Kafka 재전달) 장애 1건, 영향 예약 후속 작업 1건만 남는지 확인한다.
 * - 본문은 Slot 쪽 테스트(EquipmentDowntimeWriterTest)가 검증한 필드 이름·시각 형식과 같다.
 * - 테스트 DB(Testcontainers)에서 실제로 커밋하므로 테스트 트랜잭션을 끄고, 만든 행만 끝날 때 지운다.
 */
@DataJpaTest
@Import({EquipmentIncidentService.class, EquipmentIncidentLockRepository.class, QuerydslConfig.class})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EquipmentDowntimeStartedConsumerIntegrationTest extends PostgresContainerSupport {

    private static final LocalDateTime DOWN_START = LocalDateTime.of(2026, 11, 10, 10, 0);
    private static final LocalDateTime DOWN_END = LocalDateTime.of(2026, 11, 10, 12, 0);

    @Autowired private EquipmentIncidentService equipmentIncidentService;
    @Autowired private EquipmentIncidentRepository equipmentIncidentRepository;
    @Autowired private IncidentReferralRepository incidentReferralRepository;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private ReservationRepository reservationRepository;

    private final UUID equipmentId = UUID.randomUUID();
    private final UUID institutionId = UUID.randomUUID();
    private final List<Reservation> reservations = new ArrayList<>();

    private EquipmentDowntimeStartedConsumer consumer;

    @AfterEach
    void cleanUp() {
        List<EquipmentIncident> incidents = equipmentIncidentRepository.findAll().stream()
                .filter(incident -> incident.getEquipmentId().equals(equipmentId))
                .toList();
        incidentReferralRepository.deleteAll(incidentReferralRepository.findAll().stream()
                .filter(task -> incidents.stream().anyMatch(i -> i.getId().equals(task.getIncident().getId())))
                .toList());
        equipmentIncidentRepository.deleteAll(incidents);
        reservationRepository.deleteAll(reservations);
        referralRepository.deleteAll(reservations.stream().map(Reservation::getReferral).toList());
    }

    @Test
    @DisplayName("같은 장비 중단 메시지가 두 번 와도 장애 1건과 영향 예약 후속 작업 1건만 만든다")
    void duplicateDeliveryIsIdempotent() {
        consumer = new EquipmentDowntimeStartedConsumer(equipmentIncidentService, JsonMapper.builder().build());
        Reservation affected = confirmedReservation(DOWN_START.plusMinutes(30), DOWN_START.plusMinutes(60));
        confirmedReservation(DOWN_END, DOWN_END.plusMinutes(30));   // 경계만 닿음 → 영향 없음
        UUID eventId = UUID.randomUUID();
        ConsumerRecord<String, String> record = slotMessage(eventId);

        consumer.consume(record);
        consumer.consume(record);   // Outbox 재발행 또는 Kafka 재전달

        List<EquipmentIncident> incidents = equipmentIncidentRepository.findAll().stream()
                .filter(incident -> incident.getEquipmentId().equals(equipmentId))
                .toList();
        assertThat(incidents).hasSize(1);
        EquipmentIncident incident = incidents.getFirst();
        assertThat(incident.getSourceEventId()).isEqualTo(eventId);
        assertThat(incident.getInstitutionId()).isEqualTo(institutionId);
        assertThat(incident.getStartAt()).isEqualTo(DOWN_START);
        assertThat(incident.getEndAt()).isEqualTo(DOWN_END);

        List<IncidentReferral> tasks = incidentReferralRepository.findAll().stream()
                .filter(task -> task.getIncident().getId().equals(incident.getId()))
                .toList();
        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.getReservationId()).isEqualTo(affected.getId());
            assertThat(task.getReferralId()).isEqualTo(affected.getReferral().getId());
        });
    }

    // Slot Service OutboxPublisher가 보내는 형식: key = equipmentId, 헤더 eventId·eventType, 한국 시간 LocalDateTime 문자열
    private ConsumerRecord<String, String> slotMessage(UUID eventId) {
        String payload = """
                {"eventId": "%s", "eventType": "EquipmentDowntimeStarted", "occurredAt": "2026-10-30T10:00:00",
                 "equipmentId": "%s", "institutionId": "%s", "reason": "가상 사유",
                 "startAt": "2026-11-10T10:00:00", "endAt": "2026-11-10T12:00:00"}
                """.formatted(eventId, equipmentId, institutionId);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("equipment-events", 0, 0L, equipmentId.toString(), payload);
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", "EquipmentDowntimeStarted".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private Reservation confirmedReservation(LocalDateTime start, LocalDateTime end) {
        Referral referral = referralRepository.save(Referral.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "가상환자", UUID.randomUUID()));
        Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), UUID.randomUUID(), equipmentId,
                start, start, end);
        reservation.hold();
        reservation.confirm();
        Reservation saved = reservationRepository.save(reservation);
        reservations.add(saved);
        return saved;
    }
}
