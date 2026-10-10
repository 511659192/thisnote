package com.thisnode.ext.memorycurve;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.api.PluginRegister;
import org.pf4j.Extension;
import org.springframework.stereotype.Component;

/**
 * 插件向平台贡献的全部能力（唯一的 PF4J 扩展点）：显示名/说明/健康 + per-entity 状态回调。
 *
 * <p>这里**必须**加 {@code @Component}：本扩展注入了插件自己的 {@link SrsService}（有参构造），
 * 而 SBP 的扩展工厂在找不到对应 bean 时会退回反射实例化（要求无参构造）—— 注册成 bean 才走
 * 「从插件容器取、带注入」那条路。不需要注入的扩展只需 {@code @Extension}。
 */
@Extension
@Component
public class MemoryCurveRegister implements PluginRegister {

    private final SrsService srs;

    public MemoryCurveRegister(SrsService srs) {
        this.srs = srs;
    }

    /** 卡片被开启记忆曲线时，建一条「立即到期」的排期（插件容器里的 bean，可直接注入服务）。 */
    @Override
    public void onEntityStateChanged(String entityId, boolean enabled) {
        if (enabled) {
            srs.createIfAbsent(entityId);
        }
    }

    @Override
    public String description() {
        return "参考 Anki 的 SM-2 间隔重复：按卡片安排复习，遗忘即回退，间隔随掌握程度增长";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("开启记忆曲线的卡片会按间隔出现在复习页");
    }

    @Override
    public String title() {
        return "记忆曲线";
    }
}
