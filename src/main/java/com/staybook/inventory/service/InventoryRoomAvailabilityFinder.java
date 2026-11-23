package com.staybook.inventory.service;

import com.staybook.accommodation.service.RoomAvailabilityFinder;
import com.staybook.common.exception.BaseException;
import com.staybook.inventory.exception.InventoryErrorCode;
import com.staybook.inventory.repository.RoomInventoryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 숙소 목록 검색의 날짜 조건을 재고로 판정한다.
 * InventoryService가 AccommodationQueryService를 쓰므로, 순환 참조가 생기지 않게 별도 빈으로 둔다.
 */
@Component
@RequiredArgsConstructor
public class InventoryRoomAvailabilityFinder implements RoomAvailabilityFinder {

    private final RoomInventoryRepository inventoryRepository;
    private final Clock clock;

    /** 예약 가능 여부 조회(getAvailability)와 같은 기준: 모든 숙박일에 재고 행이 있고, 마감이 아니며, 남은 수량이 있다. */
    @Override
    @Transactional(readOnly = true)
    public List<Long> findAvailableRoomIds(LocalDate checkIn, LocalDate checkOut) {
        long nights = InventoryService.validateStay(checkIn, checkOut);
        if (checkIn.isBefore(LocalDate.now(clock))) {
            throw new BaseException(InventoryErrorCode.INVALID_DATE_RANGE);
        }
        return inventoryRepository.findRoomIdsAvailableForStay(checkIn, checkOut, nights);
    }
}
