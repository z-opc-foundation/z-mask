package com.zifang.z.mask.core;

import com.zifang.z.mask.core.domain.MaskPolicy;
import com.zifang.z.mask.core.domain.MaskType;
import com.zifang.z.mask.core.service.MaskResult;
import com.zifang.z.mask.core.service.MaskService;
import com.zifang.z.mask.core.support.DefaultPolicies;
import com.zifang.z.sense.core.domain.SensitiveCategory;
import com.zifang.z.sense.core.domain.SensitiveLevel;
import com.zifang.z.sense.core.service.SenseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏引擎单测。
 *
 * @author zifang
 */
class MaskServiceTest {

    private final MaskService service =
            new MaskService(new SenseService(), DefaultPolicies.policies());

    @Test
    @DisplayName("手机号脱敏：保留前 3 后 4")
    void masksMobile() {
        MaskResult r = service.mask("请拨打 13912345678 联系我");

        assertTrue(r.isMasked());
        assertTrue(r.getMaskedText().contains("139****5678"), "实际：" + r.getMaskedText());
        assertFalse(r.getMaskedText().contains("13912345678"), "原文不应残留");
    }

    @Test
    @DisplayName("银行卡 L4 ⇒ 全遮蔽")
    void masksBankCardFully() {
        MaskResult r = service.mask("卡号 6222020200011122336");

        assertTrue(r.getMaskedText().contains("*******************"), "实际：" + r.getMaskedText());
        assertFalse(r.getMaskedText().contains("6222020200011122336"));
    }

    @Test
    @DisplayName("身份证 L3 ⇒ 保留首 4 后 2")
    void masksIdCard() {
        MaskResult r = service.mask("身份证 110101199003078515");

        assertTrue(r.getMaskedText().contains("1101************15"), "实际：" + r.getMaskedText());
    }

    @Test
    @DisplayName("凭据 ⇒ 哈希（不可逆，且非原文）")
    void masksCredentialByHash() {
        MaskResult r = service.mask("password=Sup3rS3cret!");

        assertTrue(r.isMasked());
        assertFalse(r.getMaskedText().contains("Sup3rS3cret!"));
        assertTrue(r.getMaskedText().matches("(?s).*[0-9a-f]{16}.*"), "应为十六进制哈希：" + r.getMaskedText());
    }

    @Test
    @DisplayName("同一密码两次哈希值相同（可做指纹比对）")
    void hashIsStable() {
        String a = service.mask("password=Sup3rS3cret!").getMaskedText();
        String b = service.mask("password=Sup3rS3cret!").getMaskedText();

        assertEquals(a, b);
    }

    @Test
    @DisplayName("不同密码哈希值不同")
    void hashDiffers() {
        String a = service.mask("password=Sup3rS3cret!").getMaskedText();
        String b = service.mask("password=An0therPwd!").getMaskedText();

        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("多处命中全部脱敏，且位置不错位")
    void masksMultipleOccurrences() {
        String text = "手机 13912345678 和 13887654321 均为本人";
        MaskResult r = service.mask(text);

        assertEquals(2, r.getItems().size(), "应脱敏两处");
        assertFalse(r.getMaskedText().contains("13912345678"));
        assertFalse(r.getMaskedText().contains("13887654321"));
        assertTrue(r.getMaskedText().contains("139****5678"));
        assertTrue(r.getMaskedText().contains("138****4321"));
    }

    @Test
    @DisplayName("倒序替换保证后面命中的位置正确（长度变化不影响后续）")
    void maskPositionsRemainCorrect() {
        // 第一个命中（长）先脱敏会改变后面位置；倒序 ⇒ 后面的先替换
        String text = "身份证 110101199003078515 手机 13912345678";
        MaskResult r = service.mask(text);

        assertTrue(r.getMaskedText().contains("1101************15"), "实际：" + r.getMaskedText());
        assertTrue(r.getMaskedText().contains("139****5678"), "实际：" + r.getMaskedText());
    }

    @Test
    @DisplayName("明细带原文与脱敏值（供审计自证）")
    void itemsCarryAuditDetail() {
        MaskResult r = service.mask("手机 13912345678");

        MaskResult.MaskedItem item = r.getItems().get(0);
        assertNotNull(item.getOriginal());
        assertNotNull(item.getMasked());
        assertEquals("13912345678", item.getOriginal());
        assertEquals("139****5678", item.getMasked());
        assertNotNull(item.getPolicyId());
        assertEquals(MaskType.MASK, item.getMaskType());
    }

    @Test
    @DisplayName("无敏感项 ⇒ 原样返回，不做改动")
    void noSensitiveNoChange() {
        String text = "合同编号 C20261031001，金额 10000.00 元";
        MaskResult r = service.mask(text);

        assertFalse(r.isMasked());
        assertEquals(text, r.getMaskedText());
    }

    @Test
    @DisplayName("null / 空串 ⇒ 空结果不抛异常")
    void handlesNullAndEmpty() {
        assertEquals("", service.mask(null).getMaskedText());
        assertEquals("", service.mask("").getMaskedText());
    }

    @Test
    @DisplayName("策略未覆盖的类别 ⇒ 不脱敏（保持原样）")
    void unmappedCategoryStaysIntact() {
        MaskPolicy onlyBank = new MaskPolicy("bank_only", SensitiveCategory.BANK_ACCOUNT,
                MaskType.FULL, SensitiveLevel.L3);
        MaskService svc = new MaskService(new SenseService(), List.of(onlyBank));

        String text = "手机 13912345678";
        MaskResult r = svc.mask(text);

        assertFalse(r.isMasked(), "未配策略的类别不应脱敏");
        assertEquals(text, r.getMaskedText());
    }

    @Test
    @DisplayName("分级下限：策略 minLevel 以下的命中不脱敏")
    void minLevelGate() {
        // 策略只管 L4 及以上；L3 手机号不该被这条策略脱敏
        MaskPolicy l4Only = new MaskPolicy("l4_only", SensitiveCategory.CONTACT,
                MaskType.FULL, SensitiveLevel.L4);
        MaskService svc = new MaskService(new SenseService(), List.of(l4Only));

        MaskResult r = svc.mask("手机 13912345678");
        assertFalse(r.isMasked(), "L3 命中不应被 minLevel=L4 的策略脱敏");
    }

    @Test
    @DisplayName("掩码策略：保留位数之和 ≥ 原长 ⇒ 全遮蔽")
    void maskEdgeCaseTooShort() {
        MaskPolicy p = new MaskPolicy("p", SensitiveCategory.CONTACT, MaskType.MASK,
                SensitiveLevel.L3).with("prefix", "3").with("suffix", "4");
        MaskService svc = new MaskService(new SenseService(), List.of(p));

        // 5 位数（长度小于 3+4）⇒ 全遮蔽但等长
        MaskResult r = svc.mask("号码 12345");
        assertFalse(r.isMasked(), "5 位数字不是手机号，不应命中");
    }

    @Test
    @DisplayName("同类别多条策略 ⇒ 分级高的走更严格的那条")
    void higherLevelPicksStricterPolicy() {
        MaskResult r = service.mask("卡号 6222020200011122336");

        MaskResult.MaskedItem item = r.getItems().get(0);
        assertEquals("bank_full", item.getPolicyId(), "L4 卡号应走全遮蔽策略");
    }
}
