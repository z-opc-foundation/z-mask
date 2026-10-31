package com.zifang.z.mask.core.domain;

/**
 * 脱敏类型（**策略选择键**）。
 *
 * <p>每种类型对应一个 {@link com.zifang.z.mask.core.spi.MaskStrategy} 实现。
 * 类型本身**不含算法** —— 算法在策略实现里，类型只做选择与存储。
 *
 * @author zifang
 */
public enum MaskType {

    /** 全遮蔽：整段替换成固定字符（最保守）。 */
    FULL("全遮蔽"),
    /** 掩码：保留首尾若干位，中间替换成掩码符（张*三 / 138****5678）。 */
    MASK("掩码"),
    /** 部分保留：只保留指定位置（身份证留生日段）。 */
    PARTIAL("部分保留"),
    /** 哈希：不可逆摘要（适合做关联查询，不泄露原文）。 */
    HASH("哈希"),
    /** 加密：可逆密文（需要密钥出口）。 */
    ENCRYPT("加密"),
    /** 自定义：由业务方提供的策略实现。 */
    CUSTOM("自定义");

    private final String label;

    MaskType(String label) {
        this.label = label;
    }

    /** 中文标签。 */
    public String getLabel() {
        return label;
    }

    /**
     * 宽松解析类型字符串（接受枚举名、中文标签；不可识别返回 {@code null}）。
     *
     * @param code 类型字符串（可空）
     * @return 解析结果或 {@code null}
     */
    public static MaskType fromCode(String code) {
        if (code == null) {
            return null;
        }
        String s = code.trim();
        if (s.isEmpty()) {
            return null;
        }
        for (MaskType t : values()) {
            if (t.name().equalsIgnoreCase(s) || t.label.equals(s)) {
                return t;
            }
        }
        return null;
    }
}
