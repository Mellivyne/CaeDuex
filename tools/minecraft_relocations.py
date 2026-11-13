"""Apply the Minecraft 26.3 source relocations shared by loader builds."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHANGES = sorted(
    json.loads((ROOT / "versions/unobfuscated-26.3/relocations.json").read_text("utf-8")).items(),
    key=lambda pair: len(pair[0]),
    reverse=True,
)


def relocate_26_3(text: str, file_name: str) -> str:
    for _ in range(2):
        for source, replacement in CHANGES:
            if file_name != "BlockStateNbt.java" or not source.startswith("NbtUtils."):
                text = text.replace(source, replacement)
    return text
