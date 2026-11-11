# -*- coding: utf-8 -*-
"""校验 irontide 多方块 allowRotation 的四个等价布局。

规则与 MultiblockDefinition.rotate 完全一致：(dx, dz) -> (-dz, dx)。
只复刻解析与旋转，不加载 Minecraft 类，因此可以独立运行。
"""
import json
import sys

CONTROLLER = "C"


def parse(path):
    with open(path, encoding="utf-8") as handle:
        root = json.load(handle)
    edge = root["edge"]
    markers = root.get("markers", {})
    controller_marker = markers.get("controller", CONTROLLER)
    layers = root["layers"]

    ctrl_layer = ctrl_row = ctrl_col = None
    count = 0
    for key, rows in layers.items():
        for z, row in enumerate(rows):
            for x, symbol in enumerate(row):
                if symbol == controller_marker:
                    count += 1
                    ctrl_layer, ctrl_row, ctrl_col = int(key), z, x
    assert count == 1, f"controller marker 必须唯一，实际 {count} 个"
    assert ctrl_layer == 0, f"controller 必须在 Y=0 层，实际 Y={ctrl_layer}"

    cells = []
    for key, rows in layers.items():
        assert len(rows) == edge, f"layer {key} 行数应为 {edge}"
        for z, row in enumerate(rows):
            assert len(row) == edge, f"layer {key}[{z}] 列数应为 {edge}"
            for x, symbol in enumerate(row):
                cells.append((x - ctrl_col, int(key) - ctrl_layer, z - ctrl_row, symbol))
    cells.sort(key=lambda c: (c[1], c[2], c[0]))
    return root, cells


def rotate(cells, step):
    out = []
    for dx, dy, dz, symbol in cells:
        for _ in range(step):
            dx, dz = -dz, dx
        out.append((dx, dy, dz, symbol))
    out.sort(key=lambda c: (c[1], c[2], c[0]))
    return out


def bounds(cells):
    xs = [c[0] for c in cells]
    zs = [c[2] for c in cells]
    return min(xs), max(xs), min(zs), max(zs)


def where_is_controller(cells):
    xmin, xmax, zmin, zmax = bounds(cells)
    if xmin == 0 and zmin < 0:
        return "西边中点"
    if xmax == 0 and zmin < 0:
        return "东边中点"
    if zmin == 0 and xmin < 0:
        return "北边中点"
    if zmax == 0 and xmin < 0:
        return "南边中点"
    return "包围盒内部/角上"


def render(cells, dy):
    xmin, xmax, zmin, zmax = bounds(cells)
    grid = {}
    for dx, cy, dz, symbol in cells:
        if cy == dy:
            grid[(dx, dz)] = symbol
    lines = []
    for z in range(zmin, zmax + 1):
        row = "".join(grid.get((x, z), " ") for x in range(xmin, xmax + 1))
        lines.append("    " + row)
    return lines


def main(path):
    root, base = parse(path)
    rotatable = bool(root.get("allowRotation"))
    print(f"文件: {path}")
    print(f"id={root['id']} tier={root['tier']} edge={root['edge']} allowRotation={rotatable}")
    print(f"格子总数: {len(base)}，控制器: {sum(1 for c in base if c[3] == CONTROLLER)} 个")

    layouts = [base] if not rotatable else [base] + [rotate(base, s) for s in (1, 2, 3)]
    labels = ["rot0", "rot90", "rot180", "rot270"]

    failed = False
    for index, layout in enumerate(layouts):
        name = labels[index]
        print(f"\n=== {name} ===")
        for dy in (-1, 0, 1):
            rows = render(layout, dy)
            if rows:
                print(f"  Y={dy:+d}:")
                print("\n".join(rows))
        pos = where_is_controller(layout)
        print(f"  控制器位置: {pos}｜包围盒 dx[{bounds(layout)[0]},{bounds(layout)[1]}] dz[{bounds(layout)[2]},{bounds(layout)[3]}]")
        if len(layout) != len(base):
            print("  [FAIL] 格子数与主布局不一致")
            failed = True
        if pos == "包围盒内部/角上":
            print("  [FAIL] 控制器没有落在某条边的中点")
            failed = True

    if rotatable:
        back = rotate(base, 4)
        same = back == base
        print(f"\n旋转 4 次回到原布局: {'是' if same else '否'}")
        if not same:
            failed = True
        names = [where_is_controller(l) for l in layouts]
        if len(set(names)) != 4:
            print(f"[FAIL] 四个朝向没有覆盖四条边：{names}")
            failed = True
        else:
            print(f"四个朝向覆盖: {' / '.join(names)}")

    print("\n结果: " + ("全部通过" if not failed else "存在问题，见上方 [FAIL]"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else
                  r"E:\ioncreating\mod_l61phe\modules\irontide\src\main\resources\data\irontide\config_defaults\multiblocks\core_base_3.json"))
