package healoworld.referral_service.global.config;

import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 운영과 같은 KafkaConfig.kafkaErrorHandler()(재시도 1초×3, 재시도 제외 예외, DLT 전송, 실패 로깅)를
 * 브로커 없이 실행해 재시도 횟수·DLT 전송·DLT 전송 실패 동작을 확인한다.
 * <p>
 * - KafkaTemplate은 Mock이라 실제 DLT 토픽 쓰기·파티션 존재 여부는 확인하지 않는다 (실제 브로커가 필요한 검증)
 * - 리스너 컨테이너가 실패할 때마다 호출하는 handleOne()을 직접 불러 "같은 메시지가 다시 실패하는 상황"을 재현한다
 */
@ExtendWith(OutputCaptureExtension.class)
class KafkaErrorHandlerPolicyTest {

    private static final String EVENT_ID = "93f6598a-2e53-402b-8156-198fa7ea59e1";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final DefaultErrorHandler errorHandler = new KafkaConfig().kafkaErrorHandler(kafkaTemplate);

    @Test
    @DisplayName("재시도할 수 있는 예외는 최초 포함 4번째 실패에서 같은 파티션의 <토픽>-dlt로 1번 보내고, 시도마다 실패를 기록한다")
    void retryableFailureGoesToDltAfterFourAttempts(CapturedOutput output) {
        givenDltSendSucceeds();
        ConsumerRecord<String, String> record = record();
        Exception failure = listenerFailure(new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        List<Boolean> recovered = new ArrayList<>();
        for (int attempt = 0; attempt < 4; attempt++) {
            recovered.add(errorHandler.handleOne(failure, record, consumer, container));
        }

        assertThat(recovered).containsExactly(false, false, false, true);
        ProducerRecord<String, String> dlt = sentToDlt();
        assertThat(dlt.topic()).isEqualTo("slot-results-dlt");
        assertThat(dlt.partition()).isEqualTo(2);
        assertThat(dlt.key()).isEqualTo(record.key());
        assertThat(dlt.value()).isEqualTo(record.value());
        assertThat(new String(dlt.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8)).isEqualTo(EVENT_ID);
        assertThat(output).contains("attempt=1", "attempt=2", "attempt=3",
                "DLT로 이동", "dltTopic=slot-results-dlt", "errorCode=RESERVATION_NOT_FOUND");
    }

    @Test
    @DisplayName("재시도해도 결과가 같은 예외(형식 오류·JSON 파싱 오류)는 재시도 없이 첫 실패에서 DLT로 보낸다")
    void notRetryableFailureGoesToDltImmediately(CapturedOutput output) {
        givenDltSendSucceeds();
        Exception parseFailure = catchThrowableOfJson();

        assertThat(errorHandler.handleOne(listenerFailure(new IllegalArgumentException("UUID 형식 오류")),
                record(), consumer, container)).isTrue();
        assertThat(errorHandler.handleOne(listenerFailure(parseFailure), record(), consumer, container)).isTrue();

        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        assertThat(output).contains("cause=IllegalArgumentException", "DLT로 이동");
    }

    @Test
    @DisplayName("DLT 전송이 실패하면 처리 완료로 보지 않고(커밋 안 함) 실패를 기록하며, 다음 처리에서 재시도 횟수를 처음부터 다시 센다")
    void dltSendFailureIsNotRecoveredAndRetriedAgain(CapturedOutput output) {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        ConsumerRecord<String, String> record = record();
        Exception failure = listenerFailure(new IllegalArgumentException("형식 오류"));

        // 전송 실패면 false(복구 안 됨)를 돌려줘 컨테이너가 같은 오프셋을 다시 읽게 한다 (커밋·유실 없음)
        assertThat(errorHandler.handleOne(failure, record, consumer, container)).isFalse();
        assertThat(ourLogs(output)).contains("DLT 전송 실패", "eventId=" + EVENT_ID, "dltTopic=slot-results-dlt",
                "cause=IllegalArgumentException");
        // 우리 로그에는 예외 원문을 남기지 않는다 (Spring DLPR·DefaultErrorHandler 자체 로그는 예외 스택을 출력한다)
        assertThat(ourLogs(output)).doesNotContain("broker down", "형식 오류");
        // 메시지 본문은 Spring 자체 로그를 포함해 어디에도 남지 않는다 (레코드는 topic-partition@offset으로만 표시)
        assertThat(output).doesNotContain("가상");

        // 같은 메시지가 다시 실패하면 DLT 전송을 다시 시도하고, 계속 실패하면 계속 복구되지 않는다 (파티션이 이 메시지에서 멈춤)
        assertThat(errorHandler.handleOne(failure, record, consumer, container)).isFalse();
        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    @DisplayName("재시도 가능한 예외에서 DLT 전송이 실패하면 재시도 횟수를 초기화해 다시 4번 시도한 뒤 DLT 전송을 다시 시도한다")
    void dltSendFailureResetsRetryState(CapturedOutput output) {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        ConsumerRecord<String, String> record = record();
        Exception failure = listenerFailure(new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        List<Boolean> firstCycle = new ArrayList<>();
        for (int attempt = 0; attempt < 4; attempt++) {
            firstCycle.add(errorHandler.handleOne(failure, record, consumer, container));
        }
        assertThat(firstCycle).containsOnly(false);
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));

        // 다음 실패는 1번째 시도로 다시 세므로 바로 DLT로 보내지 않는다
        assertThat(errorHandler.handleOne(failure, record, consumer, container)).isFalse();
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        assertThat(ourLogs(output).split("attempt=1,", -1)).hasSize(3);   // attempt=1이 두 번 기록됨
    }

    // KafkaConfig(우리 로거)가 남긴 줄만 모은다
    private static String ourLogs(CapturedOutput output) {
        return output.getAll().lines()
                .filter(line -> line.contains("KafkaConfig"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    @Test
    @DisplayName("재시도 중 성공하면 DLT로 보내지 않는다 (일시 오류 복구)")
    void transientFailureDoesNotReachDlt() {
        givenDltSendSucceeds();
        Exception failure = listenerFailure(new IllegalStateException("일시 오류"));

        assertThat(errorHandler.handleOne(failure, record(), consumer, container)).isFalse();
        assertThat(errorHandler.handleOne(failure, record(), consumer, container)).isFalse();
        // 세 번째 시도는 리스너가 성공했다고 가정: 오류 처리기가 더 호출되지 않는다

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    // DLT 토픽은 docker-compose kafka-init이 원본과 같은 3개 파티션으로 만든다. 같은 파티션 번호로 보내려면 그 파티션이 있어야 한다
    private void givenDltSendSucceeds() {
        @SuppressWarnings("unchecked")
        SendResult<String, String> result = mock(SendResult.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(result));
        // DLPR은 컨슈머의 partitionsFor()로 대상 파티션이 있는지 확인한다
        when(consumer.partitionsFor(eq("slot-results-dlt"), any(Duration.class))).thenReturn(List.of(
                new PartitionInfo("slot-results-dlt", 0, null, null, null),
                new PartitionInfo("slot-results-dlt", 1, null, null, null),
                new PartitionInfo("slot-results-dlt", 2, null, null, null)));
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> sentToDlt() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(1)).send(captor.capture());
        return captor.getValue();
    }

    // 리스너가 던진 예외는 컨테이너가 ListenerExecutionFailedException으로 감싸 전달한다
    private static Exception listenerFailure(Exception cause) {
        return new ListenerExecutionFailedException("listener failed", cause);
    }

    private static Exception catchThrowableOfJson() {
        try {
            JsonMapper.builder().build().readTree("{깨진 JSON");
            throw new AssertionError("JSON 파싱이 실패해야 한다");
        } catch (JacksonException e) {
            return e;
        }
    }

    private static ConsumerRecord<String, String> record() {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("slot-results", 2, 211L, "slot-key", "{\"reservationId\":\"가상\"}");
        record.headers().add("eventId", EVENT_ID.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", "SlotBooked".getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
