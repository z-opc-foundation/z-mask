package com.example.embedhost;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 嵌入式验收用的<b>宿主模拟应用</b>。
 *
 * <p>⚠️ <b>包树位置是刻意的</b>：本类放在 {@code com.example.embedhost}，而自动装配类在
 * {@code com.zifang.z.mask.starter.config} —— <b>两者毫无关系，组件扫描绝无可能扫到装配类</b>。
 * ⇒ 装配只可能来自 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}。
 *
 * <p>这正是「宿主应用引 starter jar」的真实机制：宿主的包树与 starter 的包树天然不相交。
 * 用 {@code com.example.*} 而不是本仓自己的包，是为了<b>不给包贴近留后门</b> ——
 * 贴近的包树会让组件扫描兜底，把 imports 文件里的笔误掩盖掉。
 *
 * @author zifang
 */
@SpringBootApplication
public class StarterHostTestApplication {
}
