package healoworld.referral_service.global.config;

import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

// Kafka 처리 실패 로그에 원인·이벤트 정보가 남고, 메시지 본문과 일반 예외 메시지(개인정보 가능)는 남지 않는지 확인한다
@ExtendWith(OutputCaptureExtension.class)
class FailureLoggingRetryListenerTest {

    private static final String EVENT_ID = "93f6598a-2e53-402b-8156-198fa7ea59e1";
    private static final String PAYLOAD = "{\"patientName\":\"가상환자\"}";

    private final KafkaConfig.FailureLoggingRetryListener listener = new KafkaConfig.FailureLoggingRetryListener();

    @Test
    @DisplayName("업무 예외로 실패하면 시도 횟수·이벤트 ID·타입·ErrorCode를 기록한다")
    void logsBusinessFailure(CapturedOutput output) {
        BaseException cause = new BaseException(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);

        listener.failedDelivery(record(), new ListenerExecutionFailedException("listener failed", cause), 2);

        assertThat(output).contains("Kafka 메시지 처리 실패", "topic=slot-results", "eventId=" + EVENT_ID,
                "eventType=SlotBooked", "attempt=2", "errorCode=EXAMINATION_RESERVATION_NOT_CHANGEABLE",
                ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE.getMessage());
        assertThat(output).doesNotContain(PAYLOAD);
    }

    @Test
    @DisplayName("DB 예외는 예외 종류와 SQLState만 남기고 원문 메시지(행 값 포함 가능)는 남기지 않는다")
    void dbFailureHidesRawMessage(CapturedOutput output) {
        SQLException sql = new SQLException("Failing row contains (가상환자, 010-0000-0000)", "23502");

        listener.recovered(record(), new DataIntegrityViolationException("constraint", sql));

        assertThat(output).contains("DLT로 이동", "eventId=" + EVENT_ID, "SQLException sqlState=23502");
        assertThat(output).doesNotContain("Failing row", "가상환자", "010-0000-0000", PAYLOAD);
    }

    @Test
    @DisplayName("DLT로 보낼 때 원본 위치(topic·partition·offset)와 보낸 DLT 토픽을 기록한다")
    void recoveredLogsSourceAndDltTopic(CapturedOutput output) {
        listener.recovered(record(), new BaseException(ErrorCode.EQUIPMENT_INCIDENT_EVENT_CONFLICT));

        assertThat(output).contains("topic=slot-results", "partition=2", "offset=211", "dltTopic=slot-results-dlt",
                "eventType=SlotBooked", "errorCode=EQUIPMENT_INCIDENT_EVENT_CONFLICT");
    }

    @Test
    @DisplayName("DLT 전송 실패는 원래 원인과 DLT 전송 실패 원인을 함께 기록하고 원문 메시지는 남기지 않는다")
    void recoveryFailedLogsBothCauses(CapturedOutput output) {
        IllegalStateException original = new IllegalStateException("가상환자 처리 중 오류");
        RuntimeException dltFailure = new RuntimeException("broker down",
                new org.apache.kafka.common.errors.TimeoutException("메타데이터 대기 초과"));

        listener.recoveryFailed(record(), original, dltFailure);

        assertThat(output).contains("DLT 전송 실패", "eventId=" + EVENT_ID, "dltTopic=slot-results-dlt",
                "cause=IllegalStateException", "dltFailure=TimeoutException");
        assertThat(output).doesNotContain("가상환자", "broker down", "메타데이터 대기 초과", PAYLOAD);
    }

    @Test
    @DisplayName("업무 예외가 아닌 일반 예외는 메시지에 개인정보가 있어도 예외 종류만 남긴다")
    void genericExceptionMessageIsHidden(CapturedOutput output) {
        listener.failedDelivery(record(), new IllegalArgumentException("patientName=가상환자 형식 오류"), 1);

        assertThat(output).contains("attempt=1", "cause=IllegalArgumentException");
        assertThat(output).doesNotContain("가상환자", "patientName", PAYLOAD);
    }

    @Test
    @DisplayName("eventId·eventType 헤더가 없는 메시지도 예외 없이 null로 기록한다")
    void missingHeadersAreLoggedAsNull(CapturedOutput output) {
        ConsumerRecord<String, String> noHeaders = new ConsumerRecord<>("equipment-events", 0, 5L, "key", PAYLOAD);

        listener.failedDelivery(noHeaders, new IllegalStateException("x"), 1);

        assertThat(output).contains("topic=equipment-events", "eventId=null", "eventType=null");
    }

    private ConsumerRecord<String, String> record() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("slot-results", 2, 211L, "slot-key", PAYLOAD);
        record.headers().add("eventId", EVENT_ID.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", "SlotBooked".getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
