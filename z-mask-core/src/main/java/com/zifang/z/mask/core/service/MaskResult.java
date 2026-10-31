package com.zifang.z.mask.core.service;

import com.zifang.z.mask.core.domain.MaskType;

import java.util.Collections;
import java.util.List;

/**
 * 脱敏结果（脱敏后文本 + 逐项明细）。
 *
 * <p><b>为什么带明细</b>：审计与合规要能回答「这份数据脱敏了什么、用了什么策略」——
 * 只给脱敏后的文本，事后无法自证。
 *
 * @author zifang
 */
public class MaskResult {

    /** 脱敏后的文本。 */
    private final String maskedText;

    /** 逐项明细（按位置升序）。 */
    private final List<MaskedItem> items;

    public MaskResult(String maskedText, List<MaskedItem> items) {
        this.maskedText = maskedText == null ? "" : maskedText;
        this.items = Collections.unmodifiableList(items == null ? List.of() : items);
    }

    public String getMaskedText() {
        return maskedText;
    }

    public List<MaskedItem> getItems() {
        return items;
    }

    /** 是否发生了脱敏。 */
    public boolean isMasked() {
        return !items.isEmpty();
    }

    /**
     * 一条脱敏明细。
     */
    public static class MaskedItem {

        /** 原文起始偏移。 */
        private final int start;
        /** 原文结束偏移（不含）。 */
        private final int end;
        /** 原文。 */
        private final String original;
        /** 脱敏后的值。 */
        private final String masked;
        /** 命中的策略标识。 */
        private final String policyId;
        /** 使用的脱敏类型。 */
        private final MaskType maskType;

        public MaskedItem(int start, int end, String original, String masked,
                          String policyId, MaskType maskType) {
            this.start = start;
            this.end = end;
            this.original = original;
            this.masked = masked;
            this.policyId = policyId;
            this.maskType = maskType;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public String getOriginal() {
            return original;
        }

        public String getMasked() {
            return masked;
        }

        public String getPolicyId() {
            return policyId;
        }

        public MaskType getMaskType() {
            return maskType;
        }

        @Override
        public String toString() {
            return "MaskedItem{" + policyId + ", " + maskType + ", [" + start + "," + end + ")}";
        }
    }
}
