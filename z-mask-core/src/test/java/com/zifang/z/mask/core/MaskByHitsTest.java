package com.zifang.z.mask.core;

import com.zifang.z.mask.core.domain.MaskPolicy;
import com.zifang.z.mask.core.domain.MaskType;
import com.zifang.z.mask.core.service.MaskResult;
import com.zifang.z.mask.core.service.MaskService;
import com.zifang.z.mask.core.support.DefaultPolicies;
import com.zifang.z.sense.core.domain.SenseResult;
import com.zifang.z.sense.core.domain.SensitiveCategory;
import com.zifang.z.sense.core.domain.SensitiveHit;
import com.zifang.z.sense.core.domain.SensitiveLevel;
import com.zifang.z.sense.core.service.SenseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「按命中列表脱敏」重载单测（**M2：识别一次 → 脱敏一次**）。
 *
 * <p>验证的是三件事：
 * <ol>
 *   <li>重载与自识别路径**结果一致**（同一段文本，两条路脱出来一样）；</li>
 *   <li>重载**真的不识别**（换掉识别器也能脱 ⇒ 证明没走识别）；</li>
 *   <li>外部传入的脏命中列表**被兜住**（越界、重叠、契约破坏）。</li>
 * </ol>
 *
 * @author zifang
 */
class MaskByHitsTest {

    /** 真实身份证号（校验位通过 GB 11643 ⇒ 引擎会真的命中）。 */
    private static final String ID_CARD = "110101199003078515";
    /** 真实手机号（号段合法）。 */
    private static final String PHONE = "13912345678";

    private final SenseService senseService = new SenseService();
    private final MaskService service =
            new MaskService(senseService, DefaultPolicies.policies());

    // ---------------- 一致性 ----------------

    @Test
    @DisplayName("按命中脱敏：与自识别路径结果一致")
    void sameResultAsSelfDetect() {
        String text = "身份证 " + ID_CARD + " 手机 " + PHONE;

        MaskResult bySelf = service.mask(text);
        SenseResult sense = senseService.detect(text);
        MaskResult byHits = service.mask(text, sense.getHits());

        assertEquals(bySelf.getMaskedText(), byHits.getMaskedText());
        assertEquals(bySelf.getItems().size(), byHits.getItems().size());
    }

    @Test
    @DisplayName("按命中脱敏：与「传识别结果」重载结果一致")
    void sameResultAsSenseResultOverload() {
        String text = "身份证 " + ID_CARD;
        SenseResult sense = senseService.detect(text);

        MaskResult a = service.mask(text, sense);
        MaskResult b = service.mask(text, sense.getHits());

        assertEquals(a.getMaskedText(), b.getMaskedText());
    }

