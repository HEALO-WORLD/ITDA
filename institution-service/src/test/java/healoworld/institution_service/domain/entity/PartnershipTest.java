package healoworld.institution_service.domain.entity;

import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PartnershipTest {
    private Institution requester;
    private Institution exam;


    @BeforeEach
    void setUp() {
        requester = institution(InstitutionType.REQUESTER, "11100001");
        exam = institution(InstitutionType.EXAM, "11100001");
    }

    @Nested
    @DisplayName("협약 요약")
    class Request {
        @Test
        @DisplayName("의료기관이 검사기관에 요청하면 PENDING으로 시작한다")
        void request_success() {
            Partnership partnership = Partnership.request(requester, exam);

            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.PENDING);
            assertThat(partnership.getRequestedAt()).isNotNull();
            assertThat(partnership.getApprovedAt()).isNull();
            assertThat(partnership.getTerminatedAt()).isNull();
        }

        @Test
        @DisplayName("검사기관이 요청하면 예외")
        void request_byExam() {
            Institution otherExam = institution(InstitutionType.EXAM, "11100004");

            assertErrorCode(() -> Partnership.request(otherExam, exam), ErrorCode.INVALID_PARTNERSHIP_INSTITUTION);
        }

        @Test
        @DisplayName("상대가 의료기관이면 예외")
        void request_toRequester() {
            Institution otherRequester = institution(InstitutionType.REQUESTER, "11100002");

            assertErrorCode(() -> Partnership.request(requester, otherRequester), ErrorCode.INVALID_PARTNERSHIP_INSTITUTION);
        }
    }

    @Nested
    @DisplayName("승인, 거절")
    class ApproveReject {

        @Test
        @DisplayName("PENDING을 승인하면 ACTIVE가 되고 승인 시각이 기록된다.")
        void approve_pending() {
            Partnership partnership = Partnership.request(requester, exam);

            partnership.approve();

            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.ACTIVE);
            assertThat(partnership.getApprovedAt()).isNotNull();
        }

        @Test
        @DisplayName("ACTIVE를 다시 승인하면 예외")
        void approve_active() {
            Partnership partnership = active();

            assertErrorCode(partnership::approve, ErrorCode.INVALID_PARTNERSHIP_STATUS);
        }

        @Test
        @DisplayName("PENDING을 거절하면 REJECTED")
        void reject_pending() {
            Partnership partnership = Partnership.request(requester, exam);

            partnership.reject();

            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.REJECTED);
        }

        @Test
        @DisplayName("거절된 협약은 승인할 수 없다")
        void approve_rejected() {
            Partnership partnership = Partnership.request(requester, exam);
            partnership.reject();

            assertErrorCode(partnership::approve, ErrorCode.INVALID_PARTNERSHIP_STATUS);
        }
    }

    @Nested
    @DisplayName("종료")
    class Terminate {
        @Test
        @DisplayName("ACTIVE를 종료하면 TERMINATED가 되고 종료 시각이 기록된다.")
        void terminate_active() {
            Partnership partnership = active();
            partnership.terminate();
            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.TERMINATED);
            assertThat(partnership.getTerminatedAt()).isNotNull();
        }

        @Test
        @DisplayName("승인 전(PENDING)에는 종료 할 수 없다.")
        void terminate_pending() {
            Partnership partnership = Partnership.request(requester, exam);
            assertErrorCode(partnership::terminate, ErrorCode.INVALID_PARTNERSHIP_STATUS);
        }
    }

    @Nested
    @DisplayName("재요청")
    class ReRequest {

        @Test
        @DisplayName("거절된 협약을 재요청하면 PENDING으로 돌아간다")
        void reRequest_rejected() {
            Partnership partnership = Partnership.request(requester, exam);
            partnership.reject();

            partnership.reRequest();

            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.PENDING);
            assertThat(partnership.getApprovedAt()).isNull();
        }

        @Test
        @DisplayName("종료된 협약을 재요청하면 PENDING으로 돌아가고 승인·종료 시각이 비워진다")
        void reRequest_terminated() {
            Partnership partnership = active();
            partnership.terminate();

            partnership.reRequest();

            assertThat(partnership.getStatus()).isEqualTo(PartnershipStatus.PENDING);
            assertThat(partnership.getApprovedAt()).isNull();
            assertThat(partnership.getTerminatedAt()).isNull();
        }

        @Test
        @DisplayName("진행 중(PENDING·ACTIVE)인 협약은 재요청할 수 없다")
        void reRequest_inProgress() {
            Partnership pending = Partnership.request(requester, exam);
            Partnership active = active();

            assertErrorCode(pending::reRequest, ErrorCode.INVALID_PARTNERSHIP_STATUS);
            assertErrorCode(active::reRequest, ErrorCode.INVALID_PARTNERSHIP_STATUS);
        }
    }

    @Nested
    @DisplayName("당사자 확인")
    class Party {

        @Test
        @DisplayName("의뢰기관은 당사자지만 검사기관은 아니다")
        void requester_isParty() {
            Partnership partnership = Partnership.request(requester, exam);

            assertThat(partnership.isParty(requester.getId())).isTrue();
            assertThat(partnership.isExamInstitution(requester.getId())).isFalse();
        }

        @Test
        @DisplayName("검사기관은 당사자이면서 검사기관이다")
        void exam_isParty() {
            Partnership partnership = Partnership.request(requester, exam);

            assertThat(partnership.isParty(exam.getId())).isTrue();
            assertThat(partnership.isExamInstitution(exam.getId())).isTrue();
        }

        @Test
        @DisplayName("제3기관은 당사자가 아니다")
        void other_isNotParty() {
            Partnership partnership = Partnership.request(requester, exam);
            UUID otherId = UUID.randomUUID();

            assertThat(partnership.isParty(otherId)).isFalse();
            assertThat(partnership.isExamInstitution(otherId)).isFalse();
        }
    }


    private Partnership active() {
        Partnership partnership = Partnership.request(requester, exam);
        partnership.approve();
        return partnership;
    }

    // DB 저장 전이라 id가 null → 테스트에서만 리플렉션으로 넣음
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