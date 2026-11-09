package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.global.config.QuerydslConfig;

import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import(QuerydslConfig.class)
class ExaminationRepositoryTest extends PostgresContainerSupport {

    @Autowired
    private ExaminationRepository examinationRepository;

    @Autowired
    private ReferralRepository referralRepository;

    // 컨트롤러와 같은 정렬 (생성일 최신순, 같으면 ID 내림차순)
    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    // 테스트용 검사 데이터 생성 (확정된 의뢰에 연결)
    private Examination createExamination(UUID institutionId) {
        return createExamination(institutionId, ReferralStatus.CONFIRMED, false);
    }

    // 지정한 상태의 의뢰에 연결된 검사를 만든다. deleted면 의뢰를 soft delete한다 (취소된 의뢰만 삭제 가능)
    private Examination createExamination(UUID institutionId, ReferralStatus referralStatus, boolean deleted) {

        Referral referral = Referral.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "테스트 환자", UUID.randomUUID());
        referral.confirm();
        if (referralStatus == ReferralStatus.CANCELED) {
            referral.cancel();
        } else if (referralStatus == ReferralStatus.NEEDS_RESCHEDULE) {
            referral.requestReschedule();
        }
        if (deleted) {
            referral.delete(UUID.randomUUID());
        }
        referralRepository.saveAndFlush(referral);

        Examination examination = Examination.schedule(
                referral.getId(),  // 의뢰 ID
                UUID.randomUUID(), // 예약 ID
                institutionId      // 검사기관 ID
        );

        return examinationRepository.saveAndFlush(examination);
    }

    @Test
    @DisplayName("검사기관에 속한 검사 목록을 조회한다")
    void findByExamInstitutionId_success() {

        // given
        UUID institutionId = UUID.randomUUID();

        createExamination(institutionId);
        createExamination(institutionId);

        // when
        Page<Examination> result =
                examinationRepository.findByExamInstitutionId(
                        institutionId,
                        PageRequest.of(0, 10)
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).hasSize(2);

        assertThat(result.getContent())
                .allMatch(examination ->
                        examination.getExamInstitutionId()
                                .equals(institutionId)
                );
    }

    @Test
    @DisplayName("다른 검사기관의 검사는 조회되지 않는다")
    void findByExamInstitutionId_excludesOtherInstitution() {

        // given
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();

        Examination examinationA = createExamination(institutionA);
        Examination examinationB = createExamination(institutionB);

        // when
        Page<Examination> result =
                examinationRepository.findByExamInstitutionId(
                        institutionA,
                        PageRequest.of(0, 10)
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);

        assertThat(result.getContent())
                .extracting(Examination::getId)
                .containsExactly(examinationA.getId())
                .doesNotContain(examinationB.getId());
    }

    @Test
    @DisplayName("소속 기관에 검사가 없으면 빈 페이지를 반환한다")
    void findByExamInstitutionId_empty() {

        // given
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();

        createExamination(institutionB);

        // when
        Page<Examination> result =
                examinationRepository.findByExamInstitutionId(
                        institutionA,
                        PageRequest.of(0, 10)
                );

        // then
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("검사 목록은 페이지네이션을 지원한다")
    void findByExamInstitutionId_pagination() {

        // given
        UUID institutionId = UUID.randomUUID();

        createExamination(institutionId);
        createExamination(institutionId);
        createExamination(institutionId);

        // when
        Page<Examination> firstPage =
                examinationRepository.findByExamInstitutionId(
                        institutionId,
                        PageRequest.of(0, 2)
                );

        Page<Examination> secondPage =
                examinationRepository.findByExamInstitutionId(
                        institutionId,
                        PageRequest.of(1, 2)
                );

        // then
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getContent()).hasSize(1);

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);

        assertThat(firstPage.getNumber()).isZero();
        assertThat(secondPage.getNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("취소된 의뢰의 검사는 목록과 전체 건수에서 제외되고, 재조정 필요 의뢰의 검사는 포함된다")
    void findByExamInstitutionId_excludesCanceledReferral() {

        // given
        UUID institutionId = UUID.randomUUID();

        Examination confirmed = createExamination(institutionId, ReferralStatus.CONFIRMED, false);
        Examination needsReschedule = createExamination(institutionId, ReferralStatus.NEEDS_RESCHEDULE, false);
        Examination canceled = createExamination(institutionId, ReferralStatus.CANCELED, false);

        // when
        Page<Examination> result =
                examinationRepository.findByExamInstitutionId(
                        institutionId,
                        PageRequest.of(0, 10, LIST_SORT)
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .extracting(Examination::getId)
                .containsExactlyInAnyOrder(confirmed.getId(), needsReschedule.getId())
                .doesNotContain(canceled.getId());
    }

    @Test
    @DisplayName("soft delete된 의뢰의 검사는 목록과 전체 건수에서 제외된다")
    void findByExamInstitutionId_excludesDeletedReferral() {

        // given
        UUID institutionId = UUID.randomUUID();

        Examination confirmed = createExamination(institutionId);
        Examination deleted = createExamination(institutionId, ReferralStatus.CANCELED, true);

        // when
        Page<Examination> result =
                examinationRepository.findByExamInstitutionId(
                        institutionId,
                        PageRequest.of(0, 10, LIST_SORT)
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent())
                .extracting(Examination::getId)
                .containsExactly(confirmed.getId())
                .doesNotContain(deleted.getId());
    }

    @Test
    @DisplayName("제외 대상이 섞여 있어도 페이지 내용과 전체 건수·페이지 수가 같은 조건으로 계산된다")
    void findByExamInstitutionId_paginationWithExcludedReferrals() {

        // given: 유효 3건 + 취소 1건 + 삭제 1건
        UUID institutionId = UUID.randomUUID();

        Set<UUID> visibleIds = Set.of(
                createExamination(institutionId).getId(),
                createExamination(institutionId).getId(),
                createExamination(institutionId).getId()
        );
        createExamination(institutionId, ReferralStatus.CANCELED, false);
        createExamination(institutionId, ReferralStatus.CANCELED, true);

        // when
        Page<Examination> firstPage =
                examinationRepository.findByExamInstitutionId(institutionId, PageRequest.of(0, 2, LIST_SORT));
        Page<Examination> secondPage =
                examinationRepository.findByExamInstitutionId(institutionId, PageRequest.of(1, 2, LIST_SORT));

        // then
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(secondPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);

        // 두 페이지를 합치면 유효한 검사 3건이 중복·누락 없이 나온다
        assertThat(Stream.concat(firstPage.getContent().stream(), secondPage.getContent().stream())
                .map(Examination::getId))
                .containsExactlyInAnyOrderElementsOf(visibleIds);
    }
}