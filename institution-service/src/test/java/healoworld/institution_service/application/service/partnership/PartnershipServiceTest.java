package healoworld.institution_service.application.service.partnership;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.Partnership;
import healoworld.institution_service.domain.entity.PartnershipHistory;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.infrastructure.repository.PartnershipHistoryRepository;
import healoworld.institution_service.infrastructure.repository.PartnershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
public class PartnershipServiceTest {
    @Mock
    PartnershipRepository partnershipRepository;

    @Mock
    InstitutionRepository institutionRepository;

    @Mock
    PartnershipHistoryRepository partnershipHistoryRepository;

    @InjectMocks
    PartnershipService partnershipService;

    private Institution requester;
    private Institution exam;

    @BeforeEach
    void setUp() {
        requester = institution(InstitutionType.REQUESTER, "11100002");
        exam = institution(InstitutionType.EXAM, "11100003");
    }

    @Nested
    @DisplayName("협약 요청")
    class Request {

        @BeforeEach
        void stubInstitutions() {
            given(institutionRepository.findByIdAndDeletedAtIsNull(requester.getId())).willReturn(Optional.of(requester));
            given(institutionRepository.findByIdAndDeletedAtIsNull(exam.getId())).willReturn(Optional.of(exam));
        }

        @Test
        @DisplayName("처음 요청하면 새 협약을 저장하고 이력(null → PENDING)을 남긴다")
        void request_new() {
            given(partnershipRepository.findByRequesterInstitutionIdAndExamInstitutionId(requester.getId(), exam.getId()))
                    .willReturn(Optional.empty());

            partnershipService.request(requester.getId(), exam.getId());

            verify(partnershipRepository).saveAndFlush(any(Partnership.class));
            PartnershipHistory history = savedHistory();
            assertThat(history.getFromStatus()).isNull();
            assertThat(history.getToStatus()).isEqualTo(PartnershipStatus.PENDING);
        }

        @Test
        @DisplayName("진행 중 협약이 있으면 409")
        void request_inProgress() {
            Partnership pending = Partnership.request(requester, exam);
            given(partnershipRepository.findByRequesterInstitutionIdAndExamInstitutionId(requester.getId(), exam.getId()))
                    .willReturn(Optional.of(pending));

            assertErrorCode(() -> partnershipService.request(requester.getId(), exam.getId()),
                    ErrorCode.PARTNERSHIP_ALREADY_IN_PROGRESS);
            verify(partnershipHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("거절된 협약이 있으면 같은 협약을 재요청하고 이력(REJECTED → PENDING)을 남긴다")
        void request_reRequest() {
            Partnership rejected = Partnership.request(requester, exam);
            rejected.reject();
            given(partnershipRepository.findByRequesterInstitutionIdAndExamInstitutionId(requester.getId(), exam.getId()))
                    .willReturn(Optional.of(rejected));

            partnershipService.request(requester.getId(), exam.getId());

            assertThat(rejected.getStatus()).isEqualTo(PartnershipStatus.PENDING);
            verify(partnershipRepository, never()).saveAndFlush(any());   // 새 행을 만들지 않음
            PartnershipHistory history = savedHistory();
            assertThat(history.getFromStatus()).isEqualTo(PartnershipStatus.REJECTED);
            assertThat(history.getToStatus()).isEqualTo(PartnershipStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("승인")
    class Approve {

        @Test
        @DisplayName("요청받은 검사기관이 승인하면 ACTIVE가 되고 이력을 남긴다")
        void approve_byExam() {
            Partnership partnership = Partnership.request(requester, exam);
            UUID id = givenPartnership(partnership);

            partnershipService.approve(id, exam.getId());

            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.ACTIVE);
            assertThat(savedHistory().getFromStatus()).isEqualTo(PartnershipStatus.PENDING);
        }

        @Test
        @DisplayName("의뢰기관이 승인하면 403")
        void approve_byRequester() {
            Partnership partnership = Partnership.request(requester, exam);
            UUID id = givenPartnership(partnership);

            assertErrorCode(() -> partnershipService.approve(id, requester.getId()), ErrorCode.FORBIDDEN);
            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.PENDING);
        }

        @Test
        @DisplayName("없는 협약이면 404")
        void approve_notFound() {
            UUID id = UUID.randomUUID();
            given(partnershipRepository.findById(id)).willReturn(Optional.empty());

            assertErrorCode(() -> partnershipService.approve(id, exam.getId()), ErrorCode.PARTNERSHIP_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("종료")
    class Terminate {

        @Test
        @DisplayName("의뢰기관이 종료할 수 있다")
        void terminate_byRequester() {
            Partnership partnership = active();
            UUID id = givenPartnership(partnership);

            partnershipService.terminate(id, requester.getId(), Role.REQUESTER);

            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.TERMINATED);
        }

        @Test
        @DisplayName("관리자는 당사자가 아니어도 종료할 수 있다")
        void terminate_byAdmin() {
            Partnership partnership = active();
            UUID id = givenPartnership(partnership);

            partnershipService.terminate(id, UUID.randomUUID(), Role.ADMIN);

            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.TERMINATED);
        }

        @Test
        @DisplayName("제3기관이 종료하면 403")
        void terminate_byOther() {
            Partnership partnership = active();
            UUID id = givenPartnership(partnership);

            assertErrorCode(() -> partnershipService.terminate(id, UUID.randomUUID(), Role.REQUESTER), ErrorCode.FORBIDDEN);
            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.ACTIVE);
            verify(partnershipHistoryRepository, never()).save(any());
        }
    }

    private Partnership active() {
        Partnership partnership = Partnership.request(requester, exam);
        partnership.approve();
        return partnership;
    }

    private UUID givenPartnership(Partnership partnership) {
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(partnership, "id", id);
        given(partnershipRepository.findById(id)).willReturn(Optional.of(partnership));
        return id;
    }

    private PartnershipHistory savedHistory() {
        ArgumentCaptor<PartnershipHistory> captor = ArgumentCaptor.forClass(PartnershipHistory.class);
        verify(partnershipHistoryRepository).save(captor.capture());
        return captor.getValue();
    }

    private Institution institution(InstitutionType type, String code) {
        Institution institution = Institution.create("테스트기관", type, "서울특별시 중구 세종대로 1",
                type.isMedical() ? code : null);
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
