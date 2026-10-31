package com.zifang.z.mask.core.domain;

import com.zifang.z.sense.core.domain.SensitiveCategory;
import com.zifang.z.sense.core.domain.SensitiveLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 脱敏策略（**策略即数据**）：某类敏感数据用什么方式脱敏。
 *
 * <p><b>策略由三部分组成</b>：
 * <ul>
 *   <li>{@link #maskType} —— 脱敏类型（决定用哪个算法）；</li>
 *   <li>{@link #params} —— 类型相关的参数（如掩码保留位数、哈希算法、加密密钥引用）；</li>
 *   <li>{@link #minLevel} —— **生效下限**：只有达到该敏感级别的数据才走本策略
 *       （低于下限 ⇒ 不脱敏）。</li>
 * </ul>
 *
 * <p><b>为什么要有 minLevel</b>：同一类数据在不同场景的脱敏强度不同 ——
 * 对内展示要部分保留，对外输出要全遮蔽。用分级下限表达「本策略管到哪一级」，
 * 比复制多份策略干净。
 *
 * @author zifang
 */
public class MaskPolicy {

    /** 策略标识（唯一）。 */
    private String policyId;

    /** 适用类别（{@code null} ⇒ 适用于所有类别）。 */
    private SensitiveCategory category;

    /** 脱敏类型。 */
    private MaskType maskType;

    /** 生效下限（敏感级别；低于此级别不脱敏）。 */
    private SensitiveLevel minLevel;

    /** 类型相关参数。 */
    private final Map<String, String> params = new HashMap<>();

    public MaskPolicy() {
    }

    public MaskPolicy(String policyId, SensitiveCategory category, MaskType maskType,
                      SensitiveLevel minLevel) {
        this.policyId = policyId;
        this.category = category;
        this.maskType = maskType;
        this.minLevel = minLevel;
    }

    /**
     * 取参数（可空）。
     *
     * @param key     参数名
     * @param defaultValue 缺省值
     * @return 参数值
     */
    public String param(String key, String defaultValue) {
        String v = params.get(key);
        return v == null ? defaultValue : v;
    }

    /**
     * 取整数参数（可空 / 非法 ⇒ 回落缺省）。
     *
     * @param key          参数名
     * @param defaultValue 缺省值
     * @return 参数值
     */
    public int intParam(String key, int defaultValue) {
        String v = params.get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public String getPolicyId() {
        return policyId;
    }

    public void setPolicyId(String policyId) {
        this.policyId = policyId;
    }

    public SensitiveCategory getCategory() {
        return category;
    }

    public void setCategory(SensitiveCategory category) {
        this.category = category;
    }

    public MaskType getMaskType() {
        return maskType;
    }

    public void setMaskType(MaskType maskType) {
        this.maskType = maskType;
    }

    public SensitiveLevel getMinLevel() {
        return minLevel;
    }

    public void setMinLevel(SensitiveLevel minLevel) {
        this.minLevel = minLevel;
    }

    public Map<String, String> getParams() {
        return params;
    }

    /**
     * 放参数。
     *
     * @param key   参数名
     * @param value 参数值
     * @return 本策略（链式）
     */
    public MaskPolicy with(String key, String value) {
        params.put(key, value);
        return this;
    }

    @Override
    public String toString() {
        return "MaskPolicy{" + policyId + ", " + category + ", " + maskType + ", min=" + minLevel + "}";
    }
}
