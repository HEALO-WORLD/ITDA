package healoworld.institution_service.global.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

// createdBy, updatedBy에 넣을 현재 사용자 ID 제공
// Gateway가 JWT 검증 후 넘겨주는 X-User-Id 헤더 값 사용
@Configuration
public class JpaAuditingConfig {

    // 사용자 ID를 담은 헤더 이름 (Gateway와 약속한 값)
    private static final String USER_ID_HEADER = "X-User-Id";

    @Bean
    public AuditorAware<UUID> auditorAware() {
        return () -> {
            // 지금 처리 중인 HTTP 요청을 꺼내기
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            // 요청 밖에서 저장하는 경우 (스케줄러 등)
            if (attributes == null) {
                return Optional.empty();
            }

            // 요청 헤더에서 사용자 ID 문자열을 읽기
            HttpServletRequest request = attributes.getRequest();
            String userId = request.getHeader(USER_ID_HEADER);

            // 헤더 없음 (Gateway를 거치지 않은 로컬 직접 호출 등)
            if (userId == null || userId.isBlank()) {
                return Optional.empty();
            }

            // 문자열을 UUID로 변환
            try {
                return Optional.of(UUID.fromString(userId));
            } catch (IllegalArgumentException e) {   // UUID 형식이 아닌 헤더 값
                return Optional.empty();
            }
        };
    }
}