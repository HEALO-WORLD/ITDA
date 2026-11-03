package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.ExamType;
import healoworld.institution_service.domain.enums.Modality;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ExamTypeCreateResponse {

    private final UUID id;
    private final String name;
    private final Modality modality;
    private final Integer durationMinutes;

    public static ExamTypeCreateResponse from(ExamType examType) {
        return new ExamTypeCreateResponse(
                examType.getId(),
                examType.getName(),
                examType.getModality(),
                examType.getDurationMinutes()
        );
    }
}
