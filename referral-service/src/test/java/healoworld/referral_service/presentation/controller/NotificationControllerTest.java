package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.presentation.dto.response.NotificationReadResponse;
import healoworld.referral_service.presentation.dto.response.NotificationResponse;
import healoworld.referral_service.presentation.dto.response.UnreadCountResponse;
import healoworld.referral_service.application.service.NotificationService;
import healoworld.referral_service.application.service.InstitutionNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

    @MockitoBean
    private InstitutionNotificationService institutionNotificationService;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 찾기 때문에 대체한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("알림 목록 조회 - 필터와 함께 200 응답")
    void getMyNotifications() throws Exception {
        UUID referralId = UUID.randomUUID();
        NotificationResponse item = new NotificationResponse(UUID.randomUUID(), referralId, "TEST", "제목", "내용",
                false, LocalDateTime.of(2026, 10, 27, 9, 0), null, null);
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

    // ===== 페이지·정렬 정책 =====

    // 요청 파라미터로 목록을 조회하고 Service에 전달된 Pageable을 돌려준다
    private Pageable pageableOf(String... params) throws Exception {
        given(notificationService.getMyNotifications(eq(userId), isNull(), isNull(), any(Pageable.class)))
                .willReturn(new PagedResponse<>(List.of(), 0, 0, 10, 0));
        var request = get("/api/v1/notifications").header("X-User-Id", userId.toString());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }

        mockMvc.perform(request).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(notificationService).should().getMyNotifications(eq(userId), isNull(), isNull(), pageable.capture());
        return pageable.getValue();
    }

    @Test
    @DisplayName("파라미터가 없으면 page=0, size=10, createdAt 내림차순이다")
    void defaultPageable() throws Exception {
        Pageable pageable = pageableOf();

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("createdAt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "30", "50"})
    @DisplayName("size가 10, 30, 50이면 그대로 적용된다")
    void allowedSize(String size) throws Exception {
        assertThat(pageableOf("size", size).getPageSize()).isEqualTo(Integer.parseInt(size));
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "0", "-1", "abc", ""})
    @DisplayName("size가 10, 30, 50이 아니면 기본값 10이 적용된다")
    void otherSizeFallsBackToDefault(String size) throws Exception {
        assertThat(pageableOf("size", size).getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("page는 전달한 값이 적용된다")
    void pageNumber() throws Exception {
        assertThat(pageableOf("page", "2").getPageNumber()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "abc"})
    @DisplayName("page가 음수이거나 숫자가 아니면 0이 적용된다")
    void invalidPageFallsBackToZero(String page) throws Exception {
        assertThat(pageableOf("page", page).getPageNumber()).isZero();
    }

    @Test
    @DisplayName("허용된 필드의 다중 정렬은 전달한 순서대로 적용된다")
    void multipleSort() throws Exception {
        Pageable pageable = pageableOf("sort", "readAt,asc", "sort", "createdAt,desc");

        assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("readAt"), Sort.Order.desc("createdAt"));
    }

    @Test
    @DisplayName("방향을 생략하면 오름차순이다")
    void sortDirectionForms() throws Exception {
        assertThat(pageableOf("sort", "readAt").getSort()).containsExactly(Sort.Order.asc("readAt"));
    }

    @Test
    @DisplayName("방향은 대문자로 보내도 적용된다")
    void upperCaseDirection() throws Exception {
        assertThat(pageableOf("sort", "createdAt,DESC").getSort()).containsExactly(Sort.Order.desc("createdAt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"title,asc", "id", "recipientUserId,desc", "createdAt,sideways", "createdAt,",
            "createdAt,asc,desc", "createdAt,readAt,desc", "desc", ",desc", "", "CREATEDAT,asc", "createdAt, desc"})
    @DisplayName("허용되지 않은 정렬은 400 INVALID_INPUT이고 Service를 호출하지 않는다")
    void invalidSort(String sort) throws Exception {
        mockMvc.perform(get("/api/v1/notifications").header("X-User-Id", userId.toString()).param("sort", sort))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        then(notificationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("다중 정렬 중 뒤쪽 하나만 잘못돼도 400 INVALID_INPUT이다")
    void oneInvalidAmongMultipleSort() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").header("X-User-Id", userId.toString())
                        .param("sort", "createdAt,desc")
                        .param("sort", "message,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        then(notificationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("페이지·정렬 정책을 적용해도 요청자 ID와 referralId, isRead 필터는 그대로 Service에 전달된다")
    void filtersAreKept() throws Exception {
        UUID referralId = UUID.randomUUID();
        given(notificationService.getMyNotifications(eq(userId), eq(referralId), eq(true), any(Pageable.class)))
                .willReturn(new PagedResponse<>(List.of(), 0, 0, 30, 1));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-User-Id", userId.toString())
                        .param("referralId", referralId.toString())
                        .param("isRead", "true")
                        .param("page", "1")
                        .param("size", "30")
                        .param("sort", "readAt,desc"))
                .andExpect(status().isOk());

        then(notificationService).should().getMyNotifications(eq(userId), eq(referralId), eq(true),
                eq(PageRequest.of(1, 30, Sort.by(Sort.Order.desc("readAt")))));
    }

    // ===== 역할별 분기: EXAMINER는 소속 검사기관 공통 알림, 그 밖은 개인 알림 =====

    private final UUID institutionId = UUID.randomUUID();

    @Test
    @DisplayName("EXAMINER 목록 조회는 X-Institution-Id의 기관 알림을 같은 페이지·필터로 조회하고 개인 알림은 조회하지 않는다")
    void examinerListUsesInstitution() throws Exception {
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        NotificationResponse item = new NotificationResponse(UUID.randomUUID(), referralId, "RESERVATION_CONFIRMED",
                "제목", "내용", false, LocalDateTime.of(2026, 11, 10, 9, 0), null, reservationId);
        given(institutionNotificationService.getInstitutionNotifications(eq(institutionId), eq(userId), eq(referralId),
                eq(false), any(Pageable.class))).willReturn(new PagedResponse<>(List.of(item), 1, 1, 30, 0));

        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", "EXAMINER")
                        .header("X-Institution-Id", institutionId.toString())
                        .param("referralId", referralId.toString())
                        .param("isRead", "false")
                        .param("size", "30")
                        .param("sort", "readAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contents[0].type").value("RESERVATION_CONFIRMED"))
                .andExpect(jsonPath("$.data.contents[0].reservationId").value(reservationId.toString()));

        then(institutionNotificationService).should().getInstitutionNotifications(eq(institutionId), eq(userId),
                eq(referralId), eq(false), eq(PageRequest.of(0, 30, Sort.by(Sort.Order.desc("readAt")))));
        then(notificationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("EXAMINER 안 읽은 수·읽음 처리도 기관 알림으로 처리한다")
    void examinerUnreadCountAndRead() throws Exception {
        UUID notificationId = UUID.randomUUID();
        given(institutionNotificationService.countUnread(institutionId, userId)).willReturn(new UnreadCountResponse(3));
        given(institutionNotificationService.markAsRead(institutionId, userId, notificationId))
                .willReturn(new NotificationReadResponse(notificationId, true, LocalDateTime.of(2026, 11, 10, 9, 30)));

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", "EXAMINER")
                        .header("X-Institution-Id", institutionId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(3));
        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", "EXAMINER")
                        .header("X-Institution-Id", institutionId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isRead").value(true));

        then(notificationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("EXAMINER가 다른 기관 알림을 읽음 처리하면 403 NOTIFICATION_ACCESS_DENIED")
    void examinerReadOtherInstitution() throws Exception {
        UUID notificationId = UUID.randomUUID();
        given(institutionNotificationService.markAsRead(institutionId, userId, notificationId))
                .willThrow(new BaseException(ErrorCode.NOTIFICATION_ACCESS_DENIED));

        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", "EXAMINER")
                        .header("X-Institution-Id", institutionId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("EXAMINER인데 X-Institution-Id가 없으면 400 MISSING_HEADER이고 어떤 알림도 조회하지 않는다")
    void examinerWithoutInstitution() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", "EXAMINER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", "EXAMINER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", UUID.randomUUID())
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", "EXAMINER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

        then(institutionNotificationService).shouldHaveNoInteractions();
        then(notificationService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTER", "ADMIN"})
    @DisplayName("REQUESTER·ADMIN은 X-Institution-Id가 있어도 기존처럼 본인 개인 알림을 조회·읽음 처리한다")
    void nonExaminerUsesPersonal(String role) throws Exception {
        UUID notificationId = UUID.randomUUID();
        given(notificationService.getMyNotifications(eq(userId), isNull(), isNull(), any(Pageable.class)))
                .willReturn(new PagedResponse<>(List.of(), 0, 0, 10, 0));
        given(notificationService.countUnread(userId)).willReturn(new UnreadCountResponse(1));
        given(notificationService.markAsRead(userId, notificationId))
                .willReturn(new NotificationReadResponse(notificationId, true, LocalDateTime.of(2026, 11, 10, 9, 30)));

        mockMvc.perform(get("/api/v1/notifications").header("X-User-Id", userId.toString())
                        .header("X-Role", role).header("X-Institution-Id", institutionId.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notifications/unread-count").header("X-User-Id", userId.toString())
                        .header("X-Role", role).header("X-Institution-Id", institutionId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(1));
        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", role).header("X-Institution-Id", institutionId.toString()))
                .andExpect(status().isOk());

        then(institutionNotificationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("X-Role이 알 수 없는 값이면 400 INVALID_INPUT이고 어떤 알림도 조회하지 않는다")
    void invalidRole() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", "PATIENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        then(institutionNotificationService).shouldHaveNoInteractions();
        then(notificationService).shouldHaveNoInteractions();
    }
    @Test
    @DisplayName("EXAMINER 읽음 처리·목록은 헤더의 사용자·기관 ID만 쓰고, 쿼리·본문의 userId·institutionId는 무시한다")
    void examinerUsesOnlyGatewayHeaders() throws Exception {
        UUID notificationId = UUID.randomUUID();
        UUID forgedUser = UUID.randomUUID();
        UUID forgedInstitution = UUID.randomUUID();
        given(institutionNotificationService.markAsRead(institutionId, userId, notificationId))
                .willReturn(new NotificationReadResponse(notificationId, true, LocalDateTime.of(2026, 11, 10, 9, 30)));
        given(institutionNotificationService.getInstitutionNotifications(eq(institutionId), eq(userId), isNull(),
                isNull(), any(Pageable.class))).willReturn(new PagedResponse<>(List.of(), 0, 0, 10, 0));

        mockMvc.perform(patch("/api/v1/notifications/{notificationId}/read", notificationId)
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", "EXAMINER")
                        .header("X-Institution-Id", institutionId.toString())
                        .param("userId", forgedUser.toString())
                        .param("institutionId", forgedInstitution.toString())
                        .contentType("application/json")
                        .content("{\"userId\":\"" + forgedUser + "\",\"institutionId\":\"" + forgedInstitution + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notifications")
                        .header("X-User-Id", userId.toString())
                        .header("X-Role", "EXAMINER")
                        .header("X-Institution-Id", institutionId.toString())
                        .param("userId", forgedUser.toString())
                        .param("institutionId", forgedInstitution.toString()))
                .andExpect(status().isOk());

        then(institutionNotificationService).should().markAsRead(institutionId, userId, notificationId);
        then(institutionNotificationService).should().getInstitutionNotifications(eq(institutionId), eq(userId),
                isNull(), isNull(), any(Pageable.class));
        then(institutionNotificationService).shouldHaveNoMoreInteractions();
    }
}
