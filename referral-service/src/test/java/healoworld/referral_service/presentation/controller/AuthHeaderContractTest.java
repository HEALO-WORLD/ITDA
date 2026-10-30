package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ReferralHistoryService;
import healoworld.referral_service.application.service.ReferralService;
import healoworld.referral_service.application.service.ReservationService;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.outbox.OutboxEventRepository;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Gateway(JwtAuthenticationFilter)가 전달하는 인증 헤더 계약과 보안 회귀를 검증한다
// 역할: X-Role = REQUESTER / EXAMINER / ADMIN. 예전 이름인 X-User-Role은 어떤 경우에도 권한에 쓰이면 안 된다
// 실제 Controller + 실제 Service로 권한 판단까지 실행한다 (Repository만 Mock, DB 미사용)
@WebMvcTest({ReferralController.class, ReservationController.class})
@Import({ReferralService.class, ReservationService.class, ReferralHistoryService.class})
@ActiveProfiles("test")
class AuthHeaderContractTest {

    private static final String USER_ID = "X-User-Id";
    private static final String INSTITUTION_ID = "X-Institution-Id";
    private static final String ROLE = "X-Role";
    private static final String FORGED_ROLE = "X-User-Role";

    private static final String RESCHEDULE_BODY = """
            {"slotId": "71de0c4e-1b2a-4c3d-9e8f-0a1b2c3d4e5f",
             "examInstitutionId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
             "equipmentId": "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
             "examDate": "2026-11-10T10:00:00",
             "startTime": "2026-11-10T10:00:00",
             "endTime": "2026-11-10T11:00:00"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReferralRepository referralRepository;

    @MockitoBean
    private ReservationRepository reservationRepository;

    @MockitoBean
    private ReferralHistoryRepository referralHistoryRepository;

    @MockitoBean
    private OutboxEventRepository outboxEventRepository;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 찾기 때문에 대체한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    // 요청자와 무관한 기관·사용자의 의뢰와 예약
    private final UUID requesterId = UUID.randomUUID();
    private final UUID requesterInstitutionId = UUID.randomUUID();
    private Referral othersReferral;
    private Reservation othersReservation;

    @BeforeEach
    void setUp() {
        othersReferral = DomainFixtures.referralIn(ReferralStatus.NEEDS_RESCHEDULE, UUID.randomUUID());
        othersReservation = DomainFixtures.reservationIn(ReservationStatus.CANCELED, othersReferral);
        given(referralRepository.findById(othersReferral.getId())).willReturn(Optional.of(othersReferral));
        given(reservationRepository.findById(othersReservation.getId())).willReturn(Optional.of(othersReservation));
    }

    // ===== 헤더 계약 =====

    @ParameterizedTest
    @EnumSource(RoleType.class)
    @DisplayName("X-Role의 세 역할 값이 모두 받아들여지고 자기 기관 의뢰를 조회한다")
    void allRolesAreAccepted(RoleType role) throws Exception {
        mockMvc.perform(get("/api/v1/referrals/{id}", othersReferral.getId())
                        .header(INSTITUTION_ID, othersReferral.getReferringInstitutionId().toString())
                        .header(ROLE, role.name()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("RoleType은 Auth의 역할 값과 일치한다")
    void roleValuesMatchAuth() {
        assertThat(RoleType.values()).extracting(Enum::name)
                .containsExactlyInAnyOrder("REQUESTER", "EXAMINER", "ADMIN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "EXAM", "PLATFORM"})
    @DisplayName("Auth 역할이 아닌 값(이전 값 USER, 기관 유형 값)은 400 INVALID_INPUT")
    void unknownRoleValuesAreRejected(String role) throws Exception {
        mockMvc.perform(get("/api/v1/referrals/{id}", othersReferral.getId())
                        .header(INSTITUTION_ID, requesterInstitutionId.toString())
                        .header(ROLE, role))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    // ===== 보안 회귀: X-User-Role로 관리자 권한을 얻을 수 없다 =====

    @Test
    @DisplayName("X-User-Role: ADMIN만 보내면 X-Role 누락으로 400이고 데이터를 조회하지 않는다")
    void forgedRoleAloneIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/referrals/{id}", othersReferral.getId())
                        .header(INSTITUTION_ID, requesterInstitutionId.toString())
                        .header(FORGED_ROLE, "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        then(referralRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("[대조군] X-Role: ADMIN이면 다른 기관 의뢰를 조회할 수 있다")
    void realAdminCanReadOthersReferral() throws Exception {
        mockMvc.perform(get("/api/v1/referrals/{id}", othersReferral.getId())
                        .header(INSTITUTION_ID, requesterInstitutionId.toString())
                        .header(ROLE, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(othersReferral.getId().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTER", "EXAMINER"})
    @DisplayName("의뢰 조회: 일반 역할에 X-User-Role: ADMIN을 더해도 다른 기관 의뢰는 404")
    void forgedRoleCannotReadReferral(String role) throws Exception {
        mockMvc.perform(get("/api/v1/referrals/{id}", othersReferral.getId())
                        .header(INSTITUTION_ID, requesterInstitutionId.toString())
                        .header(ROLE, role)
                        .header(FORGED_ROLE, "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFERRAL_NOT_FOUND"));
    }

    @Test
    @DisplayName("의뢰 이력 조회: X-User-Role: ADMIN을 더해도 다른 기관 의뢰는 404이고 이력을 조회하지 않는다")
    void forgedRoleCannotReadHistories() throws Exception {
        mockMvc.perform(get("/api/v1/referrals/{id}/histories", othersReferral.getId())
                        .header(INSTITUTION_ID, requesterInstitutionId.toString())
                        .header(ROLE, "REQUESTER")
                        .header(FORGED_ROLE, "ADMIN"))
                .andExpect(status().isNotFound());
        then(referralHistoryRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("의뢰 목록: X-User-Role: ADMIN을 더해도 Repository에는 X-Role의 역할과 자기 기관으로 전달된다")
    void forgedRoleCannotWidenReferralList() throws Exception {
        given(referralRepository.search(any(), any(), any(), any())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/referrals")
                        .header(INSTITUTION_ID, requesterInstitutionId.toString())
                        .header(ROLE, "REQUESTER")
                        .header(FORGED_ROLE, "ADMIN"))
                .andExpect(status().isOk());

        then(referralRepository).should().search(any(), eq(requesterInstitutionId), eq(RoleType.REQUESTER), any());
    }

    @Test
    @DisplayName("의뢰 삭제: X-User-Role: ADMIN을 더해도 다른 사람의 의뢰는 404이고 저장되지 않는다")
    void forgedRoleCannotDeleteReferral() throws Exception {
        mockMvc.perform(delete("/api/v1/referrals/{id}", othersReferral.getId())
                        .header(USER_ID, requesterId.toString())
                        .header(ROLE, "REQUESTER")
                        .header(FORGED_ROLE, "ADMIN"))
                .andExpect(status().isNotFound());
        then(referralRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("재예약: X-User-Role: ADMIN을 더해도 다른 사람의 의뢰는 404이고 새 예약을 만들지 않는다")
    void forgedRoleCannotReschedule() throws Exception {
        mockMvc.perform(post("/api/v1/referrals/{id}/reschedule", othersReferral.getId())
                        .header(USER_ID, requesterId.toString())
                        .header(ROLE, "REQUESTER")
                        .header(FORGED_ROLE, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESCHEDULE_BODY))
                .andExpect(status().isNotFound());
        then(reservationRepository).should(never()).save(any());
        assertThat(othersReferral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
    }

    @Test
    @DisplayName("예약 조회: X-User-Role: ADMIN을 더해도 관계없는 기관의 예약은 404")
    void forgedRoleCannotReadReservation() throws Exception {
        mockMvc.perform(get("/api/v1/referrals/reservations/{id}", othersReservation.getId())
                        .header(INSTITUTION_ID, requesterInstitutionId.toString())
                        .header(ROLE, "EXAMINER")
                        .header(FORGED_ROLE, "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("예약 목록: X-User-Role: ADMIN을 더해도 Repository에는 X-Role의 역할과 자기 기관으로 전달된다")
    void forgedRoleCannotWidenReservationList() throws Exception {
        given(reservationRepository.search(any(), any(), any(), any())).willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/referrals/reservations")
                        .header(INSTITUTION_ID, requesterInstitutionId.toString())
                        .header(ROLE, "EXAMINER")
                        .header(FORGED_ROLE, "ADMIN"))
                .andExpect(status().isOk());

        then(reservationRepository).should().search(any(), eq(requesterInstitutionId), eq(RoleType.EXAMINER), any());
    }

    @Test
    @DisplayName("예약 삭제: X-User-Role: ADMIN을 더해도 다른 사람의 예약은 404이고 저장되지 않는다")
    void forgedRoleCannotDeleteReservation() throws Exception {
        mockMvc.perform(delete("/api/v1/referrals/reservations/{id}", othersReservation.getId())
                        .header(USER_ID, requesterId.toString())
                        .header(ROLE, "REQUESTER")
                        .header(FORGED_ROLE, "ADMIN"))
                .andExpect(status().isNotFound());
        then(reservationRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("예약 조회: 검사기관 소속 EXAMINER는 자기 기관 예약을 조회한다")
    void examinerReadsOwnInstitutionReservation() throws Exception {
        mockMvc.perform(get("/api/v1/referrals/reservations/{id}", othersReservation.getId())
                        .header(INSTITUTION_ID, othersReservation.getExamInstitutionId().toString())
                        .header(ROLE, "EXAMINER"))
                .andExpect(status().isOk());
    }
}
