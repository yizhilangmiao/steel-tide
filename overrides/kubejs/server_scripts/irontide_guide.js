// 首次进服发放小组件（每人只发一次，标记存在 persistentData 里）：
//   1. 钻石背包  sophisticatedbackpacks:diamond_backpack
//   2. 面包 x64  minecraft:bread
//   3. 军械手册  patchouli:guide_book[patchouli:book="mod_l61phe:irontide_arms"]
// 两个标记分开记：老玩家早就拿过手册，这次补发背包和面包时不会重复给书。
// 临时不想发就把这个文件删掉。
PlayerEvents.loggedIn(event => {
  try {
    const player = event.player
    const data = player.persistentData

    if (!data.getBoolean('irontide_starter_kit_given')) {
      player.give(Item.of('sophisticatedbackpacks:diamond_backpack'))
      player.give(Item.of('minecraft:bread', 64))
      data.putBoolean('irontide_starter_kit_given', true)
    }

    if (!data.getBoolean('irontide_guide_given')) {
      player.give(Item.of('patchouli:guide_book[patchouli:book="mod_l61phe:irontide_arms"]'))
      data.putBoolean('irontide_guide_given', true)
    }
  } catch (err) {
    console.error('[irontide] 发放开局礼包失败: ' + err)
  }
})