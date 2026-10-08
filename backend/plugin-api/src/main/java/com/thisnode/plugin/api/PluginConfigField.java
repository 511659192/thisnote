package com.thisnode.plugin.api;

/**
 * 插件配置项的一项声明。
 *
 * <p>插件只声明「我有哪些配置」，底座负责存储、脱敏与渲染：值存在 {@code plugin_data}
 * （entityType={@code config}），{@link #secret()} 为真时读取接口只回「有没有值」不回内容，
 * 前端也只会把它渲染成密码框。
 *
 * @param key          存储键，也是插件读取时的键
 * @param label        表单标签
 * @param type         控件类型
 * @param defaultValue 默认值（可为 null）
 * @param required     是否必填（缺失时插件应把自己标成 degraded）
 * @param secret       是否敏感（如 SecretKey）：写入后永不回传
 * @param help         一行说明
 */
public record PluginConfigField(String key, String label, Type type, String defaultValue,
                                boolean required, boolean secret, String help) {

    public enum Type {
        TEXT, PASSWORD, NUMBER, BOOLEAN
    }

    public static PluginConfigField text(String key, String label, String help) {
        return new PluginConfigField(key, label, Type.TEXT, null, false, false, help);
    }

    public static PluginConfigField requiredText(String key, String label, String help) {
        return new PluginConfigField(key, label, Type.TEXT, null, true, false, help);
    }

    public static PluginConfigField secret(String key, String label, String help) {
        return new PluginConfigField(key, label, Type.PASSWORD, null, false, true, help);
    }

    public static PluginConfigField bool(String key, String label, boolean defaultValue, String help) {
        return new PluginConfigField(key, label, Type.BOOLEAN,
                String.valueOf(defaultValue), false, false, help);
    }

    public static PluginConfigField number(String key, String label, String defaultValue, String help) {
        return new PluginConfigField(key, label, Type.NUMBER, defaultValue, false, false, help);
    }
}
