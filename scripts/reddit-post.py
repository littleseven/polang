#!/usr/bin/env python3
"""Reddit 冷启动发帖工具（官方 API / PRAW，单账号本人内容）。

帖子内容 SSOT = 本文件（原 spec docs/superpowers/specs/2026-08-24-en-launch-posts.md 已随交付清理，git 历史可查）。

用法:
  export REDDIT_CLIENT_ID=...        # reddit.com/prefs/apps → script app
  export REDDIT_CLIENT_SECRET=...
  export REDDIT_USERNAME=...
  export REDDIT_PASSWORD=...         # 若账号开了 2FA: "密码:6位动态码"

  python3 scripts/reddit-post.py --check                 # 认证+账号+版规+flair 预检（不发帖）
  python3 scripts/reddit-post.py --list                  # 仅打印将要发的内容（默认行为）
  python3 scripts/reddit-post.py --post r/AndroidApps    # 真发（一次一个版，按手册错开天数）
  python3 scripts/reddit-post.py --post r/AndroidApps --style gallery
                                                          # AndroidApps 可选图库帖（3 截图+图注），
                                                          # 正文自动转为首条评论

依赖: pip3 install praw  （PRAW 未装时本脚本会提示）
注意: Show HN 无官方提交 API，需手动在 news.ycombinator.com/submit 提交。
"""
import argparse
import os
import sys

try:
    import praw
except ImportError:
    print("缺依赖: pip3 install praw")
    sys.exit(2)

# ---- 帖子内容（SSOT，已发布冻结） ----

PLAY = "https://play.google.com/store/apps/details?id=com.mamba.picme"
GITHUB = "https://github.com/littleseven/polang"
SITE = "https://polang.net"

POSTS = {
    "r/AndroidApps": {
        "title": ("PoLang – free & open-source (MIT) AI photo gallery: "
                  "on-device semantic search, auto-tagging, face clustering, no ads"),
        "body": f"""Solo dev here. PoLang is a gallery app where the AI parts run entirely on your phone — photos never get uploaded anywhere. It's MIT-licensed and the source is on GitHub, so you can verify that claim instead of trusting it.

What it does:

- Browse — a normal gallery first: grid, timeline, albums
- Search in plain language — "sunset at the beach", "photos of my daughter last summer"; also search-by-image
- Auto-tagging & face grouping — scene/object tags (Florence-2) plus an on-device VLM (Qwen3-VL-2B via MNN); label a face once, then ask for "mom's photos"; there's a person-relationship graph too
- Chat assistant — ask it to find / organize / summarize your gallery, or edit: "make it warmer", "cut this out and make an ID photo" (it always asks before changing anything)
- Editor — filters, beauty retouch, one-tap background removal, ID photos, on a self-written OpenGL ES pipeline
- Privacy — 100% of media processing is on-device. The optional chat uses a remote LLM for text only — never photos.

Trade-offs I won't hide:

- First run downloads ~1.5 GB of on-device models (Wi-Fi), and the initial scan takes ~1.5 h per 10k photos (background; incremental afterwards)
- Chat needs internet and has a free quota (email unlocks more); the rest of the app works offline
- Android 7.0+, arm64 recommended

Links: {GITHUB} | {PLAY}&referrer=utm_source%3Dreddit%26utm_medium%3Dpost%26utm_campaign%3Dr_androidapps | {SITE}

Happy to answer anything — also genuinely interested in what would make the 1.5 GB first run less scary.""",
        "gallery": [
            ("google-play-listing/en-US/screenshots/01-gallery.png", "Browse, search by natural language, auto-organize — on-device"),
            ("google-play-listing/en-US/screenshots/02-search.png", "Semantic search: plain-language queries over your own library"),
            ("google-play-listing/en-US/screenshots/08-privacy.png", "All media processing 100% on-device — photos never uploaded"),
        ],
        "flair_keywords": ["app"],
    },
    "r/opensource": {
        "title": ("PoLang (MIT) – on-device AI photo gallery: Kotlin Multiplatform, "
                  "self-written OpenGL ES engine, MNN inference, agent layer on JetBrains Koog"),
        "body": f"""Solo dev sharing an Android app (an iOS app shares the same Kotlin Multiplatform core, in testing) that has been my playground for "AI-first client architecture": an agent that maps natural language to on-device capabilities.

Stack highlights:

- Kotlin Multiplatform :shared module — agent orchestrator + capability registry in commonMain; JetBrains Koog drives the remote-LLM tool-calls loop
- On-device inference via MNN: Qwen3-VL-2B (VLM tagging), Florence-2 (scene/object tags), CLIP-class embeddings for semantic search
- Self-developed OpenGL ES + EGL render/beauty pipeline — no third-party beauty SDK
- Layered module boundaries (beauty-api / beauty-engine), ~50 JVM test files, ktlint + detekt gates

The app itself: photo gallery with on-device semantic search, auto-tagging, face clustering, a person-relationship graph, and conversational editing. All media processing is 100% local; only the optional chat uses a remote LLM (text only). Free, no ads.

Code (MIT): {GITHUB} — docs/ has the ADRs if you enjoy reading architecture decisions (ADR-005 local/remote separation, ADR-008 privacy red lines, ADR-013 the KMP contract).

Contributions welcome, especially on the model side — always looking for smaller/faster on-device models.""",
        "flair_keywords": ["project", "open source"],
    },
    "r/privacy": {
        "title": ("On-device AI photo gallery — tagging, face grouping, semantic search, all local; "
                  "MIT-licensed so you can verify (Android)"),
        "body": f"""Solo dev sharing a tool built around a premise this sub cares about: a photo library should be able to have useful AI without the photos ever leaving the phone.

PoLang (Android, MIT, source: {GITHUB}):

- Semantic search ("sunset at the beach"), auto-tagging (Florence-2), face grouping, person relationships — all computed on-device (MNN runtime; Qwen3-VL-2B for VLM tagging)
- No account needed for any of that; browsing, search and tagging work fully offline
- Free, no ads, no in-app purchases

Full disclosure so nobody has to dig it out of me: the optional AI-chat assistant uses a remote LLM. It sends text only — your prompts and its tool results — never photos, videos, or media metadata. You can use the entire gallery without ever opening the chat. The local/remote boundary is written up in the repo (docs/02-ARCHITECTURE/ADR — ADR-008 defines which data may never leave the device).

The price of on-device inference: first run downloads ~1.5 GB of local models. Android 7.0+, arm64 recommended.

If you'd rather not install it: the source is there to inspect. Happy to answer questions about the architecture.""",
        "flair_keywords": ["tool", "software"],
    },
}


