package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// 입력 검증과 "조회 후 다른 요청이 먼저 저장한 경우"(UNIQUE 위반) 분기를 Mock으로 검증한다
@ExtendWith(MockitoExtension.class)
class EquipmentIncidentServiceTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 2, 9, 0);
    private static final LocalDateTime START_AT = OCCURRED_AT;
    private static final LocalDateTime END_AT = OCCURRED_AT.plusHours(2);

    @Mock
    private EquipmentIncidentRepository equipmentIncidentRepository;

    // TransactionTemplate이 사용할 트랜잭션 매니저. Mock이라 실제 트랜잭션 없이 콜백만 실행된다
    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private EquipmentIncidentService equipmentIncidentService;

    private final UUID sourceEventId = UUID.randomUUID();
    private final UUID equipmentId = UUID.randomUUID();
    private final UUID institutionId = UUID.randomUUID();

    private static void assertErrorCode(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private UUID register() {
        return equipmentIncidentService.register(sourceEventId, equipmentId, institutionId, "가상 장애 사유",
                OCCURRED_AT, START_AT, END_AT);
    }

    private EquipmentIncident savedIncident(UUID equipmentId) {
        EquipmentIncident incident = EquipmentIncident.occur(sourceEventId, equipmentId, institutionId,
                "가상 장애 사유", OCCURRED_AT, START_AT, END_AT);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        return incident;
    }

    private void givenUniqueViolationOnSave() {
        given(equipmentIncidentRepository.saveAndFlush(any(EquipmentIncident.class))).willThrow(
                new DataIntegrityViolationException("duplicate", new SQLException("duplicate key", "23505")));
    }

    @Test
    @DisplayName("저장 직전 다른 요청이 같은 이벤트를 먼저 저장했다면(UNIQUE 위반) 그 장애 ID를 돌려준다")
    void concurrentDuplicateReturnsExisting() {
        EquipmentIncident winner = savedIncident(equipmentId);
        given(equipmentIncidentRepository.findBySourceEventId(sourceEventId))
                .willReturn(Optional.empty(), Optional.of(winner));
        givenUniqueViolationOnSave();

        assertThat(register()).isEqualTo(winner.getId());
    }

    @Test
    @DisplayName("동시에 먼저 저장된 장애의 내용이 다르면 EQUIPMENT_INCIDENT_EVENT_CONFLICT")
    void concurrentDuplicateWithOtherContent() {
        given(equipmentIncidentRepository.findBySourceEventId(sourceEventId))
                .willReturn(Optional.empty(), Optional.of(savedIncident(UUID.randomUUID())));
        givenUniqueViolationOnSave();

        assertErrorCode(this::register, ErrorCode.EQUIPMENT_INCIDENT_EVENT_CONFLICT);
    }

    @Test
    @DisplayName("UNIQUE가 아닌 다른 제약 위반은 숨기지 않고 그대로 던지며 재조회하지 않는다")
    void otherViolationIsRethrown() {
        given(equipmentIncidentRepository.findBySourceEventId(sourceEventId)).willReturn(Optional.empty());
        given(equipmentIncidentRepository.saveAndFlush(any(EquipmentIncident.class))).willThrow(
                new DataIntegrityViolationException("not null", new SQLException("null value", "23502")));

        assertThatThrownBy(this::register).isInstanceOf(DataIntegrityViolationException.class);
        then(equipmentIncidentRepository).should().findBySourceEventId(sourceEventId); // 처음 1번만 조회
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sourceEventId", "equipmentId", "institutionId", "reason", "occurredAt", "startAt",
            "endAt", "blankReason", "longReason"})
    @DisplayName("필수 값이 없거나 사유가 비었거나 500자를 넘으면 VALIDATION_FAILED이고 DB에 접근하지 않는다")
    void requiredValues(String field) {
        assertErrorCode(() -> equipmentIncidentService.register(
                field.equals("sourceEventId") ? null : sourceEventId,
                field.equals("equipmentId") ? null : equipmentId,
                field.equals("institutionId") ? null : institutionId,
                switch (field) {
                    case "reason" -> null;
                    case "blankReason" -> "   ";
                    case "longReason" -> "가".repeat(501);
                    default -> "가상 장애 사유";
                },
                field.equals("occurredAt") ? null : OCCURRED_AT,
                field.equals("startAt") ? null : START_AT,
                field.equals("endAt") ? null : END_AT), ErrorCode.VALIDATION_FAILED);

        then(equipmentIncidentRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("사유가 정확히 500자면 허용한다")
    void reasonOf500Chars() {
        given(equipmentIncidentRepository.findBySourceEventId(sourceEventId)).willReturn(Optional.empty());
        given(equipmentIncidentRepository.saveAndFlush(any(EquipmentIncident.class))).willReturn(
                savedIncident(equipmentId));

        equipmentIncidentService.register(sourceEventId, equipmentId, institutionId, "가".repeat(500),
                OCCURRED_AT, START_AT, END_AT);

        then(equipmentIncidentRepository).should().saveAndFlush(any(EquipmentIncident.class));
    }

    @Test
    @DisplayName("영향 시작이 종료와 같거나 늦으면 INVALID_EQUIPMENT_INCIDENT_PERIOD")
    void startMustBeBeforeEnd() {
        assertErrorCode(() -> equipmentIncidentService.register(sourceEventId, equipmentId, institutionId, "사유",
                OCCURRED_AT, END_AT, END_AT), ErrorCode.INVALID_EQUIPMENT_INCIDENT_PERIOD);
        assertErrorCode(() -> equipmentIncidentService.register(sourceEventId, equipmentId, institutionId, "사유",
                OCCURRED_AT, END_AT, START_AT), ErrorCode.INVALID_EQUIPMENT_INCIDENT_PERIOD);
        then(equipmentIncidentRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("발생 시각이 영향 종료보다 늦으면 INVALID_EQUIPMENT_INCIDENT_PERIOD")
    void occurredAtMustNotBeAfterEnd() {
        assertErrorCode(() -> equipmentIncidentService.register(sourceEventId, equipmentId, institutionId, "사유",
                END_AT.plusMinutes(1), START_AT, END_AT), ErrorCode.INVALID_EQUIPMENT_INCIDENT_PERIOD);
        then(equipmentIncidentRepository).shouldHaveNoInteractions();
    }
}
