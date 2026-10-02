package com.prizm.campaign.repository;

import com.prizm.campaign.model.Voucher;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    Voucher findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.code = :code")
    Voucher findByCodeForUpdate(@Param("code") String code);

    List<Voucher> findByCampaignId(Long campaignId);

    long countByCampaignIdAndRedeemedByAndStatus(Long campaignId, String redeemedBy, String status);
}
