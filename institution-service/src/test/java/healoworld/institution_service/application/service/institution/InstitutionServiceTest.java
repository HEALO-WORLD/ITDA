package healoworld.institution_service.application.service.institution;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.infrastructure.repository.PartnershipRepository;
import healoworld.institution_service.infrastructure.repository.UserRepository;
import healoworld.institution_service.presentation.dto.request.InstitutionCreateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class InstitutionServiceTest {
    @Mock
    InstitutionRepository institutionRepository;

    @Mock
    PartnershipRepository partnershipRepository;

    @Mock
    UserRepository userRepository;

    @InjectMocks
    InstitutionService institutionService;

    @Nested
    @DisplayName("기관 등록")
    class Create {
        @Test
        @DisplayName("정상 등록하면 저장한다.")
        void create_success() {
            InstitutionCreateRequest request = new InstitutionCreateRequest(
                    "C의원", InstitutionType.REQUESTER, "서울특별시 종로구 종로 404", "11100004");
            given(institutionRepository.existsByCareInstitutionCode("11100004")).willReturn(false);
            institutionService.create(request);
            verify(institutionRepository).saveAndFlush(any(Institution.class));
        }

        @Test
        @DisplayName("운영사(PLATFORM)는 등록할 수 없다")
        void create_platform() {
            InstitutionCreateRequest request = new InstitutionCreateRequest(
                    "운영사2", InstitutionType.PLATFORM, "서울특별시 중구 세종대로 910", "11199999");

            assertErrorCode(() -> institutionService.create(request), ErrorCode.INSTITUTION_TYPE_NOT_ALLOWED);
            verify(institutionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("요양기관기호가 이미 있으면 409")
        void create_duplicateCode() {
            InstitutionCreateRequest request = new InstitutionCreateRequest(
                    "C의원", InstitutionType.REQUESTER, "서울특별시 종로구 종로 404", "11100001");
            given(institutionRepository.existsByCareInstitutionCode("11100001")).willReturn(true);

            assertErrorCode(() -> institutionService.create(request), ErrorCode.DUPLICATE_CARE_INSTITUTION_CODE);
            verify(institutionRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("기관 삭제")
    class Delete {

        @Test
        @DisplayName("진행 중 협약이 있으면 409")
        void delete_hasPartnership() {
            Institution target = institution(InstitutionType.REQUESTER);
            given(institutionRepository.findByIdAndDeletedAtIsNull(target.getId())).willReturn(Optional.of(target));
            given(partnershipRepository.existsByInstitutionIdAndStatusIn(target.getId(), PartnershipStatus.inProgress()))
                    .willReturn(true);

            assertErrorCode(() -> institutionService.delete(target.getId(), UUID.randomUUID()),
                    ErrorCode.INSTITUTION_HAS_ACTIVE_PARTNERSHIP);
            assertThat(target.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("소속 사용자가 있으면 409")
        void delete_hasUsers() {
            Institution target = institution(InstitutionType.REQUESTER);
            given(institutionRepository.findByIdAndDeletedAtIsNull(target.getId())).willReturn(Optional.of(target));
            given(partnershipRepository.existsByInstitutionIdAndStatusIn(target.getId(), PartnershipStatus.inProgress()))
                    .willReturn(false);
            given(userRepository.existsByInstitutionIdAndDeletedAtIsNull(target.getId())).willReturn(true);

            assertErrorCode(() -> institutionService.delete(target.getId(), UUID.randomUUID()),
                    ErrorCode.INSTITUTION_HAS_USERS);
            assertThat(target.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("협약·사용자가 없으면 논리 삭제된다")
        void delete_success() {
            Institution target = institution(InstitutionType.REQUESTER);
            UUID adminId = UUID.randomUUID();
            given(institutionRepository.findByIdAndDeletedAtIsNull(target.getId())).willReturn(Optional.of(target));
            given(partnershipRepository.existsByInstitutionIdAndStatusIn(target.getId(), PartnershipStatus.inProgress()))
                    .willReturn(false);
            given(userRepository.existsByInstitutionIdAndDeletedAtIsNull(target.getId())).willReturn(false);

            institutionService.delete(target.getId(), adminId);

            assertThat(target.getDeletedAt()).isNotNull();
            assertThat(target.getDeletedBy()).isEqualTo(adminId);
            assertThat(target.isActive()).isFalse();
        }

        @Test
        @DisplayName("없는 기관이면 404")
        void delete_notFound() {
            UUID id = UUID.randomUUID();
            given(institutionRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.empty());

            assertErrorCode(() -> institutionService.delete(id, UUID.randomUUID()), ErrorCode.INSTITUTION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("기관 상세 접근 권한")
    class Detail {

        @Test
        @DisplayName("ADMIN은 협약 조회 없이 모든 기관을 본다")
        void detail_admin() {
            Institution target = institution(InstitutionType.REQUESTER);
            given(institutionRepository.findByIdAndDeletedAtIsNull(target.getId())).willReturn(Optional.of(target));

            assertThat(institutionService.getInstitutionDetail(target.getId(), UUID.randomUUID(), Role.ADMIN).id())
                    .isEqualTo(target.getId());
            verify(partnershipRepository, never()).existsBetweenInstitutionsAndStatusIn(any(), any(), any());
        }

        @Test
        @DisplayName("내 기관은 협약 조회 없이 본다")
        void detail_mine() {
            Institution target = institution(InstitutionType.REQUESTER);
            given(institutionRepository.findByIdAndDeletedAtIsNull(target.getId())).willReturn(Optional.of(target));

            institutionService.getInstitutionDetail(target.getId(), target.getId(), Role.REQUESTER);

            verify(partnershipRepository, never()).existsBetweenInstitutionsAndStatusIn(any(), any(), any());
        }

        @Test
        @DisplayName("PENDING·ACTIVE 협약 상대는 본다")
        void detail_partner() {
            Institution target = institution(InstitutionType.EXAM);
            UUID myId = UUID.randomUUID();
            given(institutionRepository.findByIdAndDeletedAtIsNull(target.getId())).willReturn(Optional.of(target));
            given(partnershipRepository.existsBetweenInstitutionsAndStatusIn(myId, target.getId(), PartnershipStatus.inProgress()))
                    .willReturn(true);

            assertThat(institutionService.getInstitutionDetail(target.getId(), myId, Role.REQUESTER).id())
                    .isEqualTo(target.getId());
        }

        @Test
        @DisplayName("협약이 없으면 403")
        void detail_forbidden() {
            Institution target = institution(InstitutionType.EXAM);
            UUID myId = UUID.randomUUID();
            given(institutionRepository.findByIdAndDeletedAtIsNull(target.getId())).willReturn(Optional.of(target));
            given(partnershipRepository.existsBetweenInstitutionsAndStatusIn(myId, target.getId(), PartnershipStatus.inProgress()))
                    .willReturn(false);

            assertErrorCode(() -> institutionService.getInstitutionDetail(target.getId(), myId, Role.REQUESTER),
                    ErrorCode.FORBIDDEN);
        }
    }

    private Institution institution(InstitutionType type) {
        Institution institution = Institution.create("테스트기관", type, "서울특별시 중구 세종대로 1",
                type.isMedical() ? "11100001" : null);
        ReflectionTestUtils.setField(institution, "id", UUID.randomUUID());
        return institution;
    }
    
    private void assertErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }
}
