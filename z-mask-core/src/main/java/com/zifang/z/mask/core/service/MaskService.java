package com.zifang.z.mask.core.service;

import com.zifang.z.mask.core.domain.MaskPolicy;
import com.zifang.z.mask.core.domain.MaskType;
import com.zifang.z.mask.core.spi.MaskStrategy;
import com.zifang.z.sense.core.domain.SenseResult;
import com.zifang.z.sense.core.domain.SensitiveCategory;
import com.zifang.z.sense.core.domain.SensitiveHit;
import com.zifang.z.sense.core.domain.SensitiveLevel;
import com.zifang.z.sense.core.service.SenseService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脱敏门面 —— 本仓对外的**唯一主入口**。
 *
 * <p><b>核心口径</b>：脱敏**以识别结果为输入**（不是自己再扫一遍）。
 * 流程恒为「<b>识别一次 → 脱敏一次</b>」：
 * <pre>
 *   文本 ──sense.detect──▶ 命中列表（带位置）
 *                              │
 *                              ├─▶ 策略匹配（类别 + 分级 ⇒ 策略）
 *                              └─▶ 按位置替换（**倒序替换**，避免前面的替换挪动后面的位置）
 *                                  ──▶ 脱敏后文本
 * </pre>
 * 倒序替换是关键：正序替换会把后面命中的位置**推偏**（前段长度变了）⇒ 替换错位。
 *
 * <p><b>策略匹配口径</b>（{@link #resolvePolicy}）：
 * <ol>
 *   <li>先按 <b>类别 + 分级 ≥ 策略下限</b> 精确匹配；</li>
 *   <li>没命中 ⇒ 按 <b>类别</b> 匹配（忽略分级下限，取该类别最严格的一条）；</li>
 *   <li>还没命中 ⇒ 按 <b>类别 = null</b> 的通用策略匹配；</li>
 *   <li>都没有 ⇒ <b>不脱敏</b>（该类别未配策略 ⇒ 保持原样）。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：策略表不可变（构造后只读）⇒ 可并发调用。
 *
 * @author zifang
 */
public class MaskService {

    /** 敏感识别服务（脱敏的输入来源）。 */
    private final SenseService senseService;

    /** 类型 → 策略 实现。 */
    private final Map<MaskType, MaskStrategy> strategies;

    /** 策略表（有序：先注册的在前，匹配时取更具体的在前）。 */
    private final List<MaskPolicy> policies;

    public MaskService(SenseService senseService, List<MaskPolicy> policies) {
        this(senseService, policies, BuiltinMaskStrategies.all());
    }

    public MaskService(SenseService senseService, List<MaskPolicy> policies,
                       Map<MaskType, MaskStrategy> strategies) {
        this.senseService = senseService;
        this.policies = Collections.unmodifiableList(
                new ArrayList<>(policies == null ? List.of() : policies));
        this.strategies = Collections.unmodifiableMap(
                new LinkedHashMap<>(strategies == null ? Map.of() : strategies));
    }

    /**
     * 脱敏一段文本。
     *
     * @param text 待脱敏文本（可空）
     * @return 脱敏结果（含脱敏后文本 + 命中明细）
     */
    public MaskResult mask(String text) {
        if (text == null || text.isEmpty()) {
            return new MaskResult("", List.of());
        }
        SenseResult sense = senseService.detect(text);
        List<SensitiveHit> hits = sense.getHits();
        if (hits.isEmpty()) {
            return new MaskResult(text, List.of());
        }

        // 倒序替换：避免前段长度变化导致后段位置偏移
        StringBuilder sb = new StringBuilder(text);
        List<MaskResult.MaskedItem> items = new ArrayList<>();
        for (int i = hits.size() - 1; i >= 0; i--) {
            SensitiveHit hit = hits.get(i);
            MaskPolicy policy = resolvePolicy(hit);
            if (policy == null) {
                continue;
            }
            MaskStrategy strategy = strategies.get(policy.getMaskType());
            if (strategy == null) {
                continue;
            }
            String original = text.substring(hit.getStart(), hit.getEnd());
            String masked = strategy.mask(original, policy);
            if (masked == null) {
                continue;
            }
            sb.replace(hit.getStart(), hit.getEnd(), masked);
            items.add(new MaskResult.MaskedItem(hit.getStart(), hit.getEnd(), original, masked,
                    policy.getPolicyId(), policy.getMaskType()));
        }
        // 还原为按位置升序
        items.sort((a, b) -> Integer.compare(a.getStart(), b.getStart()));
        return new MaskResult(sb.toString(), items);
    }

    /**
     * 给定命中匹配脱敏策略。
     *
     * <p><b>匹配口径</b>（每一档都遵守 {@link MaskPolicy#getMinLevel()} 下限）：
     * <ol>
     *   <li>按 <b>类别</b> 匹配，取该类别中**下限最严格**（级别最高）且**覆盖本命中级别**的策略；</li>
     *   <li>没命中 ⇒ 按 <b>类别 = null</b> 的通用策略匹配（同样遵守下限）；</li>
     *   <li>都没有 ⇒ <b>不脱敏</b>（该类别未配策略 ⇒ 保持原样）。</li>
     * </ol>
     *
     * <p>⚠ **下限必须贯穿每一档**：早先的实现在「按类别兜底」那档忽略了 minLevel，
     * 导致 minLevel=L4 的策略把 L3 的数据也脱敏了 —— 下限形同虚设。
     *
     * @param hit 命中
     * @return 匹配的策略（**可能为 null** —— 表示该类别未配策略 ⇒ 不脱敏）
     */
    public MaskPolicy resolvePolicy(SensitiveHit hit) {
        if (hit == null) {
            return null;
        }
        SensitiveCategory category = hit.getCategory();
        SensitiveLevel level = hit.getLevel();

        // 1) 按类别匹配，取「覆盖本命中级别」且下限最严格的一条
        MaskPolicy best = null;
        for (MaskPolicy p : policies) {
            if (p.getCategory() != category) {
                continue;
            }
            if (!covers(p, level)) {
                continue;
            }
            if (best == null || stricter(p, best)) {
                best = p;
            }
        }
        if (best != null) {
            return best;
        }
        // 2) 通用策略（category = null），同样遵守下限
        for (MaskPolicy p : policies) {
            if (p.getCategory() == null && covers(p, level)) {
                return p;
            }
        }
        // 3) 无策略 ⇒ 不脱敏
        return null;
    }

    /** 策略是否覆盖给定的敏感级别（**下限贯穿每一档匹配**）。 */
    private static boolean covers(MaskPolicy policy, SensitiveLevel level) {
        if (policy.getMinLevel() == null) {
            return true;
        }
        if (level == null) {
            return false;
        }
        return level.atLeast(policy.getMinLevel());
    }

    /** a 是否比 b 更严格（下限更高 ⇒ 更严）。 */
    private static boolean stricter(MaskPolicy a, MaskPolicy b) {
        if (a.getMinLevel() == null) {
            return false;
        }
        if (b.getMinLevel() == null) {
            return true;
        }
        return a.getMinLevel().getWeight() > b.getMinLevel().getWeight();
    }

    /** 当前策略表（只读）。 */
    public List<MaskPolicy> getPolicies() {
        return policies;
    }
}
