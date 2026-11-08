"""画面翻訳の評価（docs/05_画面翻訳.md「評価」）。

BenchActivity が書いた results.jsonl と、人が付けた正解（吹き出しの位置）を突き合わせ、
次の3点を数える。作者が見たい観点（2026-11-05）に合わせた。

1. 認識率：正解の吹き出しのうち、訳が1つ以上入ったものの割合
2. 一まとまり率：正解の吹き出しのうち、訳がちょうど1つ（分割されずに1まとまり）で入ったものの割合
3. 欠け：訳が最小の文字（7sp）でも枠に収まらなかった数と、読みにくい小ささ（9sp未満）の数
4. 文字の正解率：正解の文字（text）を付けた画像だけ。空白・改行を除いて編集距離で比べる

正解ファイル（truth.json）の形：
  {"Screenshot_xxx.jpg": {"area": [x0, y0, x1, y1], "bubbles": [{"id": "C1", "box": [x0, y0, x1, y1]}, ...]}}
area は評価する範囲（漫画の表示部分など）。範囲外の訳（アプリのUIなど）は数えない。

使い方：
  python tools/eval-screen.py tools/testdata/ocr/2026-11-05-out/results.jsonl tools/testdata/ocr/2026-11-05/truth.json
"""
import json
import sys

SMALL_SP = 9.0


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def squash(text):
    """空白と改行を除く（改行の位置や空白の数は読み取りの正しさに含めない）。"""
    return "".join(text.split())


def center(b):
    return ((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)


def inside(point, box, margin=0.05):
    x, y = point
    w, h = box[2] - box[0], box[3] - box[1]
    return box[0] - w * margin <= x <= box[2] + w * margin and box[1] - h * margin <= y <= box[3] + h * margin


def evaluate(result, truth):
    area = truth["area"]
    targets = [t for t in result["targets"] if inside(center(t["bounds"]), area, 0)]
    skipped = [s for s in result.get("skipped", []) if isinstance(s, dict) and inside(center(s["bounds"]), area, 0)]
    rows = []
    used = set()
    for bubble in truth["bubbles"]:
        hits = [i for i, t in enumerate(targets) if inside(center(t["bounds"]), bubble["box"])]
        missed = [s for s in skipped if inside(center(s["bounds"]), bubble["box"])]
        used.update(hits)
        recognized = squash("".join(targets[i]["source"] for i in hits))
        truth_text = squash(bubble.get("text", ""))
        rows.append({
            "id": bubble["id"],
            "chars": len(truth_text),
            "errors": edit_distance(recognized, truth_text) if truth_text else 0,
            "source": recognized,
            "pieces": len(hits),
            "skipped": len(missed),
            "translation": " / ".join(targets[i]["translation"].replace("\n", " ") for i in hits),
        })
    outside = [t for i, t in enumerate(targets) if i not in used]
    n = len(rows)
    return {
        "bubbles": n,
        "recognized": sum(1 for r in rows if r["pieces"] > 0),
        "one_unit": sum(1 for r in rows if r["pieces"] == 1),
        "pieces": sum(r["pieces"] for r in rows),
        "skipped_in_bubbles": sum(r["skipped"] for r in rows),
        "not_fitted": sum(1 for t in targets if t.get("fitted") is False),
        "small": sum(1 for t in targets if t.get("textSp", 99) < SMALL_SP),
        "outside": len(outside),
        "targets": len(targets),
        "ms": result["ocrMs"] + result["translateMs"],
        "chars": sum(r["chars"] for r in rows),
        "errors": sum(r["errors"] for r in rows),
        "rows": rows,
    }


def accuracy(e):
    """正解の文字（truth.json の text）がある画像だけ、読み取りの文字の正解率（1 − 編集距離/正解の字数）を出す。"""
    if not e["chars"]:
        return "－"
    return f"{max(0.0, 1 - e['errors'] / e['chars']):.1%}"


def main():
    results = [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
    truth = json.load(open(sys.argv[2], encoding="utf-8"))
    verbose = "-v" in sys.argv
    print("| 画像 | 吹き出し | 認識 | 一まとまり | 訳の数 | 吹き出し内で訳さず | 枠に収まらず | 9sp未満 | 吹き出し外 | 文字の正解率 | 時間 |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    total = {"bubbles": 0, "recognized": 0, "one_unit": 0}
    for r in results:
        if r["image"] not in truth:
            continue
        e = evaluate(r, truth[r["image"]])
        for k in total:
            total[k] += e[k]
        print(f"| {r['image']} | {e['bubbles']} | {e['recognized']} | {e['one_unit']} | {e['pieces']} | "
              f"{e['skipped_in_bubbles']} | {e['not_fitted']} | {e['small']} | {e['outside']} | "
              f"{accuracy(e)} | {e['ms'] / 1000:.1f}秒 |")
        if verbose:
            for row in e["rows"]:
                print(f"    {row['id']}: {row['pieces']}分割 {row['translation']}")
                if row["chars"]:
                    print(f"        読み取り（誤り{row['errors']}/{row['chars']}字）: {row['source']}")
    if total["bubbles"]:
        print(f"\n合計：認識率 {total['recognized'] / total['bubbles']:.0%}（{total['recognized']}/{total['bubbles']}）、"
              f"一まとまり率 {total['one_unit'] / total['bubbles']:.0%}（{total['one_unit']}/{total['bubbles']}）")


if __name__ == "__main__":
    main()