def make_reddit():
    required = ["REDDIT_CLIENT_ID", "REDDIT_CLIENT_SECRET",
                "REDDIT_USERNAME", "REDDIT_PASSWORD"]
    missing = [k for k in required if not os.environ.get(k)]
    if missing:
        print(f"缺环境变量: {', '.join(missing)}（见脚本头部用法说明）")
        sys.exit(2)
    return praw.Reddit(
        client_id=os.environ["REDDIT_CLIENT_ID"],
        client_secret=os.environ["REDDIT_CLIENT_SECRET"],
        username=os.environ["REDDIT_USERNAME"],
        password=os.environ["REDDIT_PASSWORD"],
        user_agent=f"script:com.mamba.picme.launch:v1.0 (by /u/{os.environ['REDDIT_USERNAME']})",
        check_for_async=False,
    )


def check(reddit):
    me = reddit.user.me()
    print(f"账号: /u/{me.name}  created={me.created_utc:.0f}  karma(link/comment)="
          f"{me.link_karma}/{me.comment_karma}  verified_email={getattr(me, 'has_verified_email', '?')}")
    print("（新号或 karma<100 发链接帖易被 AutoMod 吞，见发布手册）\n")
    for name in POSTS:
        sub = reddit.subreddit(name.removeprefix("r/"))
        print(f"===== {name} =====")
        try:
            for rule in sub.rules()[:10]:
                desc = (rule.short_name or "") + (": " + rule.description.replace("\n", " ")[:120]
                                                  if rule.description else "")
                print(f"  规则 - {desc}")
        except Exception as e:
            print(f"  版规拉取失败: {e}")
        try:
            flairs = sub.flair.link_templates.user_selectable()
            if flairs:
                print("  可选 flair: " + ", ".join(f"{f['flair_text']}" for f in flairs))
            else:
                print("  flair: 无用户可选 flair（发后无需/无法设置）")
        except Exception as e:
            print(f"  flair 拉取失败: {e}")
        print()


def show_posts():
    for name, p in POSTS.items():
        print(f"===== {name} =====")
        print(f"TITLE: {p['title']}")
        print(f"BODY:\n{p['body']}\n")


def apply_flair(submission, sub_name):
    kw = [k.lower() for k in POSTS[sub_name]["flair_keywords"]]
    try:
        for f in submission.subreddit.flair.link_templates.user_selectable():
            if any(k in (f["flair_text"] or "").lower() for k in kw):
                submission.flair.select(f["flair_template_id"])
                print(f"  已设 flair: {f['flair_text']}")
                return
        print("  ⚠️ 未匹配到 flair，发后请手动设置（本版要求时）")
    except Exception as e:
        print(f"  ⚠️ flair 设置跳过: {e}")


def post(reddit, sub_name, style):
    p = POSTS[sub_name]
    sub = reddit.subreddit(sub_name.removeprefix("r/"))
    if style == "gallery" and "gallery" in p:
        images = [{"image_path": path, "caption": cap} for path, cap in p["gallery"]]
        missing = [i["image_path"] for i in images if not os.path.isfile(i["image_path"])]
        if missing:
            print(f"图库帖缺图片，回退 text: {missing}")
        else:
            submission = sub.submit_gallery(title=p["title"], images=images)
            submission.reply(body=p["body"])
            print(f"  已发图库帖 + 正文首评: {submission.shortlink}")
            apply_flair(submission, sub_name)
            return
    submission = sub.submit(title=p["title"], selftext=p["body"])
    print(f"  已发文本帖: {submission.shortlink}")
    apply_flair(submission, sub_name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="认证/账号/版规/flair 预检，不发帖")
    ap.add_argument("--list", action="store_true", help="打印将发内容")
    ap.add_argument("--post", metavar="SUB", help="真发，如 r/AndroidApps（一次一个版）")
    ap.add_argument("--style", default="text", choices=["text", "gallery"])
    args = ap.parse_args()

    if args.list and not args.post and not args.check:
        show_posts()
        return
    reddit = make_reddit()
    if args.check:
        check(reddit)
        return
    if args.post:
        if args.post not in POSTS:
            print(f"未知版: {args.post}（可选: {', '.join(POSTS)}）")
            sys.exit(2)
        post(reddit, args.post, args.style)
        return
    show_posts()


if __name__ == "__main__":
    main()
