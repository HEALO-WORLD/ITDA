package healoworld.referral_service.global.config;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import healoworld.referral_service.global.exception.BaseException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Slf4j
@Configuration
public class KafkaConfig {

    private static final String[] MDC_HEADER_KEYS = {"eventId", "eventType"};
    // DeadLetterPublishingRecoverer 기본 규칙의 DLT 토픽 이름 접미사 (docker-compose의 kafka-init이 같은 이름으로 미리 만든다)
    static final String DLT_SUFFIX = "-dlt";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // 실패한 메시지를 "원본토픽-dlt"의 같은 파티션으로 보낸다
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        // 1초 간격으로 3번 재시도 (최초 시도 포함 총 4번)
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));

        // 다시 해도 결과가 같은 예외는 재시도 없이 바로 DLT로
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                JacksonException.class
        );

        // 기본 설정은 재시도 중 "Record in retry" 한 줄만 남기고 원인 예외를 남기지 않는다. 시도마다·DLT 이동 시 원인을 기록한다
        errorHandler.setRetryListeners(new FailureLoggingRetryListener());

        return errorHandler;
    }

    // 처리 실패를 시도마다(WARN), DLT로 넘길 때(ERROR) 기록한다. 재시도·DLT 동작 자체는 바꾸지 않는다
    static class FailureLoggingRetryListener implements RetryListener {

        @Override
        public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
            log.warn("Kafka 메시지 처리 실패. topic={}, partition={}, offset={}, eventId={}, eventType={}, attempt={}, cause={}",
                    record.topic(), record.partition(), record.offset(), header(record, "eventId"),
                    header(record, "eventType"), deliveryAttempt, describe(ex));
        }

        // DLT 전송에 성공한 뒤 호출된다. 원본 위치(topic·partition·offset)와 보낸 DLT 토픽(같은 파티션)을 남긴다
        @Override
        public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
            log.error("Kafka 메시지 재시도 종료, DLT로 이동. topic={}, partition={}, offset={}, dltTopic={}, eventId={}, eventType={}, cause={}",
                    record.topic(), record.partition(), record.offset(), record.topic() + DLT_SUFFIX,
                    header(record, "eventId"), header(record, "eventType"), describe(ex));
        }

        // DLT 전송이 실패하면 메시지는 커밋되지 않고 같은 위치에서 다시 처리된다 (재시도 상태 초기화 후 반복)
        @Override
        public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
            log.error("Kafka 메시지 DLT 전송 실패, 같은 위치에서 다시 처리. topic={}, partition={}, offset={}, dltTopic={}, eventId={}, eventType={}, cause={}, dltFailure={}",
                    record.topic(), record.partition(), record.offset(), record.topic() + DLT_SUFFIX,
                    header(record, "eventId"), header(record, "eventType"), describe(original), describe(failure));
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    // 로그에 남길 실패 원인. 메시지 본문(payload)과 일반 예외의 메시지는 쓰지 않는다
    // (DB 제약 위반 메시지 등에는 행의 실제 값이 들어갈 수 있다). 업무 예외는 고정 문구인 ErrorCode와 메시지를 쓴다
    private static String describe(Exception ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof BaseException baseException) {
                return "BaseException errorCode=" + baseException.getErrorCode().name()
                        + " message=" + baseException.getErrorCode().getMessage();
            }
        }
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        String description = root.getClass().getSimpleName();
        if (root instanceof SQLException sqlException && sqlException.getSQLState() != null) {
            description += " sqlState=" + sqlException.getSQLState();
        }
        return description;
    }

    /**
     * Kafka 메시지 헤더의 eventId, eventType을 MDC에 넣어 소비 중 남기는 로그에 함께 출력한다.
     * Boot가 {@code RecordInterceptor<Object, Object>} 타입 빈을 리스너 컨테이너에 자동 연결한다.
     */
    @Bean
    public RecordInterceptor<Object, Object> mdcRecordInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                            Consumer<Object, Object> consumer) {
                for (String key : MDC_HEADER_KEYS) {
                    Header header = record.headers().lastHeader(key);
                    if (header != null) {
                        MDC.put(key, new String(header.value(), StandardCharsets.UTF_8));
                    } else {
                        MDC.remove(key);
                    }
                }
                return record;
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                for (String key : MDC_HEADER_KEYS) {
                    MDC.remove(key);
                }
            }
        };
    }
}
