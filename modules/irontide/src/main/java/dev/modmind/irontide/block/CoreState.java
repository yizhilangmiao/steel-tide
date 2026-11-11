package dev.modmind.irontide.block;

public enum CoreState {
    INCOMPLETE,
    ACTIVE,
    /** 怪潮打空血量后的停机态：结构修好也不会自动复活，等维修系统接手。 */
    DESTROYED;

    public static CoreState byName(String name) {
        for (CoreState value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return INCOMPLETE;
    }
}
