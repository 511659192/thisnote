<template>
  <section class="card-importer-settings">
    <h3>卡片自动导入</h3>
    <p>插件每 10 秒扫描一次笔记目录中的 <code>notes/cards/*.md</code>，只处理卡片元数据里没有登记的 Markdown 文件（无主卡片）。</p>
    <ul>
      <li>已在笔记元数据中关联的卡片会跳过。</li>
      <li>每个未关联的 Markdown 文件会创建为一条笔记，标题取文件名并放入收集箱。</li>
      <li>有效且未冲突的卡片 ID 和布局信息会保留；缺失或冲突的卡片 ID 会重新生成。</li>
    </ul>
  </section>
</template>

<style scoped>
.card-importer-settings {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 760px;
}

h3 {
  margin: 4px 0 0;
}

p,
ul {
  margin: 0;
}

ul {
  padding-left: 20px;
}

li + li {
  margin-top: 4px;
}
</style>
