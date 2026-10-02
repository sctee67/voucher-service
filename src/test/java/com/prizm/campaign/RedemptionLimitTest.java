package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Sql({"/schema.sql", "/data.sql"})
class RedemptionLimitTest {

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void existingCampaignsDefaultToLimitOfTwo() {
        campaignRepository.findAll().forEach(c -> assertEquals(2, c.getMaxRedemptionsPerUser()));
    }

    @Test
    void userIsRejectedOnceDefaultLimitReached() {
        assertEquals("OK", voucherService.redeem("RAYA-0001", "user-1").getResult());
        assertEquals("OK", voucherService.redeem("RAYA-0002", "user-1").getResult());

        RedeemResponse third = voucherService.redeem("RAYA-0003", "user-1");
        assertEquals("FAILED", third.getResult());
        assertTrue(third.getMessage().contains("redemption limit"), third.getMessage());

        // rejected attempt must not consume the voucher or stock
        assertEquals("ACTIVE", voucherStatus("RAYA-0003"));
        assertEquals(98, remainingStock(1));
    }

    @Test
    void limitIsPerCampaign() {
        voucherService.redeem("RAYA-0001", "user-1");
        voucherService.redeem("RAYA-0002", "user-1");

        assertEquals("OK", voucherService.redeem("MRDK-0001", "user-1").getResult());
    }

    @Test
    void limitIsPerUser() {
        voucherService.redeem("RAYA-0001", "user-1");
        voucherService.redeem("RAYA-0002", "user-1");

        assertEquals("OK", voucherService.redeem("RAYA-0003", "user-2").getResult());
    }

    @Test
    void campaignCanDefineItsOwnLimit() {
        jdbc.update("UPDATE campaign SET max_redemptions_per_user = 1 WHERE id = 1");

        assertEquals("OK", voucherService.redeem("RAYA-0001", "user-1").getResult());
        assertEquals("FAILED", voucherService.redeem("RAYA-0002", "user-1").getResult());
    }

    @Test
    void voidedRedemptionDoesNotCountTowardLimit() {
        // seed: user-99 has RAYA-0004 (REDEEMED) and RAYA-0005 (VOID) on campaign 1
        assertEquals("OK", voucherService.redeem("RAYA-0001", "user-99").getResult());
        assertEquals("FAILED", voucherService.redeem("RAYA-0002", "user-99").getResult());
    }

    @Test
    void blankUserIdIsRejected() {
        assertEquals("FAILED", voucherService.redeem("RAYA-0001", " ").getResult());
        assertEquals("ACTIVE", voucherStatus("RAYA-0001"));
    }

    @Test
    void concurrentRedemptionsBySameUserCannotExceedLimit() throws Exception {
        List<String> codes = List.of("RAYA-0001", "RAYA-0002", "RAYA-0003", "RAYA-0006");
        ExecutorService pool = Executors.newFixedThreadPool(codes.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RedeemResponse>> futures = new ArrayList<>();
        for (String code : codes) {
            futures.add(pool.submit(() -> {
                start.await();
                return voucherService.redeem(code, "user-1");
            }));
        }
        start.countDown();

        int ok = 0;
        for (Future<RedeemResponse> f : futures) {
            if ("OK".equals(f.get().getResult())) {
                ok++;
            }
        }
        pool.shutdown();

        assertEquals(2, ok);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM voucher WHERE redeemed_by = 'user-1' AND status = 'REDEEMED'", Integer.class));
        assertEquals(98, remainingStock(1));
    }

    private String voucherStatus(String code) {
        return jdbc.queryForObject("SELECT status FROM voucher WHERE code = ?", String.class, code);
    }

    private int remainingStock(long campaignId) {
        return jdbc.queryForObject("SELECT remaining_stock FROM campaign WHERE id = ?", Integer.class, campaignId);
    }
}
