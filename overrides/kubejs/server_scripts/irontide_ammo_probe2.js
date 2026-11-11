// 钢铁潮汐 · 弹药组装（探针 v4）
// v3 已证实：AmmoItemBuilder 能造出带 AmmoId 的 tacz:ammo。
// 剩下的问题：TaCZ 弹药 max_stack_size = 1，setCount(5) 会被配方编码器拒绝。
// 这里验证「一次给多个 count=1 的 stack」——KubeJS 的 result 接受数组。

ServerEvents.recipes(event => {
    try {
        let one = Java.loadClass('com.tacz.guns.api.item.builder.AmmoItemBuilder')
            .create()
            .setId(Java.loadClass('net.minecraft.resources.ResourceLocation').parse('tacz:762x39'))
            .setCount(1)
            .build()

        console.info(`[irontide] 单发 = ${one}, count = ${one.getCount()}, max = ${one.getMaxStackSize()}`)

        let out = [one, one.copy(), one.copy(), one.copy()]
        console.info(`[irontide] 数组长度 = ${out.length}`)

        event.shapeless(out, ['minecraft:iron_ingot'])
        console.info('[irontide] 探针 v4 已注册：铁锭 → 762x39 ×4')
    } catch (e) {
        console.error(`[irontide] 失败: ${e}`)
    }
})
