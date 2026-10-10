package com.thisnode.ext.memorycurve;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.api.PluginRegister;
import org.pf4j.Extension;
import org.springframework.stereotype.Component;

/**
 * 插件向平台贡献的全部能力（唯一的 PF4J 扩展点）：显示名/说明/健康 + 可选的状态回调。
 *
 * <p>写成插件容器里的 bean（@Component）—— SBP 的扩展工厂优先从容器取 bean，
 * 于是扩展类也能注入插件自己的服务。
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
    public void onEntityStateChanged(String entityType, String entityId, boolean enabled) {
        if (SrsService.ENTITY_TYPE_BLOCK.equals(entityType) && enabled) {
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
