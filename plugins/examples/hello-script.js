// 外部脚本示例：保存为手机上的任意 .js 文件，安装下面的插件 JSON 后
// 用「选择 / 重新选择脚本文件」把它绑上去即可。
//
// 与内联脚本相比，这里可以直接换行、写注释、拆函数——这正是引入 scriptFile 的目的。

// 台词池放在文件顶层，避免每次事件都重建
var eggLines = [
  '别点那么快嘛～',
  '再点我就生气了！',
  '咕噜咕噜～',
  '想看余额？人家知道啦'
];

on('doubleTap', function (ctx) {
  say(eggLines[Math.floor(Math.random() * eggLines.length)]);
});

on('balanceDecreased', function (ctx) {
  var used = ctx.prev - ctx.value;
  if (used > 0) {
    log('本次消耗 ' + used.toFixed(3) + ' 元');
  }
});
