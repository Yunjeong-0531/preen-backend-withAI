package com.staybook.payment.service;

import com.staybook.payment.repository.RefundRepository;
import com.staybook.reservation.dto.ReservationDtos.RefundInfo;
import com.staybook.reservation.service.RefundInfoProvider;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약별 환불 정보 조회.
 * PaymentService가 ReservationService를 쓰므로, 순환 참조가 생기지 않게 별도 빈으로 둔다.
 */
@Service
@RequiredArgsConstructor
public class RefundQueryService implements RefundInfoProvider {

    private final RefundRepository refundRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, RefundInfo> findByReservationIds(Collection<Long> reservationIds) {
        if (reservationIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, RefundInfo> result = new HashMap<>();
        // 오래된 것부터 오므로 한 예약에 환불이 여러 건이면 가장 최근 것이 남는다
        refundRepository.findByReservationIds(reservationIds).forEach(r -> result.put(r.getReservationId(),
                new RefundInfo(r.getAmount(), r.getPenaltyAmount(), r.getStatus().name(), r.getRefundedAt())));
        return result;
    }
}
