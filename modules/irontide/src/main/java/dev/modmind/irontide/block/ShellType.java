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
    ANDESITE("andesite"),
    BRASS("brass"),
    STEEL("steel"),
    COPPER("copper");

    public static final StringRepresentable.EnumCodec<ShellType> CODEC =
            StringRepresentable.fromEnum(ShellType::values);

    private final String name;

    ShellType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static @Nullable ShellType byName(String name) {
        return CODEC.byName(name);
    }
}
