package healoworld.institution_service.application.service.user;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.User;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.infrastructure.repository.UserRepository;
import healoworld.institution_service.presentation.dto.request.SignupRequest;
import healoworld.institution_service.presentation.dto.response.MyInfoResponse;
import healoworld.institution_service.presentation.dto.response.SignupResponse;
import healoworld.institution_service.presentation.dto.response.UserSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final InstitutionRepository institutionRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        // 1. 아이디 중복
        if (userRepository.existsByLoginId(request.loginId())) {
            throw new BaseException(ErrorCode.DUPLICATE_LOGIN_ID);
        }

        // 2. 기관 조회
        Institution institution = institutionRepository.findByIdAndDeletedAtIsNull(request.institutionId())
                .orElseThrow(() -> new BaseException(ErrorCode.INSTITUTION_NOT_FOUND));

        // 3. 운영사 기관 가입 차단(가입하면 admin 되므로)
        if (!institution.isSignupAllowed()) {
            throw new BaseException(ErrorCode.SIGNUP_NOT_ALLOWED_INSTITUTION);
        }

        // 4~5. 암호화 후 생성 (역할 자동 결정, 승인 대기는 엔티티가 처리)
        User user = User.signup(request.loginId(), passwordEncoder.encode(request.password()), institution);

        // 6. 저장 (동시 가입으로 UNIQUE 위반 시 500 대신 409)
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (userRepository.existsByLoginId(request.loginId())) {
                throw new BaseException(ErrorCode.DUPLICATE_LOGIN_ID);
            }
        }

        return SignupResponse.from(user);
    }

    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getUsers(Boolean active) {
        List<User> users = (active == null)
                ? userRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
                : userRepository.findAllByActiveAndDeletedAtIsNullOrderByCreatedAtDesc(active);
        return users.stream().map(UserSummaryResponse::from).toList();
    }

    @Transactional
    public UserSummaryResponse approve(UUID userId) {
        User user = userRepository.findWithInstitutionByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BaseException(ErrorCode.USER_NOT_FOUND));
        user.approve();
        return UserSummaryResponse.from(user);
    }

    @Transactional(readOnly = true)
    public MyInfoResponse getMyInfo(UUID userId) {
        User user = userRepository.findWithInstitutionByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BaseException(ErrorCode.USER_NOT_FOUND));
        return MyInfoResponse.from(user);
    }

}
