package healoworld.slot_service.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration
public class KafkaConfig {

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
}