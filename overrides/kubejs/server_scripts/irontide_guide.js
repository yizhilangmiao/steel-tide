// 首次进服发放《钢铁潮汐 · 军械手册》（Patchouli 书：mod_l61phe:irontide_arms）
// 每人只发一本，标记存在 persistentData 里。临时不想发就把这个文件删掉。
PlayerEvents.loggedIn(event => {
  try {
    const player = event.player
    if (player.persistentData.getBoolean('irontide_guide_given')) return
    player.give(Item.of('patchouli:guide_book[patchouli:book="mod_l61phe:irontide_arms"]'))
    player.persistentData.putBoolean('irontide_guide_given', true)
  } catch (err) {
    console.error('[irontide] 发放军械手册失败: ' + err)
  }
})