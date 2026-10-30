package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 장애 등록 → 영향 예약 조회 → PENDING 작업 생성 통합 테스트 (테스트 전용 PostgreSQL)
 *
 * - register()·createPendingReferrals()는 호출한 쪽 트랜잭션에 참여하지 않고 실제로 커밋하므로 테스트 트랜잭션도 끈다.
 * - 테스트가 만든 작업·장애·예약·의뢰 행만 끝날 때 ID로 지운다 (TestDatabaseGuard가 테스트 DB임을 보장).
 */
@DataJpaTest
@Import({EquipmentIncidentService.class, QuerydslConfig.class})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IncidentReferralCreationIntegrationTest {

    // 장애 영향 구간: 14:00 ~ 17:00
    private static final LocalDateTime INCIDENT_START = LocalDateTime.of(2026, 11, 2, 14, 0);
    private static final LocalDateTime INCIDENT_END = LocalDateTime.of(2026, 11, 2, 17, 0);

    @Autowired
    private EquipmentIncidentService equipmentIncidentService;

    @Autowired
    private EquipmentIncidentRepository equipmentIncidentRepository;

    @Autowired
    private IncidentReferralRepository incidentReferralRepository;

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private final UUID equipmentId = UUID.randomUUID();
    private final UUID institutionId = UUID.randomUUID();
    private final List<UUID> incidentIds = new ArrayList<>();
    private final List<Reservation> reservations = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // FK 순서: 작업 → 장애, 예약 → 의뢰
        incidentReferralRepository.deleteAll(incidentReferralRepository.findAll().stream()
                .filter(referral -> incidentIds.contains(referral.getIncident().getId()))
                .toList());
        equipmentIncidentRepository.deleteAllById(incidentIds);
        reservationRepository.deleteAll(reservations);
        referralRepository.deleteAll(reservations.stream().map(Reservation::getReferral).toList());
    }

    private Reservation confirmedReservation(int startHour, int endHour) {
        Referral referral = referralRepository.save(Referral.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "가상환자", UUID.randomUUID()));
        LocalDateTime start = INCIDENT_START.withHour(startHour);
        Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), UUID.randomUUID(), equipmentId,
                start, start, INCIDENT_START.withHour(endHour));
        reservation.hold();
        reservation.confirm();
        Reservation saved = reservationRepository.save(reservation);
        reservations.add(saved);
        return saved;
    }

    // 재전달 상황을 재현하려면 같은 이벤트는 내용도 같아야 하므로 기관 ID도 고정값을 쓴다
    private UUID registerIncident(UUID sourceEventId) {
        UUID id = equipmentIncidentService.register(sourceEventId, equipmentId, institutionId, "가상 장애 사유",
                INCIDENT_START, INCIDENT_START, INCIDENT_END);
        if (!incidentIds.contains(id)) {
            incidentIds.add(id);
        }
        return id;
    }

    private List<IncidentReferral> referralsOf(UUID incidentId) {
        return incidentReferralRepository.findAll().stream()
                .filter(referral -> referral.getIncident().getId().equals(incidentId))
                .toList();
    }

    private Map<UUID, UUID> reservationToReferral(List<IncidentReferral> referrals) {
        return referrals.stream().collect(Collectors.toMap(IncidentReferral::getReservationId,
                IncidentReferral::getReferralId));
    }

    @Test
    @DisplayName("장애 14~17시: 13~14시·17~18시는 제외되고 겹치는 2건만 PENDING 작업이 되며 의뢰 ID는 예약의 의뢰다")
    void createsPendingReferralPerAffectedReservation() {
        confirmedReservation(13, 14);                    // 경계만 닿음 → 제외
        Reservation b = confirmedReservation(14, 15);
        Reservation c = confirmedReservation(15, 16);
        confirmedReservation(17, 18);                    // 경계만 닿음 → 제외
        UUID incidentId = registerIncident(UUID.randomUUID());

        int created = equipmentIncidentService.createPendingReferrals(incidentId);

        List<IncidentReferral> referrals = referralsOf(incidentId);
        assertThat(created).isEqualTo(2);
        assertThat(reservationToReferral(referrals)).containsOnly(
                Map.entry(b.getId(), b.getReferral().getId()),
                Map.entry(c.getId(), c.getReferral().getId()));
        assertThat(referrals).allSatisfy(referral -> {
            assertThat(referral.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.PENDING);
            assertThat(referral.getRetryCount()).isZero();
            assertThat(referral.getLockedUntil()).isNull();
            assertThat(referral.getReferralId()).isNotEqualTo(referral.getReservationId());
        });
    }

    @Test
    @DisplayName("영향 예약이 없으면 작업을 만들지 않는다")
    void noAffectedReservation() {
        confirmedReservation(9, 10);
        UUID incidentId = registerIncident(UUID.randomUUID());

        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isZero();
        assertThat(referralsOf(incidentId)).isEmpty();
    }

    @Test
    @DisplayName("상황 A: 같은 이벤트가 재전달되면 장애·작업이 그대로 유지되고 새 작업은 0건이다")
    void redeliveryKeepsExistingReferrals() {
        confirmedReservation(14, 15);
        confirmedReservation(15, 16);
        confirmedReservation(16, 17);
        UUID sourceEventId = UUID.randomUUID();
        UUID incidentId = registerIncident(sourceEventId);
        equipmentIncidentService.createPendingReferrals(incidentId);
        List<UUID> before = referralsOf(incidentId).stream().map(IncidentReferral::getId).toList();

        UUID again = registerIncident(sourceEventId);
        int created = equipmentIncidentService.createPendingReferrals(again);

        assertThat(again).isEqualTo(incidentId);
        assertThat(created).isZero();
        assertThat(referralsOf(incidentId)).extracting(IncidentReferral::getId)
                .containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    @DisplayName("상황 B: 장애만 저장된 뒤 작업 생성이 빠졌어도, 같은 이벤트 재전달 시 기존 장애로 작업을 모두 만든다")
    void redeliveryAfterFailureCreatesMissingReferrals() {
        confirmedReservation(14, 15);
        confirmedReservation(15, 16);
        UUID sourceEventId = UUID.randomUUID();
        UUID incidentId = registerIncident(sourceEventId);   // 여기서 서버가 멈춰 작업 생성을 못 했다고 가정
        assertThat(referralsOf(incidentId)).isEmpty();

        UUID again = registerIncident(sourceEventId);
        int created = equipmentIncidentService.createPendingReferrals(again);

        assertThat(again).isEqualTo(incidentId);
        assertThat(created).isEqualTo(2);
        assertThat(referralsOf(incidentId)).hasSize(2);
    }

    @Test
    @DisplayName("일부 작업만 있으면 빠진 작업만 만들고 기존 작업은 바꾸지 않는다")
    void onlyMissingReferralsAreCreated() {
        Reservation first = confirmedReservation(14, 15);
        confirmedReservation(15, 16);
        confirmedReservation(16, 17);
        UUID incidentId = registerIncident(UUID.randomUUID());
        IncidentReferral existing = incidentReferralRepository.saveAndFlush(IncidentReferral.pending(
                equipmentIncidentRepository.findById(incidentId).orElseThrow(),
                first.getReferral().getId(), first.getId()));

        int created = equipmentIncidentService.createPendingReferrals(incidentId);

        assertThat(created).isEqualTo(2);
        List<IncidentReferral> referrals = referralsOf(incidentId);
        assertThat(referrals).hasSize(3);
        assertThat(referrals).filteredOn(referral -> referral.getReservationId().equals(first.getId()))
                .singleElement()
                .satisfies(referral -> assertThat(referral.getId()).isEqualTo(existing.getId()));
    }

    @Test
    @DisplayName("상황 C: 같은 장애로 5개 요청이 동시에 작업을 만들어도 예약마다 1건이고 새로 만든 수의 합은 예약 수와 같다")
    void concurrentCreationMakesOneReferralPerReservation() throws Exception {
        for (int hour = 14; hour < 17; hour++) {
            confirmedReservation(hour, hour + 1);
        }
        UUID incidentId = registerIncident(UUID.randomUUID());
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(1);

        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.await();
                    return equipmentIncidentService.createPendingReferrals(incidentId);
                }));
            }
            ready.countDown();

            int createdSum = 0;
            for (Future<Integer> result : results) {
                createdSum += result.get();
            }

            assertThat(createdSum).isEqualTo(3);
            assertThat(referralsOf(incidentId)).hasSize(3)
                    .extracting(IncidentReferral::getReservationId).doesNotHaveDuplicates();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("없는 장애 ID면 EQUIPMENT_INCIDENT_NOT_FOUND이고 작업을 만들지 않는다")
    void unknownIncident() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> equipmentIncidentService.createPendingReferrals(unknown))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND);
        assertThat(referralsOf(unknown)).isEmpty();
    }

    @Test
    @DisplayName("다른 장비의 장애는 이 장비의 예약에 작업을 만들지 않는다")
    void otherEquipmentIncident() {
        confirmedReservation(15, 16);
        UUID incidentId = equipmentIncidentService.register(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "가상 장애 사유", INCIDENT_START, INCIDENT_START, INCIDENT_END);
        incidentIds.add(incidentId);

        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isZero();
    }
}
