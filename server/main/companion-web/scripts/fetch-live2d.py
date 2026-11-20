"""下载网页端用的 Live2D 运行库和官方示例模型，放到 public/live2d/ 下。

这些文件属于 Live2D Inc.，不放进仓库（仓库是公开的，放进去就成了再分发）。每个要运行网页端的环境都用这个脚本自己下载。
下载即表示接受 Live2D 的授权协议，所以必须带上 --accept-license：

- 运行库 live2dcubismcore.min.js：Live2D Proprietary Software License Agreement
  https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html
- 示例模型：Live2D Free Material License Agreement
  https://www.live2d.com/eula/live2d-free-material-license-agreement_en.html

年销售额 1000 万日元以上的企业对外发布时需要和 Live2D 另行签约，见 https://www.live2d.com/en/sdk/license/ 。

用法（在 companion-web 目录下）：python3 scripts/fetch-live2d.py --accept-license
只用 Python 标准库。已经下载过的文件不会重复下载。
"""

from __future__ import annotations

import json
import sys
import urllib.request
from pathlib import Path

CORE_URL = "https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js"
SAMPLES = "Live2D/CubismWebSamples"
BRANCH = "develop"
# 用到的示例模型。和 lib/roles.ts 里的对应关系保持一致。
MODELS = ["Hiyori", "Mao", "Mark"]
TARGET = Path(__file__).resolve().parent.parent / "public" / "live2d"


def get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "companion-web-live2d-fetch"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def save(path: Path, url: str) -> None:
    if path.exists() and path.stat().st_size > 0:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    data = get(url)
    path.write_bytes(data)
    print(f"  {path.relative_to(TARGET)}  {len(data)} bytes")


def main() -> int:
    if "--accept-license" not in sys.argv:
        print(__doc__)
        print("没有下载任何文件。确认接受上面的授权协议后，加上 --accept-license 再运行。")
        return 1
    print("运行库")
    save(TARGET / "core" / "live2dcubismcore.min.js", CORE_URL)
    for model in MODELS:
        print(model)
        tree = json.loads(get(f"https://api.github.com/repos/{SAMPLES}/git/trees/{BRANCH}:Samples/Resources/{model}?recursive=1"))
        for item in tree.get("tree", []):
            if item.get("type") != "blob":
                continue
            raw = f"https://raw.githubusercontent.com/{SAMPLES}/{BRANCH}/Samples/Resources/{model}/{item['path']}"
            save(TARGET / model.lower() / item["path"], raw)
    return 0


if __name__ == "__main__":
    sys.exit(main())
