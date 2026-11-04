package healoworld.institution_service.global.config;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration
public class KafkaConfig {

    private static final String[] MDC_HEADER_KEYS = {"eventId", "eventType"};

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
        
        return errorHandler;
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
