package healoworld.institution_service.application.service.user;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.User;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.jwt.JwtProvider;
import healoworld.institution_service.infrastructure.repository.UserRepository;
import healoworld.institution_service.presentation.dto.request.LoginRequest;
import healoworld.institution_service.presentation.dto.response.TokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public TokenResponse issueDevToken(DevTestAccount account) {
        String accessToken = jwtProvider.createAccessToken(
                account.getUserId(),
                account.getInstitutionId(),
                account.getInstitutionType(),
                account.getRole());
        return TokenResponse.bearer(accessToken, jwtProvider.getAccessTokenExpirationSeconds());
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByLoginIdAndDeletedAtIsNull(request.loginId())
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BaseException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!user.isActive()) {
            throw new BaseException(ErrorCode.USER_NOT_APPROVED);
        }

        Institution institution = user.getInstitution();

        String accessToke = jwtProvider.createAccessToken(
                user.getId(),
                institution.getId(),
                institution.getType(),
                user.getRole());

        return TokenResponse.bearer(accessToke, jwtProvider.getAccessTokenExpirationSeconds());
    }
}
