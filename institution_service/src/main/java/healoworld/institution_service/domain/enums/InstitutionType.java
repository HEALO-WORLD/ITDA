package healoworld.institution_service.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InstitutionType {
    REQUESTER("의료기관", Role.REQUESTER),
    EXAM("검사기관", Role.EXAMINER),
    PLATFORM("운영사", Role.ADMIN);

    private final String description;
    private final Role memberRole;
}
