package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationResultRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.response.ExaminationListResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 검사 조회 전용 서비스. 잠금 없이 읽기 전용 트랜잭션에서 조회한다
// 검사 상태 변경·생성·예약 연결은 ExaminationService가 담당한다
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExaminationQueryService {

    private final ExaminationRepository examinationRepository;
    private final ReferralRepository referralRepository;
    private final ReservationRepository reservationRepository;
    private final ExaminationResultRepository examinationResultRepository;

    public ExaminationResponse getByReferralId(UUID referralId) {
        return ExaminationResponse.from(findByReferralId(referralId));
    }

    // 검사기관별 검사 목록 조회
    public PagedResponse<ExaminationListResponse> getExaminations(
            UUID institutionId,
            RoleType role,
            Pageable pageable
    ) {

        // 1. EXAMINER 역할 검증
        if (role != RoleType.EXAMINER) {
            throw new BaseException(ErrorCode.EXAMINATION_ACCESS_DENIED);
        }

        // 2. 소속 검사기관의 검사 목록 조회
        Page<Examination> examinations =
                examinationRepository.findByExamInstitutionId(
                        institutionId,
                        pageable
                );

        // 3. 조회 결과가 없으면 빈 페이지 반환
        if (examinations.isEmpty()) {
            return PagedResponse.from(
                    examinations.map(examination -> (ExaminationListResponse) null)
            );
        }

        List<Examination> examinationList = examinations.getContent();

        // 4. 관련 ID 수집
        Set<UUID> referralIds = examinationList.stream()
                .map(Examination::getReferralId)
                .collect(Collectors.toSet());

        Set<UUID> reservationIds = examinationList.stream()
                .map(Examination::getReservationId)
                .collect(Collectors.toSet());

        Set<UUID> examinationIds = examinationList.stream()
                .map(Examination::getId)
                .collect(Collectors.toSet());

        // 5. 의뢰 정보 일괄 조회
        Map<UUID, Referral> referralMap =
                referralRepository.findAllById(referralIds)
                        .stream()
                        .collect(Collectors.toMap(
                                Referral::getId,
                                Function.identity()
                        ));

        // 6. 예약 정보 일괄 조회
        Map<UUID, Reservation> reservationMap =
                reservationRepository.findAllById(reservationIds)
                        .stream()
                        .collect(Collectors.toMap(
                                Reservation::getId,
                                Function.identity()
                        ));

        // 7. 결과가 등록된 검사 ID 일괄 조회
        Set<UUID> registeredExaminationIds = new HashSet<>(
                examinationResultRepository.findRegisteredExaminationIds(
                        examinationIds
                )
        );

        // 8. 검사·의뢰·예약·결과 정보 조합
        Page<ExaminationListResponse> responses =
                examinations.map(examination -> {

                    Referral referral =
                            referralMap.get(examination.getReferralId());

                    Reservation reservation =
                            reservationMap.get(examination.getReservationId());

                    // 연결된 의뢰 또는 예약이 없는 경우
                    if (referral == null || reservation == null) {
                        throw new IllegalStateException(
                                "검사에 연결된 의뢰 또는 예약 정보를 찾을 수 없습니다."
                        );
                    }

                    boolean resultRegistered =
                            registeredExaminationIds.contains(
                                    examination.getId()
                            );

                    return ExaminationListResponse.from(
                            examination,
                            referral,
                            reservation,
                            resultRegistered
                    );
                });

        // 9. 페이지네이션 응답 반환
        return PagedResponse.from(responses);
    }

    private Examination findByReferralId(UUID referralId) {
        return examinationRepository.findByReferralId(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.EXAMINATION_NOT_FOUND));
    }

}
