package com.zifang.z.mask.core.support;

import com.zifang.z.mask.core.domain.MaskPolicy;
import com.zifang.z.mask.core.domain.MaskType;
import com.zifang.z.sense.core.domain.SensitiveCategory;
import com.zifang.z.sense.core.domain.SensitiveLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认脱敏策略集（M1 基线）。
 *
 * <p><b>设计原则</b>：**按敏感度递增配强度** ——
 * <ul>
 *   <li>L2 内部信息 ⇒ 掩码（保留首尾，够识别）；</li>
 *   <li>L3 敏感 ⇒ 掩码但多保留（够核对）；</li>
 *   <li>L4 核心 ⇒ 全遮蔽（一个字符都不给）。</li>
 * </ul>
 * 凭据（CREDENTIAL）单独用**哈希**：凭据原文进了日志就等于泄露，
 * 但哈希后可与其它系统的凭据指纹比对（检测复用）。
 *
 * @author zifang
 */
public final class DefaultPolicies {

    private DefaultPolicies() {
    }

    /**
     * 构造默认策略集（每次返回新列表）。
     *
     * @return 策略列表
     */
    public static List<MaskPolicy> policies() {
        List<MaskPolicy> list = new ArrayList<>();

        // ---- 凭据：哈希（不可逆，可比对指纹）----
        list.add(new MaskPolicy("cred_hash", SensitiveCategory.CREDENTIAL, MaskType.HASH,
                        SensitiveLevel.L4)
                .with("hashAlgorithm", "SHA-256")
                .with("hashLength", "16"));

        // ---- 金融账号：L4 全遮蔽 / L3 掩码 ----
        list.add(new MaskPolicy("bank_full", SensitiveCategory.BANK_ACCOUNT, MaskType.FULL,
                SensitiveLevel.L4));
        list.add(new MaskPolicy("bank_mask", SensitiveCategory.BANK_ACCOUNT, MaskType.MASK,
                SensitiveLevel.L3)
                .with("prefix", "4")
                .with("suffix", "4"));

        // ---- 证件号码：L4 全遮蔽 / L3 掩码（保留首尾）----
        list.add(new MaskPolicy("id_full", SensitiveCategory.ID_CARD, MaskType.FULL,
                SensitiveLevel.L4));
        list.add(new MaskPolicy("id_mask", SensitiveCategory.ID_CARD, MaskType.MASK,
                SensitiveLevel.L3)
                .with("prefix", "4")
                .with("suffix", "2"));

        // ---- 联系方式（手机 / 邮箱）：掩码 ----
        list.add(new MaskPolicy("contact_mask", SensitiveCategory.CONTACT, MaskType.MASK,
                SensitiveLevel.L3)
                .with("prefix", "3")
                .with("suffix", "4"));

        // ---- 位置信息：掩码 ----
        list.add(new MaskPolicy("addr_mask", SensitiveCategory.ADDRESS, MaskType.MASK,
                SensitiveLevel.L2)
                .with("prefix", "6")
                .with("suffix", "0"));

        // ---- 姓名：L3 掩码 / L2 掩码（保留姓）----
        list.add(new MaskPolicy("name_mask", SensitiveCategory.PERSONAL_NAME, MaskType.MASK,
                SensitiveLevel.L3)
                .with("prefix", "1")
                .with("suffix", "0"));

        // ---- 商业秘密 / 知识产权 / 内部信息：L3 掩码 ----
        list.add(new MaskPolicy("secret_mask", SensitiveCategory.BUSINESS_SECRET, MaskType.MASK,
                SensitiveLevel.L3)
                .with("prefix", "2")
                .with("suffix", "2"));
        list.add(new MaskPolicy("ip_mask", SensitiveCategory.IP, MaskType.MASK,
                SensitiveLevel.L3)
                .with("prefix", "2")
                .with("suffix", "2"));
        list.add(new MaskPolicy("internal_mask", SensitiveCategory.INTERNAL_INFO, MaskType.MASK,
                SensitiveLevel.L2)
                .with("prefix", "3")
                .with("suffix", "3"));

        return list;
    }
}
