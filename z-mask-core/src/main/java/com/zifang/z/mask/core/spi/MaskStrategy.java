package com.zifang.z.mask.core.spi;

import com.zifang.z.mask.core.domain.MaskPolicy;

/**
 * 脱敏策略 SPI —— 每种 {@link com.zifang.z.mask.core.domain.MaskType} 对应一个实现。
 *
 * <p><b>实现约定</b>：
 * <ul>
 *   <li><b>纯函数</b>：同样的输入 + 同样的策略 ⇒ 同样的输出（脱敏结果要可复现、可审计）；</li>
 *   <li><b>不得抛异常</b>：脱敏失败要返回原文或空串，不能让安全组件拖垮主链路；</li>
 *   <li><b>不得返回原文</b>（除非策略显式声明 passthrough）—— 忘记脱敏是安全事故，
 *       所以本 SPI 的契约是「返回值一定是脱敏后的」。</li>
 * </ul>
 *
 * @author zifang
 */
public interface MaskStrategy {

    /**
     * 按策略脱敏。
     *
     * @param value  原文（可空）
     * @param policy 策略（可空 ⇒ 用策略默认参数）
     * @return 脱敏后的值（**永不为 null**；原文为 null / 空 ⇒ 返回空串）
     */
    String mask(String value, MaskPolicy policy);

    /**
     * 该策略支持的类型。
     *
     * @return 掩码类型
     */
    com.zifang.z.mask.core.domain.MaskType type();
}
