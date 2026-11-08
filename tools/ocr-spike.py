"""縦書きを読める文字認識（PP-OCR系のONNXモデル）をPCで試し、ML Kitと同じ採点にかける（2026-11-05）。

BenchActivity と同じ形の results.jsonl を書くので、tools/eval-screen.py で読み取りの文字の正解率を比べられる。
アプリへ組み込む前に、候補のモデルが縦書きの漫画をどれだけ読めるかを確かめるための試作。

使い方（PC、ONNX Runtimeを入れたPython環境で）：
  python tools/ocr-spike.py --model manga --images tools/testdata/ocr/2026-11-05 --out tools/testdata/ocr/2026-11-05-out/spike-manga
  python tools/ocr-spike.py --model v5    --images ... --out ...

モデルは models/ocr/<名前>/ に置く（Gitの対象外）。
  manga：Kellenok/PP-OCRv6_manga（日本語・中国語の漫画向けに調整、Apache-2.0）
  v5   ：PaddlePaddle/PP-OCRv5_mobile（簡体字・繁体字・英語・日本語、Apache-2.0）
前処理と復号はモデルカードと inference.yml の値に合わせた。
"""
import argparse
import json
import os
import time

import cv2
import numpy as np
import onnxruntime as ort
import pyclipper

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_vocab(model):
    base = os.path.join(ROOT, "models", "ocr", model)
    if model == "manga":
        with open(os.path.join(base, "dict.txt"), encoding="utf-8") as f:
            chars = [line.rstrip("\n") for line in f]
    else:
        # inference.yml の PostProcess.character_dict（YAMLの配列）を読む
        import yaml
        with open(os.path.join(base, "rec.yml"), encoding="utf-8") as f:
            chars = [str(c) for c in yaml.safe_load(f)["PostProcess"]["character_dict"]]
    # CTCの0番は空白記号（blank）。辞書の後ろに半角空白を足す（use_space_char）
    return ["<blank>"] + chars + [" "]


def det_preprocess(img, limit=960):
    h, w = img.shape[:2]
    scale = limit / max(h, w)
    nh, nw = max(32, int(round(h * scale / 32)) * 32), max(32, int(round(w * scale / 32)) * 32)
    resized = cv2.resize(img, (nw, nh)).astype(np.float32) / 255.0
    resized = (resized - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
    return resized.transpose(2, 0, 1)[None].astype(np.float32), (h / nh, w / nw)


def unclip(box, ratio=1.5):
    poly = np.array(box)
    area = cv2.contourArea(poly.astype(np.float32))
    length = cv2.arcLength(poly.astype(np.float32), True)
    if length == 0:
        return None
    offset = pyclipper.PyclipperOffset()
    offset.AddPath(poly.astype(np.int64).tolist(), pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
    out = offset.Execute(area * ratio / length)
    return np.array(out[0]) if out else None


def detect(session, img, thresh=0.3, box_thresh=0.6):
    x, (sy, sx) = det_preprocess(img)
    prob = session.run(None, {session.get_inputs()[0].name: x})[0][0, 0]
    mask = (prob > thresh).astype(np.uint8)
    contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for c in contours[:1000]:
        if cv2.contourArea(c) < 9:
            continue
        rect_mask = np.zeros_like(mask)
        cv2.drawContours(rect_mask, [c], -1, 1, -1)
        if prob[rect_mask > 0].mean() < box_thresh:
            continue
        expanded = unclip(cv2.boxPoints(cv2.minAreaRect(c)))
        if expanded is None:
            continue
        pts = cv2.boxPoints(cv2.minAreaRect(expanded.astype(np.float32)))
        pts[:, 0] *= sx
        pts[:, 1] *= sy
        boxes.append(pts)
    return boxes


def crop(img, pts):
    """回転した矩形を切り出して水平にする（PaddleOCRの get_rotate_crop_image と同じ考え方）。"""
    pts = pts[np.argsort(pts[:, 0] + pts[:, 1])]
    tl, br = pts[0], pts[3]
    tr, bl = (pts[1], pts[2]) if pts[1][0] > pts[2][0] else (pts[2], pts[1])
    src = np.array([tl, tr, br, bl], dtype=np.float32)
    w = int(max(np.linalg.norm(tr - tl), np.linalg.norm(br - bl)))
    h = int(max(np.linalg.norm(bl - tl), np.linalg.norm(br - tr)))
    if w < 2 or h < 2:
        return None
    dst = np.array([[0, 0], [w, 0], [w, h], [0, h]], dtype=np.float32)
    return cv2.warpPerspective(img, cv2.getPerspectiveTransform(src, dst), (w, h), borderMode=cv2.BORDER_REPLICATE)


def recognize(session, vocab, image):
    h, w = image.shape[:2]
    vertical = h >= w * 1.5
    if vertical:
        # 縦書きの行は90度左に回して横書きとして読む（モデルカードの例に合わせた）
        image = cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE)
        h, w = image.shape[:2]
    nw = int(min(3200, max(16, round(48 * w / h))))
    x = cv2.resize(image, (nw, 48)).astype(np.float32) / 255.0
    x = ((x - 0.5) / 0.5).transpose(2, 0, 1)[None].astype(np.float32)
    out = session.run(None, {session.get_inputs()[0].name: x})[0][0]
    ids = out.argmax(axis=1)
    text, probs, prev = [], [], -1
    for t, i in enumerate(ids):
        if i != prev and i != 0 and i < len(vocab):
            text.append(vocab[i])
            probs.append(float(out[t, i]))
        prev = i
    # 確信度：採用した字の確率の平均（出力が確率でない場合も相対比較には使える）
    conf = sum(probs) / len(probs) if probs else 0.0
    return "".join(text), vertical, conf


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=["manga", "v5"], required=True)
    ap.add_argument("--images", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    base = os.path.join(ROOT, "models", "ocr", args.model)
    opts = ort.SessionOptions()
    det = ort.InferenceSession(os.path.join(base, "det.onnx"), opts, providers=["CPUExecutionProvider"])
    rec = ort.InferenceSession(os.path.join(base, "rec.onnx"), opts, providers=["CPUExecutionProvider"])
    vocab = load_vocab(args.model)
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "results.jsonl"), "w", encoding="utf-8") as out:
        for name in sorted(os.listdir(args.images)):
            if not name.lower().endswith((".jpg", ".png")):
                continue
            img = cv2.imread(os.path.join(args.images, name))
            t0 = time.time()
            lines = []
            for pts in detect(det, img):
                c = crop(img, pts)
                if c is None:
                    continue
                text, vertical, conf = recognize(rec, vocab, c)
                if not text.strip():
                    continue
                x0, y0 = pts.min(axis=0)
                x1, y1 = pts.max(axis=0)
                lines.append({"bounds": [int(x0), int(y0), int(x1), int(y1)], "source": text, "conf": round(conf, 3),
                              "vertical": vertical, "translation": "", "script": ""})
            # 読み順：縦書きは右の列から、横書きは上から（吹き出しごとの連結の順になる）
            lines.sort(key=lambda t: (-t["bounds"][2], t["bounds"][1]) if t["vertical"] else (t["bounds"][1], t["bounds"][0]))
            ms = int((time.time() - t0) * 1000)
            out.write(json.dumps({"image": name, "ocrMs": ms, "translateMs": 0, "blocks": len(lines),
                                  "targets": lines, "skipped": []}, ensure_ascii=False) + "\n")
            print(f"{name}: {len(lines)}行 {ms}ms")


if __name__ == "__main__":
    main()
