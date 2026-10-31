package com.zifang.z.mask.core.service;

import com.zifang.z.mask.core.domain.MaskPolicy;
import com.zifang.z.mask.core.domain.MaskType;
import com.zifang.z.mask.core.spi.MaskStrategy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置脱敏策略实现（四种：**全遮蔽 / 掩码 / 哈希 / 加密**）。
 *
 * <p><b>口径说明**：
 * <ul>
 *   <li><b>全遮蔽</b>：整段换成掩码符（默认 {@code *}）。适合「不给看，只给知道有」的场景；</li>
 *   <li><b>掩码</b>：保留首尾 N 位（默认首 1 尾 1），中间全掩码 —— 常见的「张*三 / 138****5678」；</li>
 *   <li><b>哈希</b>：SHA-256 前 N 位十六进制（默认 16 位）—— **不可逆**，适合做关联查询
 *       （同一手机号哈希后相同，可 join）但不泄露原文；</li>
 *   <li><b>加密</b>：AES-256-GCM —— **可逆**，需要密钥。密钥**只从参数引用取**，
 *       不接受明文密钥塞进策略（明文密钥进策略 = 密钥进日志、进库、进内存 dump）。</li>
 * </ul>
 *
 * <p><b>为什么加密要密钥引用</b>：组织 008 凭证规范要求凭据零明文。策略参数里放
 * {@code keyRef}（指向 z-mist 的键名），由宿主在运行时注入实际密钥。
 *
 * @author zifang
 */
public final class BuiltinMaskStrategies {

    /** 掩码默认字符。 */
    public static final String DEFAULT_MASK_CHAR = "*";

    /** 掩码策略：保留首尾位数。 */
    public static final String PARAM_PREFIX = "prefix";
    public static final String PARAM_SUFFIX = "suffix";
    public static final String PARAM_MASK_CHAR = "maskChar";

    /** 哈希策略：算法与截断长度。 */
    public static final String PARAM_HASH_ALGO = "hashAlgorithm";
    public static final String PARAM_HASH_LENGTH = "hashLength";

    /** 加密策略：密钥引用与 IV。 */
    public static final String PARAM_KEY_REF = "keyRef";
    public static final String PARAM_IV = "iv";

    private BuiltinMaskStrategies() {
    }

    /**
     * 构造全部内置策略（按类型索引）。
     *
     * @return 类型 → 策略 的映射
     */
    public static Map<MaskType, MaskStrategy> all() {
        Map<MaskType, MaskStrategy> map = new LinkedHashMap<>();
        map.put(MaskType.FULL, new FullMaskStrategy());
        map.put(MaskType.MASK, new KeepEdgeMaskStrategy());
        map.put(MaskType.HASH, new HashMaskStrategy());
        map.put(MaskType.ENCRYPT, new EncryptMaskStrategy());
        return map;
    }

    /** 全遮蔽策略：整段替换。 */
    public static class FullMaskStrategy implements MaskStrategy {

