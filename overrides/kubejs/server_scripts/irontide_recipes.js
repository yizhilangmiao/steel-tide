// 弹药生产自动化（W7-D12）
// 本项目没装 KubeJS 的 Create 集成插件，所以用 event.custom 直接写 Create 的配方 JSON：
// 机器类型、字段名与 data/create/recipe 完全一致，由 Create 自己解析。
//
// 覆盖四条链：
//   1. 铜板   —— 压片机（铜锭 → 铜板），替代手锤
//   2. 底火   —— 压床（铜板 + 燧石 → 4 底火），替代部署器手工应用
//   3. 钢     —— 搅拌（铁粉 + 焦煤粉 → 煤铁粉，需加热），再进高炉/鼓风机烧成钢锭
//   4. 火药   —— 搅拌（木炭 + 燧石 + 沙，需加热），给黑火药一条可量产的路
// 改完要重启服务器（server_scripts 只在启动时跑，/reload 不会重载它）。
ServerEvents.recipes(event => {
  const add = (json, label) => {
    try {
      event.custom(json)
    } catch (err) {
      console.error('[irontide] 注册配方失败（' + label + '）: ' + err)
    }
  }

  // 1) 铜板：压片机
  add({
    type: 'create:pressing',
    ingredients: [{ tag: 'c:ingots/copper' }],
    results: [{ id: 'superbwarfare:copper_plate', count: 1 }]
  }, '铜板')

  // 钢板的压片版本（做枪管/部件用，同理替代手锤）
  add({
    type: 'create:pressing',
    ingredients: [{ tag: 'c:ingots/steel' }],
    results: [{ id: 'superbwarfare:steel_plate', count: 1 }]
  }, '钢板')

  // 2) 底火：压床
  add({
    type: 'create:compacting',
    ingredients: [{ tag: 'c:plates/copper' }, { item: 'minecraft:flint' }],
    results: [{ id: 'superbwarfare:primer', count: 4 }]
  }, '底火')

  // 2b) 火药：搅拌（木炭 + 燧石 + 沙），需加热
  add({
    type: 'create:mixing',
    heat_requirement: 'heated',
    ingredients: [
      { item: 'minecraft:charcoal' },
      { item: 'minecraft:flint' },
      { tag: 'c:sands' }
    ],
    results: [{ id: 'minecraft:gunpowder', count: 2 }]
  }, '火药')

  // 3) 钢：煤铁粉搅拌（铁粉 + 焦煤粉），需加热；再烧成钢锭
  add({
    type: 'create:mixing',
    heat_requirement: 'heated',
    ingredients: [
      { tag: 'c:dusts/iron' },
      { tag: 'c:dusts/coal_coke' }
    ],
    results: [{ id: 'superbwarfare:coal_iron_powder', count: 1 }]
  }, '煤铁粉')

  // 没有焦煤粉的包（Create 本体只给 c:dusts/coal）也能跑：煤粉 + 铁粉
  add({
    type: 'create:mixing',
    heat_requirement: 'heated',
    ingredients: [
      { tag: 'c:dusts/iron' },
      { tag: 'c:dusts/coal' }
    ],
    results: [{ id: 'superbwarfare:coal_iron_powder', count: 1 }]
  }, '煤铁粉（煤粉版）')

  // 4) 弹药线本体：把三类弹药从「工作台逐条手搓」改成「压床批量」，一锅出 4 份
  add({
    type: 'create:compacting',
    heat_requirement: 'heated',
    ingredients: [
      { item: 'superbwarfare:primer' },
      { tag: 'c:plates/copper' },
      { item: 'minecraft:gunpowder' },
      { tag: 'c:ingots/copper' }
    ],
    results: [{ id: 'superbwarfare:handgun_ammo', count: 128 }]
  }, '手枪弹批量')

  add({
    type: 'create:compacting',
    heat_requirement: 'heated',
    ingredients: [
      { item: 'superbwarfare:primer' },
      { tag: 'c:plates/copper' },
      { item: 'minecraft:gunpowder' },
      { tag: 'c:ingots/steel' }
    ],
    results: [{ id: 'superbwarfare:rifle_ammo', count: 96 }]
  }, '步枪弹批量')

  add({
    type: 'create:compacting',
    heat_requirement: 'heated',
    ingredients: [
      { item: 'superbwarfare:primer' },
      { tag: 'c:plates/copper' },
      { item: 'minecraft:gunpowder' },
      { tag: 'c:ingots/lead' }
    ],
    results: [{ id: 'superbwarfare:shotgun_ammo', count: 48 }]
  }, '霰弹批量')
})
