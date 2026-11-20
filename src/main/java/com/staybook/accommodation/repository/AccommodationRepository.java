package com.staybook.accommodation.repository;

import com.staybook.accommodation.domain.Accommodation;
import com.staybook.accommodation.domain.Room;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccommodationRepository extends JpaRepository<Accommodation, Long> {

    java.util.List<Accommodation> findByHostBusinessId(Long hostBusinessId);

    java.util.List<Accommodation> findByHostBusinessIdOrderByIdDesc(Long hostBusinessId);

    /** 상태를 바꾸기 전에 숙소 행을 잠근다. 공개 전환과 객실 판매 중지가 동시에 와도 한쪽씩 처리한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Accommodation a where a.id = :id")
    Optional<Accommodation> findByIdForUpdate(@Param("id") Long id);

    /**
     * 목록 검색. 지역 조건을 쓰지 않을 때는 filterRegion=false,
     * 카테고리·인원 조건을 쓰지 않을 때는 categoryId=null, guestCount=null로 넘긴다.
     * 숙소명 조건을 쓰지 않을 때는 namePattern=null, 날짜 조건을 쓰지 않을 때는 filterRooms=false로 넘긴다.
     * namePattern은 LIKE 패턴이고 이스케이프 문자는 '!'다. 대소문자는 구분하지 않는다.
     * 인원·날짜 조건은 roomStatus인 객실 중 "최대 인원이 guestCount 이상이고 availableRoomIds에 드는" 객실이
     * 하나라도 있는지로 본다. 두 조건을 함께 쓰면 한 객실이 둘 다 만족해야 한다.
     */
    @Query("""
            select a from Accommodation a
            where a.listingStatus = :listingStatus
              and (:filterRegion = false or a.regionId in :regionIds)
              and (:categoryId is null or a.categoryId = :categoryId)
              and (:namePattern is null or lower(a.name) like lower(:namePattern) escape '!')
              and ((:guestCount is null and :filterRooms = false) or exists (
                    select 1 from Room r
                    where r.accommodationId = a.id
                      and r.status = :roomStatus
                      and (:guestCount is null or r.maxCapacity >= :guestCount)
                      and (:filterRooms = false or r.id in :availableRoomIds)))
            """)
    Page<Accommodation> search(@Param("listingStatus") Accommodation.ListingStatus listingStatus,
                               @Param("roomStatus") Room.RoomStatus roomStatus,
                               @Param("filterRegion") boolean filterRegion,
                               @Param("regionIds") Collection<Long> regionIds,
                               @Param("categoryId") Long categoryId,
                               @Param("namePattern") String namePattern,
                               @Param("guestCount") Integer guestCount,
                               @Param("filterRooms") boolean filterRooms,
                               @Param("availableRoomIds") Collection<Long> availableRoomIds,
                               Pageable pageable);
}
