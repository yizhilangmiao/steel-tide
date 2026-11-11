// 钢铁潮汐 · 弹药组装（探针 v3）
// v2 还是 redeclaration：KubeJS 会在同一作用域里重复执行脚本，
// 任何顶层 const/let 只要跨两次执行就会撞名。这里改成完全不声明变量，
// 每次用 Java.loadClass 内联取类，不落任何绑定。

ServerEvents.recipes(event => {
    try {
        let stack = Java.loadClass('com.tacz.guns.api.item.builder.AmmoItemBuilder')
            .create()
            .setId(Java.loadClass('net.minecraft.resources.ResourceLocation').parse('tacz:762x39'))
            .setCount(5)
            .build()

        console.info(`[irontide] 产物 = ${stack}`)
        console.info(`[irontide] item = ${stack.getItem()}`)
        console.info(`[irontide] count = ${stack.getCount()}`)
        console.info(`[irontide] 组件 = ${stack.getComponents()}`)

        event.shapeless(stack, ['minecraft:iron_ingot'])
        console.info('[irontide] 探针配方已注册：铁锭 → 762x39 ×5')
    } catch (e) {
        console.error(`[irontide] 失败: ${e}`)
    }
})
