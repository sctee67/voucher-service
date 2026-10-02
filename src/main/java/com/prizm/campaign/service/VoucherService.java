package com.prizm.campaign.service;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Redemption;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.repository.VoucherRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class VoucherService {

    private static final Logger log = LoggerFactory.getLogger(VoucherService.class);

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private RedemptionRepository redemptionRepository;

    @Autowired
    private AuditClient auditClient;

    public RedeemResponse redeem(String code, String userId) {

        if (userId == null || userId.isBlank()) {
            return RedeemResponse.fail("userId is required");
        }

        Voucher voucher = voucherRepository.findByCode(code);
        if (voucher == null) {
            return RedeemResponse.fail("Voucher not found");
        }

        if ("REDEEMED".equals(voucher.getStatus())) {
            return RedeemResponse.fail("Voucher already redeemed");
        }

        if ("VOID".equals(voucher.getStatus())) {
            return RedeemResponse.fail("Voucher is void");
        }

        Campaign campaign = campaignRepository.findById(voucher.getCampaignId()).get();

        if (!campaign.isActive()) {
            return RedeemResponse.fail("Campaign is not active");
        }

        // Voided vouchers keep redeemed_by but are not counted: a void cancels the redemption.
        long redeemedByUser = voucherRepository.countByCampaignIdAndRedeemedByAndStatus(
                campaign.getId(), userId, "REDEEMED");
        if (redeemedByUser >= campaign.getMaxRedemptionsPerUser()) {
            return RedeemResponse.fail("User has reached the redemption limit for this campaign ("
                    + campaign.getMaxRedemptionsPerUser() + ")");
        }

        if (campaign.getRemainingStock() <= 0) {
            return RedeemResponse.fail("Campaign out of stock");
        }

        voucher.setStatus("REDEEMED");
        voucher.setRedeemedBy(userId);
        voucher.setRedeemedAt(new Date());
        voucherRepository.save(voucher);

        campaign.setRemainingStock(campaign.getRemainingStock() - 1);
        campaignRepository.save(campaign);

        Redemption redemption = new Redemption(
                voucher.getId(), campaign.getId(), userId, new Date());
        redemptionRepository.save(redemption);

        try {
            auditClient.recordRedemption(campaign.getClientCode(), voucher.getCode(), userId);
        } catch (Exception e) {
            log.error("audit call failed");
        }

        return RedeemResponse.ok(voucher.getCode(), campaign.getRemainingStock());
    }

    public RedeemResponse voidVoucher(String code) {
        Voucher voucher = voucherRepository.findByCode(code);
        if (voucher == null) {
            return RedeemResponse.fail("Voucher not found");
        }
        voucher.setStatus("VOID");
        voucherRepository.save(voucher);
        return RedeemResponse.ok(voucher.getCode(), null);
    }
}
