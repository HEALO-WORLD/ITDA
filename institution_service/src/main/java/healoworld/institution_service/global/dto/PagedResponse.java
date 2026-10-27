package healoworld.institution_service.global.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PagedResponse<T> (
        List<T> contents,       // 현재 페이지에 포함된 데이터 리스트
        long totalElements,     // 전체 항목 수
        int totalPages,         // 전체 페이지 수
        int size,               // 페이지 크기
        int number              // 현재 페이지 번호 (0부터 시작)

) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),      // 현재 페이지의 실제 데이터
                page.getTotalElements(),// 전체 요소 수
                page.getTotalPages(),   // 총 페이지 수
                page.getSize(),         // 페이지 크기
                page.getNumber()        // 현재 페이지 번호

        );
    }
}