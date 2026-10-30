package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class EquipmentIncidentService {

    // PostgreSQL unique_violation
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final int REASON_MAX_LENGTH = 500;

    private final EquipmentIncidentRepository equipmentIncidentRepository;

    // 항상 새 트랜잭션을 열어 실행한다. UNIQUE 위반으로 한 트랜잭션이 실패해도 다음 조회는 별도 트랜잭션에서 실행하기 위해 쓴다
    private final TransactionTemplate newTransaction;

    public EquipmentIncidentService(EquipmentIncidentRepository equipmentIncidentRepository,
                                    PlatformTransactionManager transactionManager) {
        this.equipmentIncidentRepository = equipmentIncidentRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 장비 장애 이벤트 1건을 장애로 등록하고 장애 ID를 돌려준다
    // 같은 sourceEventId가 다시 전달되면 새로 만들지 않고 기존 장애 ID를 돌려준다 (Kafka 재전달 대비 멱등)
    //
    // 트랜잭션: 호출한 쪽 트랜잭션에 참여하지 않는다(NOT_SUPPORTED). DB 작업은 모두 newTransaction 안에서만 한다
    //  1) [트랜잭션 A] 조회 → 없으면 저장. 동시에 들어온 같은 이벤트가 먼저 커밋했다면 저장이 UNIQUE 위반으로 실패하고 A는 롤백된다
    //  2) [트랜잭션 B] A가 UNIQUE 위반으로 실패했을 때만, 새 트랜잭션에서 먼저 저장된 장애를 다시 읽는다
    //     (오류가 난 PostgreSQL 트랜잭션에서는 더 이상 SQL을 실행할 수 없으므로 같은 트랜잭션에서 재조회하지 않는다)
    // 트랜잭션 밖에서 DB에 접근하지 않으므로 한 번에 DB 연결을 하나만 사용한다
    // TODO: 이벤트 계약(필드 이름·형식)이 확정되면 Kafka Consumer에서 이 메서드를 호출한다
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UUID register(UUID sourceEventId, UUID equipmentId, UUID institutionId, String reason,
                         LocalDateTime occurredAt, LocalDateTime startAt, LocalDateTime endAt) {
        validate(sourceEventId, equipmentId, institutionId, reason, occurredAt, startAt, endAt);

        // DB(TIMESTAMP)는 마이크로초까지만 저장하므로 저장 값과 중복 비교 값을 같은 정밀도로 맞춘다
        LocalDateTime occurred = occurredAt.truncatedTo(ChronoUnit.MICROS);
        LocalDateTime start = startAt.truncatedTo(ChronoUnit.MICROS);
        LocalDateTime end = endAt.truncatedTo(ChronoUnit.MICROS);

        try {
            return newTransaction.execute(status -> {
                // 대부분의 재전달은 여기서 걸러진다
                Optional<EquipmentIncident> existing = equipmentIncidentRepository.findBySourceEventId(sourceEventId);
                if (existing.isPresent()) {
                    return verifySameEvent(existing.get(), equipmentId, institutionId, occurred, start, end);
                }
                return equipmentIncidentRepository.saveAndFlush(EquipmentIncident.occur(sourceEventId, equipmentId,
                        institutionId, reason, occurred, start, end)).getId();
            });
        } catch (DataIntegrityViolationException e) {
            if (!isUniqueViolation(e)) {
                throw e;
            }
            // 같은 이벤트가 동시에 들어와 다른 요청이 먼저 저장했다
            EquipmentIncident saved = newTransaction.execute(status ->
                    equipmentIncidentRepository.findBySourceEventId(sourceEventId)).orElseThrow(() -> e);
            return verifySameEvent(saved, equipmentId, institutionId, occurred, start, end);
        }
    }

    // 같은 이벤트 ID인데 장애 내용이 다르면 단순 재전달이 아니므로 기존 장애를 덮어쓰거나 그대로 돌려주지 않는다
    private UUID verifySameEvent(EquipmentIncident incident, UUID equipmentId, UUID institutionId,
                                 LocalDateTime occurredAt, LocalDateTime startAt, LocalDateTime endAt) {
        if (!incident.isSameEvent(equipmentId, institutionId, occurredAt, startAt, endAt)) {
            throw new BaseException(ErrorCode.EQUIPMENT_INCIDENT_EVENT_CONFLICT);
        }
        return incident.getId();
    }

    private void validate(UUID sourceEventId, UUID equipmentId, UUID institutionId, String reason,
                          LocalDateTime occurredAt, LocalDateTime startAt, LocalDateTime endAt) {
        if (sourceEventId == null || equipmentId == null || institutionId == null
                || occurredAt == null || startAt == null || endAt == null
                || reason == null || reason.isBlank() || reason.length() > REASON_MAX_LENGTH) {
            throw new BaseException(ErrorCode.VALIDATION_FAILED);
        }
        // 영향 구간은 시작이 종료보다 앞서야 한다 (길이 0인 구간은 겹치는 예약이 없으므로 의미가 없다)
        // 장애는 영향 구간이 끝난 뒤에 발생할 수 없다
        // TODO: 발생 시각과 영향 시작 시각의 관계(사후 등록·예정 점검 허용 범위)는 이벤트 계약과 함께 확정
        if (!startAt.isBefore(endAt) || occurredAt.isAfter(endAt)) {
            throw new BaseException(ErrorCode.INVALID_EQUIPMENT_INCIDENT_PERIOD);
        }
    }

    private boolean isUniqueViolation(DataIntegrityViolationException e) {
        return NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException sqlException
                && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState());
    }
}
