#!/usr/bin/python3
"""ArDot 语言对齐表生成器：structure.json 帧文本 × strings.xml (zh↔en 按 name 连接)。
用法:
  ardot-lang-align.py --page gallery            # 生成 docs/08-UI-SPECS/screens/lang/gallery.csv
  ardot-lang-align.py --page gallery --from-en  # 已是 EN 的帧（反向：EN 值→name→zh）
输出列: frame,node_id,name,zh,en,source(matched|manual|literal),var,action(bind|skip),note,text,id
ledger.json 已有的 var（按 zh 值反查）直接复用——跨页共享文案不重复建变量。"""
import json, re, csv, argparse, os
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STRUCT = f'{ROOT}/docs/08-UI-SPECS/screens/refs/ardot/structure.json'
LEDGER = f'{ROOT}/docs/08-UI-SPECS/screens/lang/ledger.json'
OUTDIR = f'{ROOT}/docs/08-UI-SPECS/screens/lang'
RES = f'{ROOT}/androidApp/src/main/res'
CJK = re.compile(r'[一-鿿]')
PAGES = {
  'gallery':  ['gallery/empty','gallery/info','gallery/grid','gallery/search','gallery/search_no_result',
               'gallery/scanning','gallery/sort_menu','gallery/selection','gallery/settings'],
  'settings': ['settings/main_list','settings/local_models','settings/remote_models','settings/sandbox',
               'settings/developer','settings/dialog_language','settings/dialog_stage','settings/dialog_theme',
               'settings/add_remote_provider','settings/provider_config'],
  'chat':     ['chat/conversation','chat/sidebar','chat/guest-nudge-sheet'],  # chat/empty 2026-09-21 删除（被 empty-v2-guest 取代）
  'editor':   ['editor/current_crop','editor/current_adjust','editor/concept_a_hypic',
               'editor/concept_a_adjust','editor/concept_a_crop','editor/concept_a_beauty'],
  'people':   ['people/grid','people/detail'],
  'gallery_en': ['gallery/tag_control','gallery/tag_stage_sheet'],
  'chat_en':  ['chat/empty-v2-guest'],
  # 2026-08-27 Dedup 新页补绑：仅 6 规范帧；results_en 为物理英文副本帧（旧 workaround，
  # 绑定完成后删除，遵循「零物理重复帧」约定），不参与对齐。
  'dedup':    ['dedup/overview','dedup/scanning','dedup/results',
               'dedup/group_detail','dedup/keep_rules','dedup/cleaned'],
}

def strings(fname):
    out = {}
    for s in ET.parse(f'{RES}/{fname}').getroot().findall('string'):
        # 修M3：Android 反斜杠转义还原（\" \'）——ElementTree 只解码 XML 实体(&amp; 等)，
        # Android strings 的 \" 须手动还原，否则 canvas 同文被误判 no-strings-hit
        # （实证：chat_empty_welcome / tag_stage_full_note 两案根因）。
        out[s.get('name')] = ''.join(s.itertext()).strip().replace('\\"', '"').replace("\\'", "'")
    return out

def walk_texts(node, frame, acc):
    if isinstance(node, dict):
        ch = node.get('characters')
        if node.get('type') == 'TEXT' and isinstance(ch, str) and ch.strip():
            acc.append({'frame': frame, 'id': node.get('id'), 'name': node.get('name',''), 'text': ch.strip()})
        for v in node.values(): walk_texts(v, frame, acc)
    elif isinstance(node, list):
        for v in node: walk_texts(v, frame, acc)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--page', required=True, choices=PAGES)
    ap.add_argument('--from-en', action='store_true')
    a = ap.parse_args()
    # 修M5：_en 页漏传 --from-en 会按 zh 侧解析静默产出全 skip 废表——硬报错
    if a.page.endswith('_en') and not a.from_en:
        ap.error(f"--page {a.page} is an EN-source page and requires --from-en")
    zh, en = strings('values-zh-rCN/strings.xml'), strings('values/strings.xml')
    src_val, dst_val = (en, zh) if a.from_en else (zh, en)
    by_value = {}
    for name, v in src_val.items():
        by_value.setdefault(v, []).append(name)   # 一值多名：取首名，CSV note 标注多义
    src_val_set = set(src_val.values())  # 计划代码 O(n) 慢查修正：预建 set
    struct = json.load(open(STRUCT))
    texts = []
    def find(n):
        if isinstance(n, dict):
            if n.get('name') in PAGES[a.page] and n.get('id'):
                tf = []; walk_texts(n, n['name'], tf); texts.extend(tf)
            for v in n.values(): find(v)
        elif isinstance(n, list):
            for v in n: find(v)
    find(struct)
    ledger = json.load(open(LEDGER))
    zh_by_var = {v['zh']: k for k, v in ledger['variables'].items() if v.get('zh')}
    en_by_var = {v['en']: k for k, v in ledger['variables'].items() if v.get('en')}  # from_en 帧同享"跨页复用"语义（计划只在 zh 侧实现，此处补齐）
    rows = []
    for t in texts:
        val, has_cjk = t['text'], bool(CJK.search(t['text']))
        names = by_value.get(val, [])
        # 计划代码修正：ledger 反查提前到分支外——manual(no-strings-hit) 分支同样要复用已建变量，
        # 否则「今天/昨天」等无 strings 串但 ledger 已绑定的文案会拿到空 var、重复建变量。
        var = (zh_by_var.get(val) if not a.from_en else en_by_var.get(val))
        if names and (has_cjk or a.from_en or val in src_val_set):
            if not var: var = names[0].replace('_', '.')
            rows.append({**t, 'zh': val if not a.from_en else zh.get(names[0], ''),
                         'en': val if a.from_en else dst_val.get(names[0], ''),  # 修C：from-en 时 dst_val=zh 字典，en 列须写 EN 源文（text）

                         'source': 'matched' if names else 'manual', 'var': var,
                         'action': 'bind', 'note': 'multi:' + ','.join(names) if len(names) > 1 else ''})
        elif has_cjk:
            rows.append({**t, 'zh': val,
                         'en': ledger['variables'][var]['en'] if var else '',
                         'source': 'manual', 'var': var,
                         'action': 'bind',
                         'note': ('ledger:' + var) if var else 'no-strings-hit'})
        elif a.from_en:
            # 修D：EN 帧未命中 strings 的文本此前落 else 被误标 literal/skip——须 manual 留待译中文
            rows.append({**t, 'zh': '', 'en': val, 'source': 'manual', 'var': '',
                         'action': 'bind', 'note': 'no-strings-hit'})
        else:
            rows.append({**t, 'zh': val, 'en': val, 'source': 'literal', 'var': '', 'action': 'skip', 'note': 'data'})
    for r in rows: r['node_id'] = r['id']  # 修B：行 dict 原只有 id 键，fieldnames 的 node_id 列恒空
    os.makedirs(OUTDIR, exist_ok=True)
    with open(f'{OUTDIR}/{a.page}.csv', 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=['frame','node_id','name','zh','en','source','var','action','note','text','id'])
        w.writeheader(); w.writerows(rows)
    binds = [r for r in rows if r['action'] == 'bind']
    print(f"{a.page}: texts={len(rows)} bind={len(binds)} skip={len(rows)-len(binds)} "
          f"manual={sum(1 for r in binds if r['source']=='manual')} → {OUTDIR}/{a.page}.csv")

if __name__ == '__main__':
    main()
