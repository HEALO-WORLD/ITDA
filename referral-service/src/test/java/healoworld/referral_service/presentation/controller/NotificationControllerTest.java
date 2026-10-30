package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.presentation.dto.response.NotificationReadResponse;
import healoworld.referral_service.presentation.dto.response.NotificationResponse;
import healoworld.referral_service.presentation.dto.response.UnreadCountResponse;
import healoworld.referral_service.application.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@ActiveProfiles("test")
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 찾기 때문에 대체한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("알림 목록 조회 - 필터와 함께 200 응답")
    void getMyNotifications() throws Exception {
        UUID referralId = UUID.randomUUID();
        NotificationResponse item = new NotificationResponse(UUID.randomUUID(), referralId, "TEST", "제목", "내용",
                false, LocalDateTime.of(2026, 10, 27, 9, 0), null);
        given(notificationService.getMyNotifications(eq(userId), eq(referralId), eq(false), any(Pageable.class)))
                .willReturn(new PagedResponse<>(List.of(item), 1, 1, 10, 0));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-User-Id", userId.toString())
                        .param("referralId", referralId.toString())
                        .param("isRead", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.contents[0].referralId").value(referralId.toString()))
                .andExpect(jsonPath("$.data.contents[0].isRead").value(false))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("X-User-Id 헤더가 없으면 400 MISSING_HEADER")
    void missingUserHeader() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("referralId가 UUID 형식이 아니면 400 INVALID_INPUT")
    void invalidReferralId() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-User-Id", userId.toString())
                        .param("referralId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("안 읽은 알림 수 조회 - 200 응답")
    void countUnread() throws Exception {
        given(notificationService.countUnread(userId)).willReturn(new UnreadCountResponse(2));

        mockMvc.perform(get("/api/v1/notifications/unread-count").header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(2));
    }

    @Test
    @DisplayName("읽음 처리 - 200 응답")
    void markAsRead() throws Exception {
        UUID notificationId = UUID.randomUUID();
        given(notificationService.markAsRead(userId, notificationId))
                .willReturn(new NotificationReadResponse(notificationId, true, LocalDateTime.of(2026, 10, 27, 9, 30)));

        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notificationId").value(notificationId.toString()))
                .andExpect(jsonPath("$.data.isRead").value(true));
    }

    @Test
    @DisplayName("없는 알림 읽음 처리 - 404 NOTIFICATION_NOT_FOUND")
    void markAsReadNotFound() throws Exception {
        UUID notificationId = UUID.randomUUID();
        given(notificationService.markAsRead(userId, notificationId))
                .willThrow(new BaseException(ErrorCode.NOTIFICATION_NOT_FOUND));

        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 사용자 알림 읽음 처리 - 403 NOTIFICATION_ACCESS_DENIED")
    void markAsReadAccessDenied() throws Exception {
        UUID notificationId = UUID.randomUUID();
        given(notificationService.markAsRead(userId, notificationId))
                .willThrow(new BaseException(ErrorCode.NOTIFICATION_ACCESS_DENIED));

        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header("X-User-Id", userId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("isRead 필터 없이 조회하면 서비스에 null로 전달된다")
    void getMyNotificationsWithoutFilter() throws Exception {
        given(notificationService.getMyNotifications(eq(userId), isNull(), isNull(), any(Pageable.class)))
                .willReturn(new PagedResponse<>(List.of(), 0, 0, 10, 0));

        mockMvc.perform(get("/api/v1/notifications").header("X-User-Id", userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }
}
