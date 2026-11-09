package healoworld.slot_service.global.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.data.domain.Slice;

import java.util.List;

@Getter
@AllArgsConstructor
public class SliceResponse<T> {

    private final List<T> items;
    private final int page;
    private final int size;
    private final boolean hasNext;

    public static <T> SliceResponse<T> from(Slice<T> slice) {
        return new SliceResponse<>(
                slice.getContent(),
                slice.getNumber(),
                slice.getSize(),
                slice.hasNext()
        );
    }
}