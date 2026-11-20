package com.staybook.payment.repository;

import com.staybook.payment.domain.Refund;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    /** 예약들의 환불 내역. 환불에는 예약 ID가 없으므로 결제를 거쳐 찾는다. 환불 ID 오름차순. */
    @Query("""
            select p.reservationId as reservationId, r.amount as amount, r.penaltyAmount as penaltyAmount,
                   r.status as status, r.refundedAt as refundedAt
            from Refund r join Payment p on p.id = r.paymentId
            where p.reservationId in :reservationIds
            order by r.id asc
            """)
    List<ReservationRefundView> findByReservationIds(@Param("reservationIds") Collection<Long> reservationIds);

    interface ReservationRefundView {

        Long getReservationId();

        BigDecimal getAmount();

        BigDecimal getPenaltyAmount();

        Refund.Status getStatus();

        LocalDateTime getRefundedAt();
    }
}
