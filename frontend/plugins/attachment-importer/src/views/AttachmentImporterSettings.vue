<template>
  <section class="attachment-importer-settings">
    <h3>附件自动导入</h3>
    <p>
      插件每 10 秒扫描一次 <code>notes/attachments</code>：新出现的图片只要还没有被任何卡片引用，
      就自动生成一篇笔记和一张图片卡片，归入「收集箱」。
    </p>
    <ul>
      <li>已被卡片引用的图片会跳过，编辑器里粘贴的图片不会重复生成笔记。</li>
      <li>
        文件名会被规范成 <code>&lt;uuid&gt;.&lt;扩展名&gt;</code>（附件渲染只认这种名字），
        原文件名保留为笔记标题。
      </li>
      <li>不保存导入状态：每轮都按「没有被任何卡片引用」判断，引用关系由 notes 提供；已被引用的图片不会重复生成笔记。</li>
      <li>卡片框体按图片原始尺寸生成（宽度上限 680），打开时图片铺满卡片，不留大片空白。</li>
      <li>只处理 png / jpg，其它文件与非普通文件一律跳过。</li>
    </ul>
  </section>
</template>

<style scoped>
.attachment-importer-settings {
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