        @Override
        public String mask(String value, MaskPolicy policy) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            String maskChar = policy == null ? DEFAULT_MASK_CHAR
                    : policy.param(PARAM_MASK_CHAR, DEFAULT_MASK_CHAR);
            char c = maskChar.isEmpty() ? '*' : maskChar.charAt(0);
            StringBuilder sb = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                sb.append(c);
            }
            return sb.toString();
        }

        @Override
        public MaskType type() {
            return MaskType.FULL;
        }
    }

    /**
     * 掩码策略：保留首尾 N 位，中间掩码。
     *
     * <p><b>边界口径</b>：当 {@code 前缀 + 后缀 >= 值长} ⇒ **全部掩码**（不泄露原长度以外的内容）。
     * 长度本身要不要泄露是策略问题：默认**保留长度**（等长掩码），
     * 需隐藏长度时用 {@link FullMaskStrategy}。
     */
    public static class KeepEdgeMaskStrategy implements MaskStrategy {

        @Override
        public String mask(String value, MaskPolicy policy) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            int prefix = policy == null ? 1 : policy.intParam(PARAM_PREFIX, 1);
            int suffix = policy == null ? 1 : policy.intParam(PARAM_SUFFIX, 1);
            String maskChar = policy == null ? DEFAULT_MASK_CHAR
                    : policy.param(PARAM_MASK_CHAR, DEFAULT_MASK_CHAR);
            char c = maskChar.isEmpty() ? '*' : maskChar.charAt(0);

            int len = value.length();
            if (prefix + suffix >= len) {
                // 保留不下 ⇒ 全遮蔽（但长度仍等长）
                StringBuilder sb = new StringBuilder(len);
                for (int i = 0; i < len; i++) {
                    sb.append(c);
                }
                return sb.toString();
            }
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                if (i < prefix || i >= len - suffix) {
                    sb.append(value.charAt(i));
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        @Override
        public MaskType type() {
            return MaskType.MASK;
        }
    }

    /**
     * 哈希策略：SHA-256 摘要截断（**不可逆**）。
     *
     * <p>加盐参数 {@code salt}：不加盐时，同一手机号在两个系统哈希值相同 ⇒ 可被彩虹表关联；
     * 加盐后跨系统不可关联但也不能跨系统 join —— **这是有意的权衡**，默认不加盐。
     */
    public static class HashMaskStrategy implements MaskStrategy {

        @Override
        public String mask(String value, MaskPolicy policy) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            String algo = policy == null ? "SHA-256" : policy.param(PARAM_HASH_ALGO, "SHA-256");
            int length = policy == null ? 16 : policy.intParam(PARAM_HASH_LENGTH, 16);
            String salt = policy == null ? "" : policy.param("salt", "");
            try {
                MessageDigest md = MessageDigest.getInstance(algo);
                byte[] digest = md.digest((salt + value).getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) {
                    hex.append(String.format("%02x", b));
                }
                return hex.substring(0, Math.min(length, hex.length()));
            } catch (NoSuchAlgorithmException e) {
                // 算法不可用 ⇒ 退化为全遮蔽（**不能退回原文**）
                return new FullMaskStrategy().mask(value, policy);
            }
        }

        @Override
        public MaskType type() {
            return MaskType.HASH;
        }
    }

    /**
     * 加密策略：AES-256-GCM（**可逆**）。
     *
     * <p><b>密钥口径</b>：策略参数里只放 {@code keyRef}（密钥引用）与 {@code iv}。
     * 实际密钥由 {@link SecretResolver} 注入 —— **密钥永不进策略对象**
     * （策略可能被序列化、打印、进库）。
     */
    public static class EncryptMaskStrategy implements MaskStrategy {

        /** 密钥解析器（宿主注入；缺省 ⇒ 无法解密 ⇒ 退化为全遮蔽）。 */
        private SecretResolver resolver;

        public EncryptMaskStrategy() {
        }

        public EncryptMaskStrategy(SecretResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public String mask(String value, MaskPolicy policy) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            if (resolver == null || policy == null) {
                // 没有密钥出口 ⇒ 不能给「看起来像密文其实没加密」的东西 ⇒ 全遮蔽
                return new FullMaskStrategy().mask(value, policy);
            }
            String keyRef = policy.param(PARAM_KEY_REF, null);
            byte[] key = resolver.resolve(keyRef);
            if (key == null || key.length != 32) {
                return new FullMaskStrategy().mask(value, policy);
            }
            String iv = policy.param(PARAM_IV, null);
            if (iv == null) {
                return new FullMaskStrategy().mask(value, policy);
            }
            try {
                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                javax.crypto.spec.GCMParameterSpec spec =
                        new javax.crypto.spec.GCMParameterSpec(128, iv.getBytes(StandardCharsets.UTF_8));
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(key, "AES"), spec);
                byte[] enc = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(enc);
            } catch (Exception e) {
                return new FullMaskStrategy().mask(value, policy);
            }
        }

        @Override
        public MaskType type() {
            return MaskType.ENCRYPT;
        }
    }

    /**
     * 密钥解析器 SPI —— 把「密钥引用」翻译成实际密钥字节。
     *
     * <p>宿主实现通常从 z-mist 取（凭据零明文，组织 008 规范）。
     */
    @FunctionalInterface
    public interface SecretResolver {
        /**
         * 解析密钥。
         *
         * @param keyRef 密钥引用（策略参数 {@code keyRef}）
         * @return 密钥字节（**32 字节 = AES-256**）；取不到返回 {@code null}
         */
        byte[] resolve(String keyRef);
    }
}
