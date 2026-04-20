from pathlib import Path

OUTPUT_FILE = "repo_source_dump.txt"

# ✅ WHITELIST folders (relative to repo root)
INCLUDE_DIRS = [
    "app",
    "modules",
    "gradle/wrapper",
    "scripts",
    ".github",
]

# ✅ Source extensions only
INCLUDE_EXTENSIONS = {
    ".kt", ".java", ".xml",
    ".gradle", ".kts",
    ".properties", ".json",
    ".yml", ".yaml",
    ".md", ".txt", ".pro" , ".py", ".cpp", ".h"
}

# ❌ Always excluded
EXCLUDE_DIRS = {
    ".git", ".gradle",
    "build", "venv", "__pycache__",
    "node_modules"
}

EXCLUDE_FILES = {
    "gradle-wrapper.jar",
    ".keystore",
    ".zip"
}


def is_excluded(path: Path) -> bool:
    return (
        any(p in EXCLUDE_DIRS for p in path.parts)
        or any(path.name.endswith(f) for f in EXCLUDE_FILES)
    )


def generate_tree(root: Path) -> str:
    lines = ["."]
    for base_dir in INCLUDE_DIRS:
        base = root / base_dir
        if not base.exists():
            continue

        lines.append(f"├── {base_dir}")
        for path in sorted(base.rglob("*")):
            if is_excluded(path):
                continue

            depth = len(path.relative_to(base).parts)
            indent = "│   " * depth
            lines.append(f"{indent}├── {path.name}")

    return "\n".join(lines)


def dump_source(root: Path, out):
    for base_dir in INCLUDE_DIRS:
        base = root / base_dir
        if not base.exists():
            continue

        for path in sorted(base.rglob("*")):
            if (
                path.is_file()
                and not is_excluded(path)
                and path.suffix in INCLUDE_EXTENSIONS
            ):
                out.write(f"\n//===== FILE: {path.relative_to(root)} =====\n")
                try:
                    out.write(path.read_text(encoding="utf-8"))
                except Exception:
                    out.write("[UNREADABLE FILE]")
                out.write("\n")


def main():
    root = Path(".").resolve()

    with open(OUTPUT_FILE, "w", encoding="utf-8") as out:
        out.write("================================\n")
        out.write("FILE TREE (SELECTED FOLDERS)\n")
        out.write("================================\n\n")
        out.write(generate_tree(root))

        out.write("\n\n================================\n")
        out.write("SOURCE CODE\n")
        out.write("================================\n")

        dump_source(root, out)

    print("✅ Selected folders extracted successfully")


if __name__ == "__main__":
    main()
