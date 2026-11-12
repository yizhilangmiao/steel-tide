package dev.modmind.irontide.block;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

/**
 * 结构外壳的材质种类。
 * <p>
 * 序列化名就是 blockstate 里 {@code type=*} 的值；新增一种材质只需加一行枚举 +
 * 一张贴图 + 一条 blockstate，别处不用改。
 */
public enum ShellType implements StringRepresentable {
    ANDESITE("andesite", "安山"),
    BRASS("brass", "黄铜"),
    STEEL("steel", "钢"),
    COPPER("copper", "铜");

    public static final StringRepresentable.EnumCodec<ShellType> CODEC =
            StringRepresentable.fromEnum(ShellType::values);

    private final String name;
    private final String displayName;

    ShellType(String name, String displayName) {
        this.name = name;
        this.displayName = displayName;
    }

    /** 面板/日志里的中文名。 */
    public String displayName() {
        return displayName;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static @Nullable ShellType byName(String name) {
        return CODEC.byName(name);
    }
}
