import com.alibaba.fastjson2.JSON;

import java.util.Map;

public class Probe3 {

    /** 你给的结构：一个插件一个字段。 */
    record PluginData(Map<String, Boolean> memoryCurve, Map<String, Boolean> favorite) {
    }

    public static void main(String[] args) {
        String file = "{\"memory-curve\":{\"card-1\":true},\"favorite\":{\"note-1\":true},"
                + "\"third-plugin\":{\"x-1\":true}}";
        PluginData data = JSON.parseObject(file, PluginData.class);
        System.out.println("读文件: " + file);
        System.out.println("  解析后 memoryCurve = " + data.memoryCurve());
        System.out.println("  解析后 favorite    = " + data.favorite());
        System.out.println("  写回文件: " + JSON.toJSONString(data));
        System.out.println();
        System.out.println("再试：把键名也改成驼峰（memoryCurve）");
        String camel = "{\"memoryCurve\":{\"card-1\":true},\"favorite\":{\"note-1\":true},"
                + "\"third-plugin\":{\"x-1\":true}}";
        PluginData d2 = JSON.parseObject(camel, PluginData.class);
        System.out.println("  解析后 memoryCurve = " + d2.memoryCurve());
        System.out.println("  解析后 favorite    = " + d2.favorite());
        System.out.println("  写回文件: " + JSON.toJSONString(d2));
    }
}
