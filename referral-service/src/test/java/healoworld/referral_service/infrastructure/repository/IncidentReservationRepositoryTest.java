package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트 전용 PostgreSQL에서 영향 예약 조회 조건(장비·상태·시간 겹침·삭제 제외)을 검증한다 (테스트마다 롤백)
@DataJpaTest
@Import(QuerydslConfig.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IncidentReservationRepositoryTest extends PostgresContainerSupport {

    // 장애 영향 구간: 14:00 ~ 17:00
    private static final LocalDateTime INCIDENT_START = LocalDateTime.of(2026, 11, 2, 14, 0);
    private static final LocalDateTime INCIDENT_END = LocalDateTime.of(2026, 11, 2, 17, 0);

    @Autowired
    private IncidentReservationRepository incidentReservationRepository;

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ExaminationRepository examinationRepository;

    private final UUID equipmentId = UUID.randomUUID();

    private Reservation reservation(UUID equipmentId, int startHour, int startMinute, int endHour, int endMinute,
                                    ReservationStatus status) {
        Referral referral = referralRepository.save(Referral.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "가상환자", UUID.randomUUID()));
        LocalDateTime start = INCIDENT_START.withHour(startHour).withMinute(startMinute);
        Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), UUID.randomUUID(), equipmentId,
                start, start, INCIDENT_START.withHour(endHour).withMinute(endMinute));
        switch (status) {
            case REQUESTED -> {
            }
            case HELD -> reservation.hold();
            case CONFIRMED -> {
                reservation.hold();
                reservation.confirm();
            }
            case FAILED -> reservation.fail(FailureReason.SLOT_CONFLICT);
            case BLOCKED -> {
                reservation.hold();
                reservation.confirm();
                reservation.block();
            }
            case CANCELED -> {
                reservation.hold();
                reservation.confirm();
                reservation.cancel();
            }
        }
        return reservationRepository.saveAndFlush(reservation);
    }

    private Reservation confirmed(int startHour, int startMinute, int endHour, int endMinute) {
        return reservation(equipmentId, startHour, startMinute, endHour, endMinute, ReservationStatus.CONFIRMED);
    }

    private List<UUID> affectedIds() {
        return incidentReservationRepository.findAffected(equipmentId, INCIDENT_START, INCIDENT_END).stream()
                .map(AffectedReservation::reservationId)
                .toList();
    }

    @Test
    @DisplayName("요청서 예시: 13~14시·17~18시는 경계만 닿아 제외되고, 14~14:30·15~15:30만 영향 예약이다")
    void exampleFromRequirement() {
        Reservation a = confirmed(13, 0, 14, 0);
        Reservation b = confirmed(14, 0, 14, 30);
        Reservation c = confirmed(15, 0, 15, 30);
        Reservation d = confirmed(17, 0, 18, 0);

        assertThat(affectedIds()).containsExactly(b.getId(), c.getId())
                .doesNotContain(a.getId(), d.getId());
    }

    @Test
    @DisplayName("예약이 장애 구간을 통째로 포함하거나 앞·뒤로 일부만 겹쳐도 영향 예약이다")
    void partialAndCoveringOverlap() {
        Reservation covering = confirmed(13, 0, 18, 0);
        Reservation overlapsStart = confirmed(13, 30, 14, 1);
        Reservation overlapsEnd = confirmed(16, 59, 17, 30);
        Reservation inside = confirmed(14, 0, 17, 0);

        assertThat(affectedIds()).containsExactlyInAnyOrder(covering.getId(), overlapsStart.getId(),
                overlapsEnd.getId(), inside.getId());
    }

    @Test
    @DisplayName("결과의 의뢰 ID는 예약이 실제로 연결된 의뢰 ID다")
    void referralIdComesFromReservation() {
        Reservation reservation = confirmed(15, 0, 16, 0);

        List<AffectedReservation> affected = incidentReservationRepository.findAffected(equipmentId,
                INCIDENT_START, INCIDENT_END);

        assertThat(affected).containsExactly(
                new AffectedReservation(reservation.getId(), reservation.getReferral().getId()));
    }

    @Test
    @DisplayName("다른 장비의 예약은 시간이 겹쳐도 제외된다")
    void otherEquipmentExcluded() {
        reservation(UUID.randomUUID(), 15, 0, 16, 0, ReservationStatus.CONFIRMED);

        assertThat(affectedIds()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("CONFIRMED가 아닌 예약은 시간이 겹쳐도 제외된다 (BLOCKED는 의뢰가 재조정 대기 중인 현재 예약일 때만 대상)")
    void nonConfirmedExcluded(ReservationStatus status) {
        reservation(equipmentId, 15, 0, 16, 0, status);

        assertThat(affectedIds()).isEmpty();
    }

    // 장비 장애로 차단된 예약: 의뢰에 연결 → (확정) → 차단 → 의뢰 재조정 필요. afterBlock으로 이후 의뢰 변화를 더한다
    private Reservation equipmentBlocked(UUID equipmentId, int startHour, int endHour, boolean confirmedBeforeBlock,
                                         Consumer<Referral> afterBlock) {
        Referral referral = referralRepository.save(Referral.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "가상환자", UUID.randomUUID()));
        LocalDateTime start = INCIDENT_START.withHour(startHour);
        Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), UUID.randomUUID(), equipmentId,
                start, start, INCIDENT_START.withHour(endHour));
        reservation.hold();
        if (confirmedBeforeBlock) {
            reservation.confirm();
        }
        reservationRepository.saveAndFlush(reservation);
        referral.assignReservation(reservation.getId());
        if (confirmedBeforeBlock) {
            referral.confirm();
        }
        reservation.block();
        referral.requestReschedule();
        afterBlock.accept(referral);
        referralRepository.saveAndFlush(referral);
        return reservationRepository.saveAndFlush(reservation);
    }

    private Reservation equipmentBlockedAfterConfirm(Consumer<Referral> afterBlock) {
        return equipmentBlocked(equipmentId, 15, 16, true, afterBlock);
    }

    private static void noChange(Referral referral) {
    }

    @Test
    @DisplayName("확정 뒤 장비 장애로 차단되고 의뢰가 아직 이 예약으로 재조정 대기 중이면 영향 예약이다 (SlotsBlocked가 먼저 처리된 경우)")
    void equipmentBlockedAfterConfirmIncluded() {
        Reservation blocked = equipmentBlockedAfterConfirm(IncidentReservationRepositoryTest::noChange);
        Reservation confirmed = confirmed(14, 0, 15, 0);

        assertThat(incidentReservationRepository.findAffected(equipmentId, INCIDENT_START, INCIDENT_END))
                .containsExactly(new AffectedReservation(confirmed.getId(), confirmed.getReferral().getId()),
                        new AffectedReservation(blocked.getId(), blocked.getReferral().getId()));
    }

    @Test
    @DisplayName("확정 전(HELD)에 차단된 예약은 확정 예약이 아니었으므로 제외된다")
    void equipmentBlockedBeforeConfirmExcluded() {
        equipmentBlocked(equipmentId, 15, 16, false, IncidentReservationRepositoryTest::noChange);

        assertThat(affectedIds()).isEmpty();
    }

    @Test
    @DisplayName("차단된 예약의 의뢰가 이미 새 예약으로 재예약 중이면 제외된다")
    void equipmentBlockedButRescheduledExcluded() {
        equipmentBlockedAfterConfirm(referral -> referral.reschedule(UUID.randomUUID()));

        assertThat(affectedIds()).isEmpty();
    }

    @Test
    @DisplayName("차단된 예약의 의뢰가 취소됐으면 제외된다")
    void equipmentBlockedButCanceledExcluded() {
        equipmentBlockedAfterConfirm(Referral::cancel);

        assertThat(affectedIds()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "COMPLETED"})
    @DisplayName("차단된 예약이라도 의뢰의 검사가 이미 시작·완료됐으면 제외된다")
    void equipmentBlockedWithStartedExaminationExcluded(String examinationStatus) {
        Reservation blocked = equipmentBlockedAfterConfirm(IncidentReservationRepositoryTest::noChange);
        Examination examination = Examination.schedule(blocked.getReferral().getId(), blocked.getId(),
                UUID.randomUUID());
        // 검사 시작·완료는 조건부 UPDATE로만 바뀌어 도메인 메서드가 없으므로 상태 필드를 직접 채운다
        ReflectionTestUtils.setField(examination, "status", ExaminationStatus.valueOf(examinationStatus));
        examinationRepository.saveAndFlush(examination);

        assertThat(affectedIds()).isEmpty();
    }

    @Test
    @DisplayName("차단된 예약도 검사가 SCHEDULED면 영향 예약이다")
    void equipmentBlockedWithScheduledExaminationIncluded() {
        Reservation blocked = equipmentBlockedAfterConfirm(IncidentReservationRepositoryTest::noChange);
        examinationRepository.saveAndFlush(Examination.schedule(blocked.getReferral().getId(), blocked.getId(),
                UUID.randomUUID()));

        assertThat(affectedIds()).containsExactly(blocked.getId());
    }

    @Test
    @DisplayName("차단된 예약도 다른 장비이거나 시간이 겹치지 않으면 제외된다")
    void equipmentBlockedOtherEquipmentOrTimeExcluded() {
        equipmentBlocked(UUID.randomUUID(), 15, 16, true, IncidentReservationRepositoryTest::noChange);
        equipmentBlocked(equipmentId, 17, 18, true, IncidentReservationRepositoryTest::noChange);
        equipmentBlocked(equipmentId, 12, 14, true, IncidentReservationRepositoryTest::noChange);

        assertThat(affectedIds()).isEmpty();
    }

    @Test
    @DisplayName("삭제(soft delete)된 예약은 제외된다")
    void softDeletedExcluded() {
        Reservation deleted = confirmed(15, 0, 16, 0);
        // CONFIRMED 예약은 도메인 메서드로 삭제할 수 없어 deleted_at만 직접 채워 삭제된 행을 재현한다
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
        reservationRepository.saveAndFlush(deleted);
        Reservation alive = confirmed(15, 0, 16, 0);

        assertThat(affectedIds()).containsExactly(alive.getId());
    }

    @Test
    @DisplayName("겹치는 예약이 없으면 빈 목록이다")
    void noAffectedReservation() {
        confirmed(9, 0, 10, 0);

        assertThat(affectedIds()).isEmpty();
    }
}
