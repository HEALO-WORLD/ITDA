package healoworld.referral_service.global.config;

import healoworld.referral_service.global.exception.BaseException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

@Slf4j
@Configuration
public class KafkaConfig {

    private static final String[] MDC_HEADER_KEYS = {"eventId", "eventType"};
    public static final String DEAD_LETTER_CONTAINER_FACTORY = "deadLetterListenerContainerFactory";
    public static final String FAILURE_SUMMARY_HEADER = "x-failure-summary";
    // DeadLetterPublishingRecoverer 기본 규칙의 DLT 토픽 이름 접미사 (docker-compose의 kafka-init이 같은 이름으로 미리 만든다)
    static final String DLT_SUFFIX = "-dlt";
    // DLT 이동 로그에 남길 스택 줄 수 (예외·원인마다)
    static final int STACK_FRAMES_PER_CAUSE = 10;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // 실패한 메시지를 "원본토픽-dlt"의 같은 파티션으로 보낸다
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        // 예외 메시지 원문, 스택 트레이스 제거
        recoverer.excludeHeader(DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_MSG, DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd.EX_STACKTRACE);
        recoverer.setHeadersFunction((record, ex) -> new RecordHeaders().add(FAILURE_SUMMARY_HEADER, describe(ex).getBytes(StandardCharsets.UTF_8)));

        // 기본: 1초 간격으로 3번 재시도
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));

        // 다시 해도 결과가 같은 예외는 재시도 없이 바로 DLT로
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                JacksonException.class,
                BaseException.class
        );

        // DB 일시 장애는 지수 백오프로 5번 (1·2·4·8·10초, 약 25초), 그 외는 위 기본값
        errorHandler.setBackOffFunction((record, ex) -> backOffFor(ex));

        // 기본 설정은 재시도 중 "Record in retry" 한 줄만 남기고 원인 예외를 남기지 않는다. 시도마다·DLT 이동 시 원인을 기록한다
        errorHandler.setRetryListeners(new FailureLoggingRetryListener());

        return errorHandler;
    }

    static BackOff backOffFor(Exception ex) {
        if (!isTransient(ex)) {
            return null;
        }
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10000L);
        return backOff;
    }

    private static boolean isTransient(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
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
        // DLT 헤더에는 스택트레이스를 싣지 않으므로, 실패 위치를 찾을 수 있게 메시지를 뺀 스택을 여기서 한 번 남긴다
        @Override
        public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
            log.error("Kafka 메시지 재시도 종료, DLT로 이동. topic={}, partition={}, offset={}, dltTopic={}, eventId={}, eventType={}, cause={}\n{}",
                    record.topic(), record.partition(), record.offset(), record.topic() + DLT_SUFFIX,
                    header(record, "eventId"), header(record, "eventType"), describe(ex), describeStack(ex));
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

    // 로그에 남길 스택. 예외 클래스 이름과 호출 위치(at ...)만 쓰고 예외 메시지는 쓰지 않는다 (describe와 같은 이유)
    // 원인(cause)마다 앞쪽 STACK_FRAMES_PER_CAUSE줄만 남긴다
    // 리스너 예외를 감싼 ListenerExecutionFailedException은 Spring 컨테이너 내부 줄뿐이라 클래스 이름만 남긴다
    static String describeStack(Throwable ex) {
        StringBuilder stack = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());   // 순환 cause 방지
        for (Throwable cause = ex; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause != ex) {
                stack.append("Caused by: ");
            }
            stack.append(cause.getClass().getName()).append('\n');
            if (isListenerWrapper(cause)) {
                continue;
            }

            StackTraceElement[] frames = cause.getStackTrace();
            int shown = Math.min(frames.length, STACK_FRAMES_PER_CAUSE);
            for (int i = 0; i < shown; i++) {
                stack.append("\tat ").append(frames[i]).append('\n');
            }
            if (frames.length > shown) {
                stack.append("\t... ").append(frames.length - shown).append(" more\n");
            }
        }
        return stack.toString();
    }

    private static boolean isListenerWrapper(Throwable ex) {
        return ex instanceof ListenerExecutionFailedException && ex.getCause() != null;
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

    // DLT 메시지를 DB에 저장하는 컨슈머 전용 팩토리
    // 기본 팩토리를 쓰면 저장 실패 시 "-dlt-dlt"로 보내고 넘어가 메시지가 유실되므로, 실패해도 포기하지 않는 정책을 따로 둔다
    @Bean(DEAD_LETTER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> deadLetterListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // 저장 실패(DB 장애 등)는 복구될 때까지 10초 간격으로 무한 재시도한다 (recoverer가 없어 DLT로 다시 보내지 않음)
        // 에러 핸들러를 @Bean으로 등록하면 CommonErrorHandler 빈이 2개가 되어 기본 팩토리가 kafkaErrorHandler를 쓰지 않게 되므로 여기서 직접 생성한다
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(10_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
