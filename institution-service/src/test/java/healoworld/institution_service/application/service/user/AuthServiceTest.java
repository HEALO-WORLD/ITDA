package healoworld.institution_service.application.service.user;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.User;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.jwt.JwtProperties;
import healoworld.institution_service.infrastructure.jwt.JwtProvider;
import healoworld.institution_service.infrastructure.jwt.RefreshTokenClaims;
import healoworld.institution_service.infrastructure.redis.RefreshTokenRepository;
import healoworld.institution_service.infrastructure.redis.RotationResult;
import healoworld.institution_service.infrastructure.repository.UserRepository;
import healoworld.institution_service.presentation.dto.request.LoginRequest;
import healoworld.institution_service.presentation.dto.request.RefreshTokenRequest;
import healoworld.institution_service.presentation.dto.response.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {
    private static final String TEST_SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci1pdGRhLXRlc3RzLW9ubHk=";
    private static final Duration REFRESH_TTL = Duration.ofDays(14);
    private static final Duration GRACE_TTL = Duration.ofSeconds(10);

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    RefreshTokenRepository refreshTokenRepository;

    // 토큰 생성, 검증은 외부 의존이 없는 계산이라 진짜를 쓴다(실제 토큰으로 테스트)
    private JwtProvider jwtProvider;
    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(new JwtProperties(TEST_SECRET, Duration.ofHours(1),
                REFRESH_TTL, GRACE_TTL, new JwtProperties.DevToken(false)));
        authService = new AuthService(jwtProvider, userRepository, passwordEncoder, refreshTokenRepository);
        user = activeUser();
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        @DisplayName("성공하면 Refresh Token을 발급하고, 토큰의 family, jti를 Redis에 저장한다.")
        void login_saveFamily() {
            given(userRepository.findByLoginIdAndDeletedAtIsNull("user01")).willReturn(Optional.of(user));
            given(passwordEncoder.matches("password", user.getPassword())).willReturn(true);

            TokenResponse response = authService.login(new LoginRequest("user01", "password"));

            ArgumentCaptor<UUID> familyId = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<UUID> tokenId = ArgumentCaptor.forClass(UUID.class);
            verify(refreshTokenRepository).save(familyId.capture(), tokenId.capture(), eq(REFRESH_TTL));

            // 응답으로 준 토큰과 redis에 저장한 값이 같은 family.jti여야 재발급 때 비교가 맞음
            RefreshTokenClaims claims = jwtProvider.parseRefreshToken(response.refreshToken());
            assertThat(claims.familyId()).isEqualTo(familyId.getValue());
            assertThat(claims.tokenId()).isEqualTo(tokenId.getValue());
            assertThat(response.refreshExpiresIn()).isEqualTo(REFRESH_TTL.toSeconds());
        }
    }

    @Nested
    @DisplayName("토큰 재발급")
    class Reissue {
        private UUID familyId;
        private UUID tokenId;
        private String refreshToken;

        @BeforeEach
        void issueRefreshToken() {
            familyId = UUID.randomUUID();
            tokenId = UUID.randomUUID();
            refreshToken = jwtProvider.createRefreshToken(user.getId(), familyId, tokenId);
        }

        @Test
        @DisplayName("Redis가 ROTATED면 같은 family의 새 토큰을 돌려준다.")
        void reissue_rotated() {
            givenUserFound();
            givenRotation(new RotationResult(RotationResult.Status.ROTATED, null));

            TokenResponse response = authService.reissue(new RefreshTokenRequest(refreshToken));

            RefreshTokenClaims claims = jwtProvider.parseRefreshToken(response.refreshToken());
            assertThat(claims.familyId()).isEqualTo(familyId);        // family는 유지
            assertThat(claims.tokenId()).isNotEqualTo(tokenId);       // jti는 새 값
            verify(refreshTokenRepository).rotate(eq(familyId), eq(tokenId), eq(claims.tokenId()),
                    eq(REFRESH_TTL), anyString(), eq(GRACE_TTL));     // 응답 토큰의 jti로 교체 요청
        }

        @Test
        @DisplayName("Redis가 GRACE면 유예 키에 저장된 토큰 쌍을 그대로 돌려준다.")
        void reissue_grace() {
            givenUserFound();
            givenRotation(new RotationResult(RotationResult.Status.GRACE, "saved-access saved-refresh"));

            TokenResponse response = authService.reissue(new RefreshTokenRequest(refreshToken));

            assertThat(response.accessToken()).isEqualTo("saved-access");
            assertThat(response.refreshToken()).isEqualTo("saved-refresh");
        }

        @Test
        @DisplayName("Redis가 REUSED면 REFRESH_TOKEN_REUSED")
        void reissue_reused() {
            givenUserFound();
            givenRotation(new RotationResult(RotationResult.Status.REUSED, null));

            assertErrorCode(() -> authService.reissue(new RefreshTokenRequest(refreshToken)),
                    ErrorCode.REFRESH_TOKEN_REUSED);
        }

        @Test
        @DisplayName("위조·형식 오류 토큰은 INVALID_REFRESH_TOKEN이고, Redis·DB를 조회하지 않는다")
        void reissue_invalidToken() {
            assertErrorCode(() -> authService.reissue(new RefreshTokenRequest("not-a-jwt")),
                    ErrorCode.INVALID_REFRESH_TOKEN);

            verifyNoInteractions(refreshTokenRepository, userRepository);
        }

        @Test
        @DisplayName("Access Token을 넣으면 INVALID_REFRESH_TOKEN (토큰 종류 검증)")
        void reissue_accessToken() {
            String accessToken = jwtProvider.createAccessToken(user.getId(),
                    user.getInstitution().getId(), user.getInstitution().getType(), user.getRole());

            assertErrorCode(() -> authService.reissue(new RefreshTokenRequest(accessToken)),
                    ErrorCode.INVALID_REFRESH_TOKEN);

            verifyNoInteractions(refreshTokenRepository);
        }

        @Test
        @DisplayName("비활성 사용자면 family를 폐기하고 INVALID_REFRESH_TOKEN")
        void reissue_inactiveUser() {
            User inactive = User.signup("user02", "encoded-password", user.getInstitution());   // 승인 전 = 비활성
            ReflectionTestUtils.setField(inactive, "id", user.getId());
            given(userRepository.findWithInstitutionByIdAndDeletedAtIsNull(user.getId()))
                    .willReturn(Optional.of(inactive));

            assertErrorCode(() -> authService.reissue(new RefreshTokenRequest(refreshToken)),
                    ErrorCode.INVALID_REFRESH_TOKEN);

            verify(refreshTokenRepository).delete(familyId);
            verify(refreshTokenRepository, never()).rotate(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("삭제된 사용자면 family를 폐기하고 INVALID_REFRESH_TOKEN")
        void reissue_deletedUser() {
            given(userRepository.findWithInstitutionByIdAndDeletedAtIsNull(user.getId()))
                    .willReturn(Optional.empty());

            assertErrorCode(() -> authService.reissue(new RefreshTokenRequest(refreshToken)),
                    ErrorCode.INVALID_REFRESH_TOKEN);

            verify(refreshTokenRepository).delete(familyId);
            verify(refreshTokenRepository, never()).rotate(any(), any(), any(), any(), any(), any());
        }

        private void givenRotation(RotationResult result) {
            given(refreshTokenRepository.rotate(eq(familyId), eq(tokenId), any(UUID.class),
                    any(Duration.class), anyString(), any(Duration.class)))
                    .willReturn(result);
        }

        private void givenUserFound() {
            given(userRepository.findWithInstitutionByIdAndDeletedAtIsNull(user.getId()))
                    .willReturn(Optional.of(user));
        }

    }

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("Refresh Token의 family를 삭제한다")
        void logout_deletesFamily() {
            UUID familyId = UUID.randomUUID();
            String refreshToken = jwtProvider.createRefreshToken(user.getId(), familyId, UUID.randomUUID());

            authService.logout(new RefreshTokenRequest(refreshToken));

            verify(refreshTokenRepository).delete(familyId);
        }

        @Test
        @DisplayName("잘못된 토큰이어도 예외 없이 끝나고, 아무것도 삭제하지 않는다")
        void logout_invalidToken() {
            assertThatCode(() -> authService.logout(new RefreshTokenRequest("not-a-jwt")))
                    .doesNotThrowAnyException();

            verify(refreshTokenRepository, never()).delete(any());
        }
    }

    private User activeUser() {
        Institution institution = Institution.create("테스트기관", InstitutionType.REQUESTER,
                "서울특별시 중구 세종대로 1", "11100001");
        ReflectionTestUtils.setField(institution, "id", UUID.randomUUID());

        User user = User.signup("user01", "encoded-password", institution);
        user.approve();   // 승인 = 활성
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private void assertErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }
}