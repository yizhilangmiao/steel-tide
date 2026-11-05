package dev.modmind.irontide.block;

public enum CoreState {
    INCOMPLETE,
    ACTIVE;

    public static CoreState byName(String name) {
        for (CoreState value : values()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return INCOMPLETE;
    }
}