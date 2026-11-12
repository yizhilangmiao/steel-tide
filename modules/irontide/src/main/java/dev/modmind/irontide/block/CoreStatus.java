package dev.modmind.irontide.block;

/**
 * 核心面板 / 聊天提示用的四态展示。
 * <p>
 * 和 {@link CoreState} 的区别：{@link CoreState} 是存档里的状态机（未成型 / 运转 / 摧毁），
 * 这里额外把「结构完整但没燃料」单独拆成 {@link #HALTED}，让玩家一眼看出是「没料」还是「没搭好」。
 * 低燃料不单独成态，作为 {@link #RUNNING} 的附加说明（转速会掉，但还在运转）。
 */
public enum CoreStatus {
    /** 结构不完整，核心还没成型。 */
    INCOMPLETE("未成型"),
    /** 结构完整、有燃料，正常运转。 */
    RUNNING("运转中"),
    /** 结构完整但燃料耗尽，已经停机。 */
    HALTED("停机"),
    /** 怪潮打空了血量，等维修。 */
    DESTROYED("已摧毁");

    private final String label;

    CoreStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}