package dev.modmind.irontide.config;

/** 四类核心模块。等级从 1 开始（I / II / III）。 */
public enum ModuleType {
    FUEL("燃料"),
    ARMOR("装甲"),
    BEACON("信标"),
    TRANSFER("传输");

    private final String label;

    ModuleType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
