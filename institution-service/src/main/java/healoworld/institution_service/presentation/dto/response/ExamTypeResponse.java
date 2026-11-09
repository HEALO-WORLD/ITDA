package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.ExamType;
import healoworld.institution_service.domain.enums.Modality;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ExamTypeResponse {

    private final UUID id;
    private final String name;
    private final Modality modality;

    public static ExamTypeResponse from(ExamType examType) {
        return new ExamTypeResponse(
                examType.getId(),
                examType.getName(),
                examType.getModality()
        );
    }
}