    @Test
    @DisplayName("按命中脱敏：真不识别（识别器一次都没被调用）")
    void doesNotCallDetector() {
        // 计数器：识别器被调用几次
        int[] calls = {0};
        SenseService counting = new SenseService(new com.zifang.z.sense.core.spi.SensitiveDetector() {
            @Override
            public SenseResult detect(String text) {
                calls[0]++;
                throw new IllegalStateException("识别器不可用");
            }

            @Override
            public String name() {
                return "counting";
            }
        });
        MaskService svc = new MaskService(counting, DefaultPolicies.policies());

        // 手工构造命中列表（模拟上游识别结果）
        List<SensitiveHit> hits = new ArrayList<>();
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L3,
                4, 4 + ID_CARD.length(), ID_CARD, "rule-id"));

        MaskResult r = svc.mask("身份证 " + ID_CARD, hits);

        assertEquals(0, calls[0], "按命中脱敏路径不应触发识别器");
        assertTrue(r.isMasked(), "按命中脱敏不该依赖识别器");
        assertFalse(r.getMaskedText().contains(ID_CARD));
        assertTrue(r.getMaskedText().contains("1101************15"), "实际：" + r.getMaskedText());
    }

    @Test
    @DisplayName("按命中脱敏：命中列表为空 ⇒ 原样返回")
    void emptyHitsKeepsText() {
        String text = "身份证 " + ID_CARD;

        MaskResult r = service.mask(text, List.of());

        assertFalse(r.isMasked());
        assertEquals(text, r.getMaskedText());
    }

    @Test
    @DisplayName("按命中脱敏：命中列表为 null ⇒ 原样返回")
    void nullHitsKeepsText() {
        String text = "身份证 " + ID_CARD;

        MaskResult r = service.mask(text, (List<SensitiveHit>) null);

        assertFalse(r.isMasked());
        assertEquals(text, r.getMaskedText());
    }

    // ---------------- 兜底：脏命中列表 ----------------

    @Test
    @DisplayName("按命中脱敏：越界命中被跳过（不抛异常、不越界替换）")
    void outOfBoundsHitSkipped() {
        String text = "身份证 " + ID_CARD;

        List<SensitiveHit> hits = new ArrayList<>();
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L3,
                4, 4 + ID_CARD.length() + 100, ID_CARD, "r1"));   // end 越界
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L3,
                -5, 10, "bad", "r2"));                             // start 为负
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L3,
                10, 10, "empty", "r3"));                           // end == start（空区间）

        MaskResult r = service.mask(text, hits);

        assertFalse(r.isMasked(), "三条脏命中都应被跳过");
        assertEquals(text, r.getMaskedText());
    }

    @Test
    @DisplayName("按命中脱敏：重叠命中只脱更高级别的那条")
    void overlappingHitsKeepHigherLevel() {
        String text = "身份证 110101199003078515";

        List<SensitiveHit> hits = new ArrayList<>();
        // L3 命中整段
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L3,
                4, 22, ID_CARD, "l3"));
        // L4 命中其中一段（与上面重叠）
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L4,
                4, 22, ID_CARD, "l4"));

        MaskResult r = service.mask(text, hits);

        assertEquals(1, r.getItems().size(), "重叠命中应只留一条");
        // L4 ⇒ 全遮蔽策略
        assertEquals("id_full", r.getItems().get(0).getPolicyId());
    }

    @Test
    @DisplayName("按命中脱敏：同级别重叠 ⇒ 先注册者留")
    void sameLevelOverlapKeepsFirst() {
        String text = "身份证 110101199003078515";

        List<SensitiveHit> hits = new ArrayList<>();
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L3,
                4, 22, ID_CARD, "first"));
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L3,
                10, 18, "11010119", "second"));

        MaskResult r = service.mask(text, hits);

        assertEquals(1, r.getItems().size());
        assertTrue(r.getMaskedText().contains("1101************15"),
                "应脱整段而不是片段，实际：" + r.getMaskedText());
    }

    @Test
    @DisplayName("按命中脱敏：多个命中 ⇒ 明细按位置升序")
    void itemsSortedByPosition() {
        String text = "手机 " + PHONE + " 身份证 " + ID_CARD;

        List<SensitiveHit> hits = new ArrayList<>();
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L3,
                text.indexOf(ID_CARD), text.indexOf(ID_CARD) + ID_CARD.length(), ID_CARD, "id"));
        hits.add(new SensitiveHit(SensitiveCategory.CONTACT, SensitiveLevel.L3,
                text.indexOf(PHONE), text.indexOf(PHONE) + PHONE.length(), PHONE, "phone"));

        MaskResult r = service.mask(text, hits);

        assertEquals(2, r.getItems().size());
        int prev = -1;
        for (MaskResult.MaskedItem item : r.getItems()) {
            assertTrue(item.getStart() > prev, "明细应按位置升序");
            prev = item.getStart();
        }
    }

    @Test
    @DisplayName("按命中脱敏：倒序替换 ⇒ 多处命中位置都不错位")
    void reverseReplaceKeepsPositions() {
        String text = "身份证 " + ID_CARD + " 手机 " + PHONE;

        List<SensitiveHit> hits = new ArrayList<>();
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L3,
                text.indexOf(ID_CARD), text.indexOf(ID_CARD) + ID_CARD.length(), ID_CARD, "id"));
        hits.add(new SensitiveHit(SensitiveCategory.CONTACT, SensitiveLevel.L3,
                text.indexOf(PHONE), text.indexOf(PHONE) + PHONE.length(), PHONE, "phone"));

        MaskResult r = service.mask(text, hits);

        assertTrue(r.getMaskedText().contains("1101************15"), "实际：" + r.getMaskedText());
        assertTrue(r.getMaskedText().contains("139****5678"), "实际：" + r.getMaskedText());
    }

    // ---------------- 契约破坏时的兜底 ----------------

    @Test
    @DisplayName("按识别结果脱敏：文本长度对不上 ⇒ 退回自识别（不漏遮、不错位）")
    void lengthMismatchFallsBackToSelfDetect() {
        // 识别结果对应的是「身份证 ...」，但脱敏时传的是另一段文本
        SenseResult sense = senseService.detect("身份证 " + ID_CARD);
        String other = "手机 " + PHONE;

        MaskResult r = service.mask(other, sense);

        // 退回自识别 ⇒ 对 other 里的手机号仍然生效
        assertTrue(r.isMasked(), "契约破坏时应退回自识别，不能漏遮");
        assertTrue(r.getMaskedText().contains("139****5678"), "实际：" + r.getMaskedText());
        assertFalse(r.getMaskedText().contains(PHONE));
    }

    @Test
    @DisplayName("按识别结果脱敏：null 识别结果 ⇒ 原样返回")
    void nullSenseResultKeepsText() {
        String text = "身份证 " + ID_CARD;

        MaskResult r = service.mask(text, (SenseResult) null);

        assertFalse(r.isMasked());
        assertEquals(text, r.getMaskedText());
    }

    @Test
    @DisplayName("按命中脱敏：null / 空文本 ⇒ 空结果不抛异常")
    void nullTextSafe() {
        assertEquals("", service.mask(null, List.of()).getMaskedText());
        assertEquals("", service.mask("", List.of()).getMaskedText());
    }

    @Test
    @DisplayName("按命中脱敏：策略未覆盖的类别 ⇒ 不脱敏")
    void unmappedCategoryStaysIntact() {
        MaskPolicy onlyBank = new MaskPolicy("bank_only", SensitiveCategory.BANK_ACCOUNT,
                MaskType.FULL, SensitiveLevel.L3);
        MaskService svc = new MaskService(senseService, List.of(onlyBank));

        String text = "手机 " + PHONE;
        List<SensitiveHit> hits = List.of(new SensitiveHit(SensitiveCategory.CONTACT,
                SensitiveLevel.L3, text.indexOf(PHONE), text.indexOf(PHONE) + PHONE.length(),
                PHONE, "phone"));

        MaskResult r = svc.mask(text, hits);

        assertFalse(r.isMasked());
        assertEquals(text, r.getMaskedText());
    }

    @Test
    @DisplayName("按命中脱敏：minLevel 下限同样贯穿（不因重载而失效）")
    void minLevelStillGates() {
        MaskPolicy l4Only = new MaskPolicy("l4_only", SensitiveCategory.CONTACT,
                MaskType.FULL, SensitiveLevel.L4);
        MaskService svc = new MaskService(senseService, List.of(l4Only));

        String text = "手机 " + PHONE;
        List<SensitiveHit> hits = List.of(new SensitiveHit(SensitiveCategory.CONTACT,
                SensitiveLevel.L3, text.indexOf(PHONE), text.indexOf(PHONE) + PHONE.length(),
                PHONE, "phone"));

        MaskResult r = svc.mask(text, hits);

        assertFalse(r.isMasked(), "L3 命中不应被 minLevel=L4 的策略脱敏");
    }

    @Test
    @DisplayName("按命中脱敏：命中为 null 元素 ⇒ 跳过不抛异常")
    void nullHitElementSkipped() {
        String text = "身份证 " + ID_CARD;
        List<SensitiveHit> hits = new ArrayList<>();
        hits.add(null);
        hits.add(new SensitiveHit(SensitiveCategory.ID_CARD, SensitiveLevel.L3,
                4, 4 + ID_CARD.length(), ID_CARD, "id"));

        MaskResult r = service.mask(text, hits);

        assertNotNull(r);
        assertEquals(1, r.getItems().size());
    }
}
