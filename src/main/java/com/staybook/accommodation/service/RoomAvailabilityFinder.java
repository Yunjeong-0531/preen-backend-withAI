package com.staybook.accommodation.service;

import java.time.LocalDate;
import java.util.List;

/**
 * 숙소 목록 검색의 날짜 조건에 쓰는 연결 지점.
 * 의존 방향이 inventory → accommodation이므로 인터페이스는 accommodation에 두고 inventory가 구현한다.
 */
public interface RoomAvailabilityFinder {

    /**
     * 그 기간(checkIn 이상 checkOut 미만)의 모든 숙박일에 예약할 수 있는 객실의 ID.
     * 날짜가 올바르지 않으면(1박 미만, 지난 날짜, 최대 박수 초과) 예외를 던진다.
     */
    List<Long> findAvailableRoomIds(LocalDate checkIn, LocalDate checkOut);
}
