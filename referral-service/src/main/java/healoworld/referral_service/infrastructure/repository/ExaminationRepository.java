package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Examination;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExaminationRepository extends JpaRepository<Examination, UUID> {

    Optional<Examination> findByReferralId(UUID referralId);

    // 검사 ID로 의뢰 ID만 조회한다 (엔티티를 영속성 컨텍스트에 올리지 않는 스칼라 조회)
    // 검사 시작은 이 의뢰 ID로 의뢰 → 예약 → 검사 순서로 잠근 뒤 검사를 처음 읽어야 잠금 이후의 최신 상태를 본다
    @Query("select e.referralId from Examination e where e.id = :id")
    Optional<UUID> findReferralIdById(@Param("id") UUID id);

    // SELECT ... FOR UPDATE: 장비 장애 후속 처리와 검사 시작이 검사 상태를 확인하는 동안 다른 검사 상태 UPDATE가 끼어들지 못하게 한다
    // 두 작업의 잠금 순서는 모두 의뢰 → 예약 → 검사다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Examination e where e.referralId = :referralId")
    Optional<Examination> findByReferralIdForUpdate(@Param("referralId") UUID referralId);

    // 여러 의뢰의 검사 연결 예약·상태를 한 번에 조회한다 (장애 영향 의뢰 목록의 수동 확인 표시용, 읽기 전용·잠금 없음)
    // 의뢰당 검사는 1건(referral_id UNIQUE)이므로 의뢰 ID마다 최대 1행이다. 검사가 없는 의뢰는 결과에 없다
    @Query("""
            select new healoworld.referral_service.infrastructure.repository.ExaminationProgress(
                       e.referralId, e.reservationId, e.status)
              from Examination e
             where e.referralId in :referralIds
            """)
    List<ExaminationProgress> findProgressByReferralIds(@Param("referralIds") Collection<UUID> referralIds);

    // 주어진 예약 중, 예약의 의뢰에 속한 검사가 이미 시작·완료된 예약 ID (정합성 복구의 SlotsBlocked 사전 확인용, 읽기 전용·잠금 없음)
    // 검사의 reservationId가 아니라 의뢰(referral_id)로 찾는다. SlotsBlocked 최종 확인(SlotResultService.onSlotsBlocked)과 같은 기준이라
    // 검사가 다른 예약에 연결돼 있어도 같은 판단을 한다
    @Query("""
            select r.id
              from Reservation r, Examination e
             where r.id in :reservationIds
               and e.referralId = r.referral.id
               and e.status in (healoworld.referral_service.domain.enums.ExaminationStatus.IN_PROGRESS,
                                healoworld.referral_service.domain.enums.ExaminationStatus.COMPLETED)
            """)
    List<UUID> findReservationIdsWithStartedExamination(@Param("reservationIds") Collection<UUID> reservationIds);

    // 검사기관별 검사 목록 조회
    // - 취소(CANCELED)·만료(EXPIRED)되거나 삭제(soft delete)된 의뢰의 검사는 제외한다.
    //   예약 취소 후에도 검사는 SCHEDULED로 남기 때문에, 제외하지 않으면 취소된 검사가 '검사 대기'로 보이고
    //   삭제된 의뢰는 목록 조립 시 의뢰를 찾지 못해 조회가 실패한다
    // - 필터를 쿼리에 두어 페이지 내용과 전체 건수(countQuery)가 같은 조건으로 계산되게 한다
    @Query(value = """
            select e from Examination e
             where e.examInstitutionId = :examInstitutionId
               and exists (select 1 from Referral r
                            where r.id = e.referralId
                              and r.referralStatus not in (
                                  healoworld.referral_service.domain.enums.ReferralStatus.CANCELED,
                                  healoworld.referral_service.domain.enums.ReferralStatus.EXPIRED)
                              and r.deletedAt is null)
            """,
            countQuery = """
            select count(e) from Examination e
             where e.examInstitutionId = :examInstitutionId
               and exists (select 1 from Referral r
                            where r.id = e.referralId
                              and r.referralStatus not in (
                                  healoworld.referral_service.domain.enums.ReferralStatus.CANCELED,
                                  healoworld.referral_service.domain.enums.ReferralStatus.EXPIRED)
                              and r.deletedAt is null)
            """)
    Page<Examination> findByExamInstitutionId(
            @Param("examInstitutionId") UUID examInstitutionId,
            Pageable pageable
    );

    // 특정 의뢰에 연결된 검사 기록의 개수를 조회한다
    // 동시 생성 요청 이후 검사가 정확히 1건만 저장됐는지 검증하기 위해 사용한다
    long countByReferralId(UUID referralId);

    // 아래 조건부 UPDATE는 WHERE 조건(현재 상태 등)이 맞을 때만 한 번 갱신하고, 갱신한 행 수(0 또는 1)를 돌려준다
    // 같은 행을 동시에 갱신하면 DB 행 잠금으로 순서가 정해지고, 뒤 요청은 바뀐 값으로 조건을 다시 검사해 0건이 된다

    // SCHEDULED → IN_PROGRESS. 시작 조건을 검증한 예약(expectedReservationId)에 아직 연결돼 있을 때만 시작한다
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Examination e
               set e.status = healoworld.referral_service.domain.enums.ExaminationStatus.IN_PROGRESS,
                   e.startedAt = :now, e.updatedAt = :now
             where e.id = :id
               and e.status = healoworld.referral_service.domain.enums.ExaminationStatus.SCHEDULED
               and e.reservationId = :expectedReservationId
            """)
    int startIfScheduled(@Param("id") UUID id, @Param("expectedReservationId") UUID expectedReservationId,
                         @Param("now") LocalDateTime now);

    // IN_PROGRESS → COMPLETED. started_at은 건드리지 않는다
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Examination e
               set e.status = healoworld.referral_service.domain.enums.ExaminationStatus.COMPLETED,
                   e.completedAt = :now, e.updatedAt = :now
             where e.id = :id
               and e.status = healoworld.referral_service.domain.enums.ExaminationStatus.IN_PROGRESS
            """)
    int completeIfInProgress(@Param("id") UUID id, @Param("now") LocalDateTime now);

    // 재예약 확정 시 기존 검사를 새 예약에 연결한다. 아직 시작 전(SCHEDULED)일 때만 바꿀 수 있다
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Examination e
               set e.reservationId = :reservationId, e.examInstitutionId = :examInstitutionId, e.updatedAt = :now
             where e.id = :id
               and e.status = healoworld.referral_service.domain.enums.ExaminationStatus.SCHEDULED
            """)
    int changeReservationIfScheduled(@Param("id") UUID id, @Param("reservationId") UUID reservationId,
                                     @Param("examInstitutionId") UUID examInstitutionId,
                                     @Param("now") LocalDateTime now);

}
